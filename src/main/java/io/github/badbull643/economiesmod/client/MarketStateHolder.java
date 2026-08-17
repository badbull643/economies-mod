package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.*;
import io.github.badbull643.economiesmod.core.net.HostServer;
import io.github.badbull643.economiesmod.core.net.MarketClient;
import io.github.badbull643.economiesmod.core.net.Message;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Owns the active market for the current world, in one of three modes:
 *
 *  LOCAL     — this process owns the log. Events are appended and applied
 *              immediately. Single-player, no network.
 *  CONNECTED — a remote host owns the log. Events are proposed and only applied
 *              when the host broadcasts them back. Asynchronous.
 *  HOSTING   — this process runs the HostServer AND connects to it as a client,
 *              so the host's own trades take the same path as everyone else's.
 */
public class MarketStateHolder {

    public enum Mode { LOCAL, CONNECTED, HOSTING }

    /** Written from market-connect and market-host-start, read every frame by the
     *  screen's render — so it crosses threads in both directions. */
    private static volatile Mode mode = Mode.LOCAL;

    // LOCAL mode
    private static MarketState localState;
    private static EventLog localLog;

    private static PlayerKeys keys;
    private static Path currentWorldDir;

    private static MarketHighWater highWater;

    private static PendingOps pendingOps;

    /**
     * The journal of half-finished inventory operations, or null before a world loads.
     *
     * Lives beside the log because it is scoped to the same world, and is meaningless
     * against a different one.
     */
    public static PendingOps pendingOps() { return pendingOps; }

    /**
     * How far behind this world's log is from the furthest this market has been seen
     * to reach, or 0 if we're level with it. Hosting while behind is what silently
     * forks a market.
     */
    public static long eventsBehind() {
        MarketState s = get();
        if (highWater == null || s == null || s.marketId() == null) return 0;

        long seen = highWater.seenFor(s.marketId());
        long mine = localLog != null ? localLog.lastSeq() : 0;
        if (client != null) mine = Math.max(mine, client.lastSeq());
        return Math.max(0, seen - mine);
    }

    /** Records that this market was seen at a given height, from a poll or a sync. */
    public static void observeMarketHeight(UUID marketId, long seq) {
        if (highWater != null) highWater.observe(marketId, seq);
    }

    /**
     * A host advertising a head that isn't on our chain.
     *
     * Not an error on its own — one of us is on a branch the other doesn't have, and
     * which of us is "right" isn't a question the data answers. It's a warning that
     * trading with that host will not do what either party expects.
     */
    public static class Divergence {
        public final String hostName;
        public final long seq;
        public final String theirHash;
        public final String ourHash;

        Divergence(String hostName, long seq, String theirHash, String ourHash) {
            this.hostName = hostName;
            this.seq = seq;
            this.theirHash = theirHash;
            this.ourHash = ourHash;
        }

        public String describe() {
            return (hostName == null ? "a host" : hostName)
                    + " is on a different branch of this market (differs at event " + seq + ")";
        }
    }

    private static volatile Divergence divergence;

    /** The most recently detected divergence, or null if everything we've seen agrees. */
    public static Divergence divergence() { return divergence; }

    // (hostUserId → "seq:hash") for heads we've already compared. Checking costs a full
    // read of the log, and a poll repeats every 10s against a head that usually hasn't
    // moved, so the same comparison would otherwise be redone indefinitely.
    private static final Map<String, String> checkedHeads = new ConcurrentHashMap<>();

    /**
     * Compares a discovered host's advertised head against our own chain.
     *
     * This is the cheap half of Certificate Transparency's gossip idea: participants
     * comparing what they've each been told, so a split shows up without anyone having
     * to attempt a connection first. Discovery already fetches (seq, hash) from every
     * host it polls — signed, and nonce-bound against replay — so the comparison costs
     * nothing extra on the wire.
     *
     * Only meaningful when their head is at or behind ours: our hash at their seq is a
     * point they must also have if we share a history. If they're ahead of us we hold
     * no opinion, which is the same position the host takes during a handshake.
     */
    public static void observeHostHead(UUID marketId, long seq, String hash,
                                       String hostUserId, String hostName) {
        observeMarketHeight(marketId, seq);

        MarketState s = get();
        if (s == null || s.marketId() == null || marketId == null) return;
        if (!s.marketId().equals(marketId)) return;          // different market entirely
        if (hash == null || hostUserId == null || seq <= 0) return;
        if (currentWorldDir == null || chainBrokenAt != -1) return;

        String head = seq + ":" + hash;
        if (head.equals(checkedHeads.get(hostUserId))) return;

        try {
            EventLog log = new EventLog(logPathFor(currentWorldDir));
            if (seq > log.lastSeq()) return;      // they're ahead; nothing of ours to compare
            String ours = log.hashAt(seq);
            if (ours == null) return;

            checkedHeads.put(hostUserId, head);

            if (ours.equals(hash)) {
                // They're on our chain after all — clear any earlier warning about them.
                Divergence d = divergence;
                if (d != null && hostName != null && hostName.equals(d.hostName)) {
                    divergence = null;
                }
            } else {
                divergence = new Divergence(hostName, seq, hash, ours);
                System.err.println("[economiesmod] divergence: " + hostName
                        + " reports " + hash + " at event " + seq
                        + ", we have " + ours);
            }
        } catch (IOException e) {
            // A poll is best-effort; a read failure here is not worth surfacing.
        }
    }

    /** What the journal turned out to mean, once checked against the log. */
    public static class Recovery {
        /** Deposits whose event never landed — these items must go back. */
        public final List<PendingOps.Op> refunds = new ArrayList<>();
        /** Withdrawals that may never have reached the player. Reported, never re-given:
         *  nothing records whether the hand-over completed, so acting on these would
         *  mint items every time the crash landed after the give rather than before. */
        public final List<PendingOps.Op> unconfirmed = new ArrayList<>();

        public boolean isEmpty() { return refunds.isEmpty() && unconfirmed.isEmpty(); }
    }

    /**
     * Settles the journal against the log, and empties it.
     *
     * Deposits are decided exactly: the log either contains the event or it doesn't,
     * and it will never contain it later — the proposal died with the process that
     * made it. Withdrawals can't be decided at all, so they are only described.
     *
     * If the log can't be read, nothing is resolved and the journal is left intact.
     * Guessing here would either duplicate items or destroy them, and the entry costs
     * nothing to keep until a start that can read the log properly.
     */
    public static Recovery resolvePendingOps() {
        Recovery out = new Recovery();
        if (pendingOps == null || pendingOps.isEmpty() || currentWorldDir == null) return out;

        Set<String> landed = new HashSet<>();
        try {
            EventLog log = new EventLog(logPathFor(currentWorldDir));
            for (SequencedEvent se : log.readFrom(1)) {
                if (se.event != null && se.event.clientEventId != null) {
                    landed.add(se.event.clientEventId);
                }
            }
        } catch (IOException e) {
            System.err.println("[economiesmod] could not read the log to settle pending"
                    + " inventory operations — leaving them for next time: " + e);
            return out;
        }

        for (PendingOps.Op op : pendingOps.all()) {
            if (op.isDeposit()) {
                if (!landed.contains(op.clientEventId)) out.refunds.add(op);
                pendingOps.clearDeposit(op.clientEventId);
            } else if (op.isWithdraw()) {
                out.unconfirmed.add(op);
                pendingOps.clearWithdraw(op.seq);
            }
        }
        return out;
    }

    /** Seq of the first broken link in the local log, or -1 if the chain is sound. */
    private static long chainBrokenAt = -1;
    private static String damageReason;

    public static long chainBrokenAt() { return chainBrokenAt; }

    /** Why the local log can't be used, or null if it's fine. */
    public static String damageReason() { return damageReason; }


    private static HostServer hostServer;
    private static Thread hostThread;

    // in MarketStateHolder
    private static int myHostPort = 25555;

    public static void setMyHostPort(int port) { myHostPort = port; }
    public static int myHostPort() { return myHostPort; }


    private static Path identityFile;

    /** Where this player's signing key lives. Deliberately visible in the UI. */
    public static Path identityPath() { return identityFile; }

    /** Loads (or generates) this player's signing identity. Call once at mod init. */
    public static void loadKeys(Path keyFile) {
        identityFile = keyFile;
        try {
            keys = PlayerKeys.loadOrCreate(keyFile);
            System.out.println("[economiesmod] identity loaded from " + keyFile);
        } catch (Exception e) {
            System.err.println("[economiesmod] failed to load identity: " + e);
        }
    }

    // CONNECTED mode
    private static MarketClient client;

    /** Called when a proposal is rejected, in either mode. */
    private static Consumer<String> onRejected = reason -> {};

    private static Consumer<AppliedEvent> onApplied = a -> {};

    /**
     * What actually gets wired to every apply path, in both modes.
     *
     * Bookkeeping that must happen whatever the UI does with the event goes here, so
     * it can't be lost by a caller replacing the handler — and so LOCAL and CONNECTED
     * cannot drift apart, which is where this class has been bitten before.
     */
    private static final Consumer<AppliedEvent> APPLIED = a -> {
        noteApplied(a.event);
        recordActivity(a.event);
        onApplied.accept(a);
    };

    // ─────────── activity feed ───────────
    //
    // The tail of the log, kept in memory so a dashboard panel can show what has been
    // happening without reading a file every frame. Fed from APPLIED rather than from
    // either mode's own path, for the same reason everything else here is: LOCAL and
    // CONNECTED must not be able to drift apart.
    //
    // Deliberately not filtered to live events. A synced history is exactly the thing
    // someone joining wants to see in a "recent activity" panel, and unlike handing over
    // items, showing an event twice costs nothing.

    private static final int ACTIVITY_MAX = 64;

    /** Guarded by itself: written from the network reader thread, read from the render
     *  thread. */
    private static final Deque<SequencedEvent> activity = new ArrayDeque<>();

    private static void recordActivity(SequencedEvent se) {
        if (se == null || se.event == null) return;
        synchronized (activity) {
            activity.addLast(se);
            while (activity.size() > ACTIVITY_MAX) activity.removeFirst();
        }
    }

    /** Fills the feed from a log that was replayed without going through APPLIED. */
    private static void seedActivity(EventLog log) {
        synchronized (activity) {
            activity.clear();
        }
        if (log == null) return;
        try {
            long from = Math.max(0, log.lastSeq() - ACTIVITY_MAX);
            for (SequencedEvent se : log.readFrom(from)) recordActivity(se);
        } catch (IOException e) {
            // A dashboard panel is not worth failing a world load over.
            System.err.println("[economiesmod] could not read recent activity: " + e);
        }
    }

    /** Most recent last. A copy, so the render thread never iterates a live deque. */
    public static List<SequencedEvent> recentActivity() {
        synchronized (activity) {
            return new ArrayList<>(activity);
        }
    }

    // ─────────── recovery note ───────────
    //
    // Result of settling interrupted inventory operations at world load.
    //
    // Held rather than shown immediately, because it is worked out before the player
    // has any reason to open the market screen — and an item silently reappearing in
    // your inventory with no explanation is worse than the original problem.
    //
    // Lives here rather than on MarketScreen because it is written at world load, when
    // no screen exists, and read by whichever screen opens next. That is session state,
    // not screen state, which is the reason the field on MarketScreen had to be static
    // and could not simply be demoted to an instance field with the status line.

    private static volatile String recoveryNote = "";

    public static void reportRecovery(int returned, int unconfirmed) {
        StringBuilder sb = new StringBuilder();
        if (returned > 0) {
            sb.append("Returned items from ").append(returned)
              .append(returned == 1 ? " deposit that" : " deposits that")
              .append(" never completed");
        }
        if (unconfirmed > 0) {
            if (sb.length() > 0) sb.append(". ");
            sb.append(unconfirmed).append(unconfirmed == 1 ? " withdrawal" : " withdrawals")
              .append(" may not have reached you — see the log");
        }
        recoveryNote = sb.toString();
    }

    public static String recoveryNote() { return recoveryNote; }

    /** Acknowledged: it describes something already done, so it is shown only once. */
    public static void clearRecoveryNote() { recoveryNote = ""; }

    public static void setOnApplied(Consumer<AppliedEvent> handler) {
        onApplied = handler;
        if (client != null) client.setOnApplied(APPLIED);
    }

    /**
     * An event we were waiting on has landed, so its journal entry can go.
     *
     * Keyed on clientEventId rather than the event's contents: it is the only thing
     * that ties a line in the log back to the specific inventory operation that
     * started it, which is what makes the deposit recovery exact.
     */
    private static void noteApplied(SequencedEvent se) {
        if (pendingOps == null || se == null || se.event == null) return;
        if (se.event.clientEventId != null) {
            pendingOps.clearDeposit(se.event.clientEventId);
        }
    }

    public static void setOnRejected(Consumer<String> handler) {
        onRejected = handler;
        if (client != null) client.setOnRejected(handler);
    }

    public static Mode mode() { return mode; }

    public static MarketState get() {
        if (mode != Mode.LOCAL) {
            return client != null ? client.state() : new MarketState();
        }
        if (localState == null) localState = new MarketState();
        return localState;
    }

    private static PeerCache peerCache;

    public static void loadPeers(Path peerFile) {
        peerCache = new PeerCache(peerFile);
    }

    private static Settings settings;

    public static void loadSettings(Path settingsFile) {
        settings = new Settings(settingsFile);
        myHostPort = settings.hostPort();
    }

    /**
     * Persisted preferences, or null before they've been loaded.
     *
     * Callers must tolerate null — the screen can in principle be reached before
     * SERVER_STARTED has run, and a missing settings file is not worth crashing over.
     */
    public static Settings settings() { return settings; }


    // ─────────── LOCAL mode ───────────

    public static void loadLocal(Path worldDir) {
        // Only when the world itself changes. This is also called on reset, disconnect
        // and switching, and re-reading the marker on those would quietly overrule a
        // switch whose marker failed to write.
        if (!worldDir.equals(currentWorldDir)) {
            activeSlot = MarketSlots.active(worldDir);
        }

        currentWorldDir = worldDir;
        mode = Mode.LOCAL;
        disconnectIfConnected();

        // Catches Exception, not just IOException: this runs on the server thread during
        // world load, so anything escaping here takes the whole world down before the
        // player can reach the Reset button that would fix it.
        highWater = new MarketHighWater(
                logPathFor(worldDir).resolveSibling("high-water.json"));
        pendingOps = new PendingOps(
                logPathFor(worldDir).resolveSibling("pending-ops.json"));

        try {
            localLog = new EventLog(logPathFor(worldDir));
            chainBrokenAt = localLog.verifyChain();
            damageReason = localLog.damageReason();
            if (chainBrokenAt != -1) {
                System.err.println("[economiesmod] log unusable: " + damageReason);
            }
            localState = EventApplier.replay(localLog);
            // Replay here goes straight through EventApplier rather than through APPLIED,
            // so the feed has to be filled from the log by hand. A synced history does
            // arrive through APPLIED and fills it on its own.
            seedActivity(localLog);
            System.out.println("[economiesmod] local: replayed " + localLog.lastSeq() + " events");
        } catch (Exception e) {
            System.err.println("[economiesmod] local log load failed: " + e);
            e.printStackTrace();
            localLog = null;
            localState = new MarketState();
            chainBrokenAt = 0;
            damageReason = "could not be read at all (" + e.getMessage() + ")";
        }
    }

    // ─────────── CONNECTED mode ───────────

    public static void connect(String host, int port, UUID userId, String displayName) {
        // A damaged log must not join a market. Its lastHash can coincidentally match
        // the host's — that is exactly what a duplicated sequence number produces — so
        // the handshake admits it and the replica then diverges silently: orders that
        // exist locally and nowhere else, fills that resolve differently on each side.
        if (chainBrokenAt != -1) {
            onRejected.accept("your log is damaged at event " + chainBrokenAt
                    + " — Reset log before connecting (you would lose "
                    + describeLoss(userId) + ")");
            return;
        }

        // Stop hosting first. A running HostServer owns the log file; connecting while
        // it runs leaves two EventLog instances appending to the same file, which
        // silently corrupts it (duplicate sequence numbers, broken chain).
        if (hostServer != null) {
            System.out.println("[economiesmod] stopping host before connecting out");
            stopHosting();
        }
        connect(host, port, userId, displayName, Mode.CONNECTED, true);

        // If we were refused for being ahead, and our history simply extends theirs,
        // hand them the difference and try once more. Otherwise this needs two people
        // to work out between them which of them should host next, for a situation the
        // machine can settle on its own.
        if (!isConnected() && lastRefusal != null
                && HostServer.Refusal.AHEAD.equals(lastRefusal.code)) {
            if (offerCatchUp(host, port, userId, lastRefusal)) {
                connect(host, port, userId, displayName, Mode.CONNECTED, true);
            }
        }
    }

    /** The most recent handshake refusal, kept so connect() can act on it. */
    private static MarketClient.Refused lastRefusal;

    /**
     * Hands a stale host the events it's missing. Returns true if it took them.
     *
     * Only attempted when their head is genuinely an ancestor of ours — checked here,
     * on our own log, because the host can't tell: Hello only carries our head, so from
     * where they stand "ahead of me" and "diverged from me" look identical.
     */
    private static boolean offerCatchUp(String host, int port, UUID userId,
                                        MarketClient.Refused refusal) {
        try {
            EventLog log = new EventLog(logPathFor(currentWorldDir));
            String ourHashAtTheirHead = log.hashAt(refusal.hostSeq);

            if (ourHashAtTheirHead == null
                    || !ourHashAtTheirHead.equals(refusal.hostHash)) {
                // Their head isn't on our chain — we've genuinely diverged, and the
                // extra events aren't ours to give. Migrate is NOT the answer here:
                // it refuses a branch of the same market, because our position already
                // includes the shared history their copy also has, and crediting it
                // again would pay us twice for it.
                onRejected.accept("your history diverged from that host's — Reset log to"
                        + " rejoin them. You keep everything from before you diverged;"
                        + " only what you did afterwards is lost.");
                // Logged, not just shown: without this a genuine fork is invisible in the
                // console — the connect attempt simply stops, looking identical to a hang.
                // The fast-forward case below announces itself, so the two outcomes have
                // to be told apart from the log alone.
                System.err.println("[economiesmod] diverged from host at seq "
                        + refusal.hostSeq + " (ours " + ourHashAtTheirHead
                        + ", theirs " + refusal.hostHash + ") — not a fast-forward");
                return false;
            }

            List<String> missing = log.rawLinesFrom(refusal.hostSeq + 1);
            if (missing.isEmpty()) return false;

            System.out.println("[economiesmod] host is " + missing.size()
                    + " events behind on its own market — offering them");

            Message.CatchUpResult result =
                    MarketClient.offerCatchUp(host, port, userId, missing);

            if (!result.accepted) {
                onRejected.accept("host would not catch up: " + result.reason);
                System.err.println("[economiesmod] host refused the catch-up after "
                        + result.applied + " events: " + result.reason);
                return false;
            }
            System.out.println("[economiesmod] host accepted " + result.applied + " events");
            return true;

        } catch (IOException e) {
            onRejected.accept("could not bring that host up to date: " + e.getMessage());
            return false;
        }
    }

    private static void connect(String host, int port, UUID userId, String displayName,
                                Mode targetMode, boolean persist) {

        if (keys == null) {
            onRejected.accept("no identity loaded");
            return;
        }

        // Cleared at the top of every attempt, not just on success — otherwise a
        // refusal from one host (or one earlier attempt to this one) can still be
        // sitting here when a later attempt fails a different way, e.g. a plain
        // socket IOException, and the public connect() wrapper would act on stale
        // hostSeq/hostHash that has nothing to do with the current target.
        lastRefusal = null;

        // Capture what a reset would cost BEFORE tearing down the current connection.
        // disconnectIfConnected() drops the client while leaving mode as CONNECTED, so
        // get() would hand back an empty MarketState and the refusal would cheerfully
        // report "you would lose nothing" about an irreversible action.
        String lossIfReset = describeLoss(userId);

        disconnectIfConnected();


        try {
            // Always re-open from disk rather than reusing localLog. A reused instance
            // carries an in-memory lastSeq/lastHash that may have gone stale — which is
            // precisely what let a duplicate sequence number get appended before.
            EventLog log = new EventLog(logPathFor(currentWorldDir));

            MarketClient c = new MarketClient(userId, displayName, keys, log, persist,
                    peerCache, myHostPort);
            c.setOnRejected(onRejected);
            c.setOnApplied(APPLIED);
            // Describes the world we are actually in. Honest, which is why it catches
            // only people who are also being honest — see WorldAttestation.
            WorldAttestation described = WorldFacts.of(
                    MinecraftClient.getInstance().getServer());
            c.setAttestation(described);
            // Remembered as the baseline, so the first poll after connecting does not
            // re-send what the handshake has just said.
            lastToldCheats = described != null && described.cheatsAvailable();
            lastToldGameMode = described == null || described.gameMode == null
                    ? "" : described.gameMode;
            c.connect(host, port);

            client = c;
            localLog = log;
            localState = null;
            mode = targetMode;
            lastRefusal = null;

            // Remembered so a dropped broadcast can be recovered by reconnecting to
            // the same host without the player re-entering anything. See resync().
            connectedHost = host;
            connectedPort = port;
            connectedAs = displayName;
            connectedUserId = userId;

            System.out.println("[economiesmod] connected to " + host + ":" + port
                    + " at seq " + c.lastSeq());
        } catch (MarketClient.Refused e) {
            lastRefusal = e;
            onRejected.accept(e.getMessage() + explainRemedy(e.code, lossIfReset));
            System.err.println("[economiesmod] refused: " + e.getMessage());
        } catch (IOException e) {
            onRejected.accept("connect failed: " + e.getMessage());
            System.err.println("[economiesmod] connect failed: " + e);
        }
    }

    /**
     * Turns a refusal into something the player can act on.
     *
     * Every one of these ends in "reset your log", and resetting is irreversible, so
     * the cost is stated up front rather than left to be discovered after clicking.
     */
    private static String explainRemedy(String code, String loss) {
        if (code == null) return "";

        // Right identity, wrong key — resetting your log would not help, since the
        // market's record of you lives in everyone else's copy too.
        if (HostServer.Refusal.KEY_MISMATCH.equals(code)) {
            Path id = identityPath();
            return " — copy your identity file across from your other computer"
                    + (id == null ? "" : " (" + id.getFileName() + ")");
        }

        // AHEAD is the one refusal where resetting is the WRONG move. It means we hold
        // events the host does not — so the host is the stale one and our log is the
        // current history. Telling the up-to-date party to discard theirs is how a
        // group destroys the real market to match a copy that fell behind.
        if (HostServer.Refusal.AHEAD.equals(code)) {
            return " — this host is behind you, not the other way round."
                    + " Do NOT reset; ask them to connect and catch up first.";
        }

        // A fork is a divergence within a market you both hold, so resetting costs only
        // what you did after you split — everything before it is in their copy too.
        // Quoting the whole position here, as if it were a different market, makes a
        // cheap recovery look ruinous and pushes people towards keeping a dead branch.
        if (HostServer.Refusal.FORK.equals(code)) {
            return " — Reset log to rejoin them. You keep everything from before you"
                    + " diverged; only what you did afterwards is lost.";
        }

        boolean recoverable = HostServer.Refusal.DIFFERENT_MARKET.equals(code)
                || HostServer.Refusal.NO_IDENTITY.equals(code);
        if (!recoverable) return "";

        // These two really do share nothing with the destination, so the full position
        // is the honest figure.
        String action = HostServer.Refusal.DIFFERENT_MARKET.equals(code)
                ? " — to join theirs instead, Migrate (carries your balance) or Reset log"
                : " — to join, Reset log";

        return "nothing".equals(loss)
                ? action + " (Reset would lose nothing)"
                : action + " (Reset would lose " + loss + ")";
    }


    public static void disconnect() {
        disconnectIfConnected();
        if (currentWorldDir != null) {
            loadLocal(currentWorldDir);
        } else {
            mode = Mode.LOCAL;
        }
    }

    private static void disconnectIfConnected() {
        if (client != null) {
            client.disconnect();
            client = null;
        }
    }

    public static boolean isConnected() {
        return mode != Mode.LOCAL && client != null && client.isConnected();
    }

    /**
     * Whether the host we are connected to is a dedicated server.
     *
     * False when not connected, which callers must read as "unknown" rather than "no" —
     * it is only ever learned from a Sync.
     */
    public static boolean hostIsDedicated() {
        return client != null && client.hostIsDedicated();
    }

    /**
     * Drops back to LOCAL when the link died without an explicit Disconnect.
     *
     * Without this, mode stays CONNECTED after the host goes away: trading is still
     * permitted (and fails later with a vague "not connected"), the order book keeps
     * showing the dead connection's replica, and the local log is never reopened.
     * Cheap enough to call every frame — it only does work on the transition.
     */
    // ─────────── resync after a missed broadcast ───────────

    private static String connectedHost;
    private static int connectedPort;
    private static String connectedAs;
    private static UUID connectedUserId;

    /**
     * Consecutive resync attempts, and when the last one was.
     *
     * Bounded because the failure this recovers from can also be permanent. An event
     * that fails to persist — a broken chain, a full disk — leaves appliedSeq parked,
     * so the next broadcast looks exactly like a gap; without a cap that is an
     * unbroken reconnect loop against a host that is doing nothing wrong. The window
     * exists so that an occasional dropped packet over a long session does not
     * eventually exhaust a counter that never resets.
     */
    private static final int MAX_RESYNC_ATTEMPTS = 3;
    private static final long RESYNC_WINDOW_MS = 60_000;
    private static int resyncAttempts = 0;
    private static long lastResyncAt = 0;

    /**
     * Recovers a client that missed a broadcast, by reconnecting to the same host.
     *
     * There is no "send me events from N" message in the protocol — CatchUp is the
     * opposite direction, a client offering events to a host that is behind. What does
     * backfill is the handshake itself: Hello carries our lastSeq and the host replies
     * with everything after it. So the recovery for a gap is the ordinary join path,
     * which is already chunked for oversized histories and already marks replayed
     * lines non-live. Reusing it costs no new message type and no protocol bump.
     */
    private static void resync() {
        long now = System.currentTimeMillis();
        if (now - lastResyncAt > RESYNC_WINDOW_MS) resyncAttempts = 0;
        lastResyncAt = now;

        if (++resyncAttempts > MAX_RESYNC_ATTEMPTS) {
            System.err.println("[economiesmod] giving up after " + MAX_RESYNC_ATTEMPTS
                    + " resync attempts");
            disconnect();
            onRejected.accept("lost events and could not catch up — disconnected");
            return;
        }

        long gap = client.gapAt();
        System.out.println("[economiesmod] missed events before " + gap
                + " — reconnecting to " + connectedHost + ":" + connectedPort
                + " (attempt " + resyncAttempts + ")");

        // Straight to the private overload: the public connect() would stop hosting and
        // re-check the damaged-log guard, neither of which applies to a peer we are
        // already connected to. persist=true because this is only ever reached in
        // CONNECTED mode, where our own log is the replica being repaired.
        connect(connectedHost, connectedPort, connectedUserId, connectedAs,
                Mode.CONNECTED, true);

        if (isConnected()) {
            System.out.println("[economiesmod] resynced to seq " + client.lastSeq());
        }
    }

    /**
     * What we last told a host about this world, so a change can be noticed.
     *
     * Only the parts a rule can turn on: the world's age is always changing and saying
     * so every tick would be noise.
     */
    private static boolean lastToldCheats;
    private static String lastToldGameMode;

    /**
     * Re-describes this world to the host when it stops matching what was said.
     *
     * The handshake happens once, and Open to LAN with cheats enabled happens whenever
     * somebody feels like it — including immediately after connecting from a world that
     * was clean at the time. Without this the host would be holding a description that
     * stopped being true, which is a more comfortable hole than the one it was built to
     * close.
     *
     * Cheap enough to run every frame: it reads two fields off the running server and
     * only sends when one of them differs.
     */
    public static void reattestIfChanged() {
        if (client == null || !client.isConnected()) return;

        // The cheap read first. This runs every client tick, and building a full
        // attestation hashes the world seed — not something to do sixty times a second
        // to answer a question that is two field reads.
        MinecraftServer server = MinecraftClient.getInstance().getServer();
        if (server == null) return;

        // The sticky note counts too, so a world that has been reloaded to clear the
        // live flag still differs from what a clean one would report.
        boolean cheats = WorldFacts.cheatsAvailable(server)
                || WorldFacts.cheatsEverSeen(server);
        String gameMode = WorldFacts.gameModeOf(server);
        if (cheats == lastToldCheats && gameMode.equals(lastToldGameMode)) return;

        WorldAttestation now = WorldFacts.of(server);
        if (now == null) return;

        lastToldCheats = cheats;
        lastToldGameMode = gameMode;
        client.reattest(now);
        System.out.println("[economiesmod] world changed — telling the host ("
                + gameMode + (cheats ? ", commands enabled" : "") + ")");
    }

    public static void pollConnection() {
        if (mode == Mode.LOCAL) return;
        if (client == null) return;

        if (client.isConnected()) {
            // A gapped client is still connected — it is receiving broadcasts and
            // discarding every one of them — so this has to be checked before the
            // healthy-connection early return, not after it.
            if (client.needsResync()) {
                if (mode == Mode.CONNECTED) {
                    resync();
                } else {
                    // HOSTING's client is a loopback to our own HostServer, which owns
                    // the log file. Reconnecting would open a second EventLog on it,
                    // which is the duplicate-sequence corruption startHosting exists to
                    // avoid. A gap against ourselves is a local defect, not a network
                    // one, so it is reported rather than papered over.
                    System.err.println("[economiesmod] sequence gap against our own host"
                            + " at " + client.gapAt() + " — this is a bug, not a drop");
                }
            }
            return;
        }

        if (mode == Mode.HOSTING) {
            // A host that can't reach its own server can't sequence anything.
            System.err.println("[economiesmod] lost self-connection while hosting");
            stopHosting();
            onRejected.accept("hosting stopped — lost connection to own server");
        } else {
            disconnect();
            onRejected.accept("host disconnected — market is closed");
        }
    }

    // ─────────── submitting events ───────────

    /**
     * Submits an event.
     *
     * In LOCAL mode this is synchronous — the returned Result is meaningful.
     * In CONNECTED mode it returns a "pending" result; the real outcome arrives
     * later via the state-changed callback or onRejected.
     */
    public static Submission submit(Event event) {

        //the local branch only for testing though
        if (mode != Mode.LOCAL) {
            if (client == null || !client.isConnected()) {
                return Submission.failed("not connected");
            }
            client.propose(event);
            return Submission.pending();
        }

        // LOCAL — recover the log if something left us without one.
        if (localLog == null) {
            if (currentWorldDir != null) {
                loadLocal(currentWorldDir);
            }
            if (localLog == null) return Submission.failed("no log open");
        }

        try {
            // Stamped before validation, not after it. checkGenesis refuses any event
            // whose marketId is not this market's, so stamping afterwards meant every
            // non-genesis event submitted locally was validated with a null id and
            // refused as belonging to a different market. Genesis carries its own id.
            // See MarketClient.propose for the mirror of this.
            if (!(event instanceof Event.MarketCreated)) {
                event.marketId = get().marketId();
            }

            // Validate before logging — a rejected event must not enter history.
            SequencedEvent probe = new SequencedEvent();
            probe.seq = localLog.lastSeq() + 1;
            probe.event = event;
            EventApplier.Result check = EventApplier.validate(get(), probe);
            if (!check.accepted) {
                return Submission.failed(check.reason);
            }

            // Sign local appends too. LOCAL mode is read-only for trades, but the
            // genesis event is written through here, and an unsigned line would make
            // the whole log unverifiable to anyone who later imports it.
            if (keys == null) return Submission.failed("no identity loaded");
            String signature;
            try {
                signature = keys.sign(EventCanonical.canonicalPayload(event));
            } catch (GeneralSecurityException e) {
                return Submission.failed("could not sign event: " + e.getMessage());
            }

            SequencedEvent se = localLog.append(event, signature);
            EventApplier.Result r = EventApplier.apply(get(), se);
            if (r.accepted) {
                // Always live here — this path only ever applies an event the player
                // has just authored, never a replayed one.
                APPLIED.accept(new AppliedEvent(se, r, true));
            }
            return r.accepted ? Submission.accepted(r) : Submission.failed(r.reason);
        } catch (IOException e) {
            return Submission.failed("log write failed: " + e.getMessage());
        }
    }

    /** What the caller learns immediately. In CONNECTED mode that's usually just "pending". */
    public static class Submission {
        public final boolean pending;
        public final boolean accepted;
        public final String reason;
        public final EventApplier.Result result;

        private Submission(boolean pending, boolean accepted, String reason,
                           EventApplier.Result result) {
            this.pending = pending;
            this.accepted = accepted;
            this.reason = reason;
            this.result = result;
        }

        static Submission pending() {
            return new Submission(true, false, null, null);
        }

        static Submission accepted(EventApplier.Result r) {
            return new Submission(false, true, null, r);
        }

        static Submission failed(String reason) {
            return new Submission(false, false, reason, null);
        }
    }


    /**
     * True if this world's log holds a market — so Host has something to serve.
     *
     * Reads the replayed state rather than the log file: this is polled every frame
     * by the UI, and it must not touch a file a HostServer may own.
     */
    public static boolean hasMarket() {
        MarketState s = get();
        return s != null && s.marketId() != null;
    }

    /** The name of the market in this world's log, or null if there isn't one. */
    public static String marketName() {
        MarketState s = get();
        return s != null ? s.marketName() : null;
    }

    /**
     * Creates a brand-new market in this world's log. Deliberately separate from
     * startHosting — see MarketBootstrap for why.
     */
    public static boolean createMarket(Path worldDir, UUID userId, String marketName) {
        currentWorldDir = worldDir;

        if (keys == null) {
            onRejected.accept("no identity loaded");
            return false;
        }

        // A damaged log can report lastSeq 0 while the file is full of lines we simply
        // couldn't read. Creating into that would write genesis after the garbage.
        if (chainBrokenAt != -1) {
            onRejected.accept("this world's log " + damageReason + " — Reset log first");
            return false;
        }

        try {
            disconnectIfConnected();
            EventLog log = localLog != null ? localLog : new EventLog(logPathFor(worldDir));

            if (log.lastSeq() != 0 || log.isUnreadable()) {
                onRejected.accept("this world already has a market — reset the log first");
                return false;
            }

            MarketBootstrap.createMarket(log, userId, marketName, keys);
            localLog = log;
            localState = EventApplier.replay(log);
            mode = Mode.LOCAL;
            return true;
        } catch (IOException e) {
            onRejected.accept("could not create market: " + e.getMessage());
            System.err.println("[economiesmod] create market failed: " + e);
            return false;
        }
    }

    public static void startHosting(Path worldDir, int port, UUID userId, String playerName) {
        currentWorldDir = worldDir;
        myHostPort = port;
        disconnectIfConnected();
        localLog = null;   // the HostServer's own EventLog owns the file while hosting
        localState = null;

        try {
            hostServer = new HostServer(port, logPathFor(worldDir), playerName,
                    userId.toString(), keys, peerCache);
            hostThread = new Thread(() -> {
                try {
                    hostServer.start();
                } catch (IOException e) {
                    System.err.println("[economiesmod] host stopped: " + e);
                }
            }, "market-host");
            hostThread.setDaemon(true);
            hostThread.start();

            IOException bindErr = hostServer.awaitBound(3000);
            if (bindErr != null) {
                System.err.println("[economiesmod] could not bind port " + port + ": " + bindErr);
                hostServer = null;
                loadLocal(worldDir);
                // Names the fix, because the overwhelmingly common cause is two clients
                // on one machine both defaulting to 25555 — and "already in use" alone
                // doesn't tell you the Port field is where you resolve it.
                onRejected.accept("port " + port + " is already in use — set a different"
                        + " one in the Port field (another host may be running here)");
                return;
            }

            connect("localhost", port, userId, playerName, Mode.HOSTING, false);

            if (client == null || !client.isConnected()) {
                System.err.println("[economiesmod] host started but self-connect failed");
                hostServer.stop();
                hostServer = null;
                loadLocal(worldDir);
                onRejected.accept("host started but could not connect to itself");
                return;
            }

            System.out.println("[economiesmod] hosting on port " + port);
        } catch (Exception e) {
            if (hostServer != null) {
                hostServer.stop();
                hostServer = null;
            }
            loadLocal(worldDir);
            onRejected.accept("failed to start host: " + e.getMessage());
            System.err.println("[economiesmod] host start failed: " + e);
        }
    }

    public static void stopHosting() {
        disconnectIfConnected();
        if (hostServer != null) {
            hostServer.stop();
            hostServer = null;
        }
        if (currentWorldDir != null) {
            loadLocal(currentWorldDir);   // reopens the local log and sets mode
        } else {
            mode = Mode.LOCAL;
        }
    }

    /** Full teardown — the world is closing. Unlike stopHosting, doesn't reopen a local log. */
    public static void shutdown() {
        if (hostServer != null) {
            hostServer.stop();
            hostServer = null;
        }
        disconnectIfConnected();
        localLog = null;
        localState = null;
        currentWorldDir = null;
        mode = Mode.LOCAL;
    }

    /**
     * Which market in this world is in use. Still the single place a log path is decided.
     *
     * Every file a market owns is a sibling of its log, so pinning a different log here
     * moves the high-water mark, pending ops and known keys with it. That is the whole
     * of switching: nothing else in this class knows there is more than one.
     */
    private static String activeSlot = MarketSlots.DEFAULT;

    public static String activeSlot() { return activeSlot; }

    public static List<String> availableSlots() {
        return MarketSlots.list(currentWorldDir);
    }

    /** What the market in a slot calls itself, or null when it holds none yet. */
    public static String slotMarketName(String slot) {
        return MarketSlots.marketNameIn(currentWorldDir, slot);
    }

    /**
     * Makes room for another market in this world and switches to it.
     *
     * The new slot is empty, which the Market screen already reads as MS_NO_MARKET —
     * so the player lands on exactly the Create, Import and Connect choices that a
     * market-to-be needs, with no new flow to learn.
     */
    /**
     * Removes the market currently in use and falls back to the default slot.
     *
     * Only ever the active one, so what is about to be destroyed is what the screen is
     * describing — deleting a market from a list, while looking at a different one's
     * balances, is how the wrong thing gets deleted.
     */
    public static boolean deleteActiveMarketSlot() {
        if (currentWorldDir == null) {
            onRejected.accept("no world open");
            return false;
        }
        String doomed = activeSlot;
        if (MarketSlots.DEFAULT.equalsIgnoreCase(doomed)) {
            onRejected.accept("the first market in a world cannot be removed —"
                    + " use Discard to empty it instead");
            return false;
        }

        // Nothing may be holding the files open when they go.
        if (hostServer != null) stopHosting();
        disconnectIfConnected();
        localLog = null;
        localState = null;

        try {
            MarketSlots.delete(currentWorldDir, doomed);
        } catch (IOException e) {
            onRejected.accept("could not remove that market: " + e.getMessage());
            loadLocal(currentWorldDir);
            return false;
        }

        activeSlot = MarketSlots.DEFAULT;
        try {
            MarketSlots.setActive(currentWorldDir, activeSlot);
        } catch (IOException e) {
            System.err.println("[economiesmod] could not remember the active market: " + e);
        }

        divergence = null;
        checkedHeads.clear();
        pendingReplace = new ArrayList<>();

        loadLocal(currentWorldDir);
        System.out.println("[economiesmod] removed market slot '" + doomed + "'");
        return true;
    }

    public static boolean addMarketSlot() {
        if (currentWorldDir == null) {
            onRejected.accept("no world open");
            return false;
        }
        try {
            return switchTo(MarketSlots.createNext(currentWorldDir));
        } catch (IOException e) {
            onRejected.accept("could not add a market: " + e.getMessage());
            return false;
        }
    }

    private static Path logPathFor(Path worldDir) {
        Path p = MarketSlots.logPath(worldDir, activeSlot);
        // A name that cannot be a path should have been refused long before this, but
        // falling back to the default beats handing a null to a file operation.
        return p != null ? p : MarketSlots.logPath(worldDir, MarketSlots.DEFAULT);
    }

    /**
     * Puts this world on a different market.
     *
     * Disconnects and stops hosting first, for the same reason resetLog does: a running
     * HostServer owns the log file it was started on, and leaving it running while the
     * holder pins a different one leaves two EventLog instances writing to files neither
     * agrees about.
     *
     * An empty slot is a market that does not exist yet, not an error — the Market
     * screen reads that as MS_NO_MARKET and offers Create, Import and Connect, which is
     * exactly the right set of choices for one.
     */
    public static boolean switchTo(String slot) {
        if (currentWorldDir == null) return false;
        if (!MarketSlots.isValidName(slot)) {
            onRejected.accept("'" + slot + "' is not a usable market name");
            return false;
        }
        if (slot.equalsIgnoreCase(activeSlot)) return true;

        if (hostServer != null) stopHosting();
        disconnectIfConnected();

        activeSlot = slot.trim();
        try {
            MarketSlots.setActive(currentWorldDir, activeSlot);
        } catch (IOException e) {
            // The switch still happens; it just will not be remembered next session.
            System.err.println("[economiesmod] could not remember the active market: " + e);
        }

        // Judgements about the market we just left, not this one.
        divergence = null;
        checkedHeads.clear();
        pendingReplace = new ArrayList<>();

        loadLocal(currentWorldDir);
        System.out.println("[economiesmod] now using market slot '" + activeSlot + "'");
        return true;
    }

    /** Discards the local history entirely. Only for resolving a fork — destructive. */
    /** Discards the local history entirely. Only for resolving a fork — destructive. */
    public static void resetLog() {
        // Stop hosting first — otherwise the running HostServer keeps its in-memory
        // lastSeq and would append to a recreated file mid-chain, and its socket
        // stays bound so nothing else can host.
        if (hostServer != null) {
            hostServer.stop();
            hostServer = null;
        }
        disconnectIfConnected();

        if (currentWorldDir == null) return;

        // Before anything is deleted, and before divergence is cleared below — both are
        // needed to work out which orders this reset actually costs.
        List<OldOrder> lost = ordersLostToReset();

        try {
            Path log = logPathFor(currentWorldDir);
            Files.deleteIfExists(log);
            // known-keys.json only has meaning relative to the market that's just been
            // discarded. Leaving it behind meant a stale entry could refuse the very
            // player who owns the world, including their own self-connection when
            // hosting — with no way to fix it from inside the game.
            Files.deleteIfExists(log.resolveSibling("known-keys.json"));
            // The watermark describes the market being discarded, so it goes with it —
            // otherwise a fresh market would look permanently behind the old one.
            if (highWater != null) highWater.clear();
            // Both are judgements about a history that no longer exists.
            divergence = null;
            checkedHeads.clear();
            loadLocal(currentWorldDir);
            System.out.println("[economiesmod] local history discarded");

            // Offered after the reset rather than before, so the list belongs to the
            // market being rejoined rather than the one just discarded.
            if (!lost.isEmpty()) {
                pendingReplace = lost;
                System.out.println("[economiesmod] " + lost.size()
                        + " orders held for re-placing after the reset");
            }
        } catch (IOException e) {
            System.err.println("[economiesmod] reset failed: " + e);
        }
    }

    /**
     * Orders a reset would destroy without offering them back.
     *
     * Only meaningful after a fork. Everything up to the divergence point is history
     * this market shares with the host, so it comes back on reconnecting and needs no
     * help; only what was placed on our own branch afterwards is genuinely lost.
     * Migration snapshots every order instead, and that difference is not an
     * inconsistency — migration abandons the whole market, so every order goes with it.
     *
     * A reset with no fork returns nothing. There is no host holding a shared history
     * to rejoin, so an offer to re-place would be an offer to re-place them into
     * nothing.
     */
    private static List<OldOrder> ordersLostToReset() {
        List<OldOrder> out = new ArrayList<>();

        Divergence split = divergence;
        if (split == null || currentWorldDir == null) return out;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return out;
        UUID me = MinecraftIds.userIdOf(mc.player);

        try {
            // The arithmetic lives in core so it can be tested without Minecraft; all
            // that belongs here is knowing whose keyboard this is.
            EventLog log = new EventLog(logPathFor(currentWorldDir));
            for (Order o : BranchDiff.ordersOnlyAfter(log, split.seq, me)) {
                out.add(new OldOrder(o.itemID(), o.value(), o.volume(), o.isBid()));
            }
        } catch (Exception e) {
            // The reset itself must go ahead regardless. Losing the convenience of a
            // checklist is not a reason to leave somebody stuck on a forked branch.
            System.err.println("[economiesmod] could not work out which orders the reset"
                    + " would cost: " + e);
            return new ArrayList<>();
        }

        return out;
    }


    /** Summarises what the local player would lose if the log were discarded. */
    public static String describeLoss(UUID userId) {
        return NetPosition.of(get(), userId).describe();
    }

    public static PeerCache peers() {
        return peerCache;
    }

    // ─────────── migration ───────────

    /** One of your orders from an abandoned market, kept so you can re-place it. */
    public static class OldOrder {
        public final String itemId;
        public final long price;
        public final long volume;
        public final boolean isBid;

        OldOrder(String itemId, long price, long volume, boolean isBid) {
            this.itemId = itemId;
            this.price = price;
            this.volume = volume;
            this.isBid = isBid;
        }
    }

    // Captured before the abandoned log is discarded. In memory only — a convenience,
    // not a record: the balances themselves are carried by the MigrateBalance event.
    private static List<OldOrder> pendingReplace = new ArrayList<>();

    public static List<OldOrder> pendingReplace() { return pendingReplace; }
    public static void clearPendingReplace() { pendingReplace = new ArrayList<>(); }

    /**
     * Hands this world's market to another host, which verifies it and credits what we
     * hold there. Does not touch the local log — the caller resets and connects after.
     */
    public static boolean migrateTo(String host, int port, UUID userId) {
        if (currentWorldDir == null) {
            onRejected.accept("no world open");
            return false;
        }
        MarketState mine = get();
        if (mine == null || mine.marketId() == null) {
            onRejected.accept("you hold no market to migrate");
            return false;
        }

        try {
            disconnectIfConnected();
            if (hostServer != null) {
                hostServer.stop();
                hostServer = null;
                loadLocal(currentWorldDir);
                mine = get();
            }

            // Snapshot the orders first — once the log is reset they're unrecoverable,
            // and re-placing them is the whole reason this is less painful than a reset.
            List<OldOrder> orders = new ArrayList<>();
            for (String itemId : mine.activeItems()) {
                for (Order o : mine.bookFor(itemId).restingAsks()) {
                    if (o.userID().equals(userId)) {
                        orders.add(new OldOrder(itemId, o.value(), o.volume(), false));
                    }
                }
                for (Order o : mine.bookFor(itemId).restingBids()) {
                    if (o.userID().equals(userId)) {
                        orders.add(new OldOrder(itemId, o.value(), o.volume(), true));
                    }
                }
            }

            List<String> lines = new EventLog(logPathFor(currentWorldDir)).rawLinesFrom(1);
            Message.MigrateResult result =
                    MarketClient.requestMigration(host, port, userId, lines);

            if (!result.accepted) {
                onRejected.accept("migration refused: " + result.reason);
                // A refusal here is a real outcome — the double-mint guards live behind
                // it — so it belongs in the log next to the success and failure cases,
                // not only on a status line that scrolls away.
                System.err.println("[economiesmod] migration of '" + mine.marketName()
                        + "' refused by " + host + ":" + port + ": " + result.reason);
                return false;
            }

            pendingReplace = orders;
            System.out.println("[economiesmod] migrated " + result.summary
                    + "; " + orders.size() + " orders held for re-placing");
            return true;

        } catch (IOException e) {
            onRejected.accept("migration failed: " + e.getMessage());
            System.err.println("[economiesmod] migration failed: " + e);
            return false;
        }
    }

    // ─────────── export / import ───────────

    /**
     * Anchored to the game directory, not the process working directory.
     *
     * A relative path resolves against CWD, which is only the game folder by accident
     * — several launchers start the JVM elsewhere. The on-screen instructions name
     * these folders, so they have to be where the player will actually look.
     */
    private static Path shareDir(String name) {
        return FabricLoader.getInstance().getGameDir().resolve(name);
    }

    private static Path exportDir() {
        return shareDir("economiesmod-exports");
    }

    private static Path importDir() {
        return shareDir("economiesmod-imports");
    }

    /**
     * Creates both share folders up front.
     *
     * Import needs a file placed in a folder before it runs, so creating that folder
     * lazily on first Import means the first attempt can only ever fail — you press it
     * once to find out where to put the file.
     */
    public static void ensureShareFolders() {
        try {
            Files.createDirectories(exportDir());
            Files.createDirectories(importDir());
        } catch (IOException e) {
            System.err.println("[economiesmod] could not create share folders: " + e);
        }
    }

    /** Writes this world's market to a shareable file. Returns the path written. */
    public static Path exportMarket() throws IOException {
        if (currentWorldDir == null) throw new IOException("no world open");

        MarketState s = get();
        String name = s != null && s.marketName() != null ? s.marketName() : "market";
        String safe = name.replaceAll("[^a-zA-Z0-9-_]", "_");
        Path dest = exportDir().resolve(safe + "-" + System.currentTimeMillis() + ".jsonl");

        MarketArchive.export(logPathFor(currentWorldDir), dest);
        return dest.toAbsolutePath();
    }

    /**
     * Adopts a market from a file in the import folder.
     *
     * Requires exactly one archive present — picking one on the player's behalf when
     * several are there would be guessing about which market they meant to join.
     */
    public static MarketArchive.Summary importMarket() throws IOException {
        if (currentWorldDir == null) throw new IOException("no world open");

        Path dir = importDir();
        Files.createDirectories(dir);

        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jsonl")) {
            for (Path p : stream) found.add(p);
        }

        if (found.isEmpty()) {
            throw new IOException("put a .jsonl market file in "
                    + dir.toAbsolutePath() + " first");
        }
        if (found.size() > 1) {
            throw new IOException("found " + found.size() + " market files in "
                    + dir.toAbsolutePath() + " — leave only the one you want");
        }

        disconnectIfConnected();
        if (hostServer != null) {
            hostServer.stop();
            hostServer = null;
        }

        MarketArchive.Summary summary =
                MarketArchive.importInto(found.get(0), logPathFor(currentWorldDir));
        loadLocal(currentWorldDir);
        return summary;
    }



}