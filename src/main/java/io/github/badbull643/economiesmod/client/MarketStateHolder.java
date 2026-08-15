package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.*;
import io.github.badbull643.economiesmod.core.net.HostServer;
import io.github.badbull643.economiesmod.core.net.MarketClient;
import io.github.badbull643.economiesmod.core.net.Message;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Owns the active market for the current world, in one of two modes:
 *
 *  LOCAL     — this process owns the log. Events are appended and applied
 *              immediately. Single-player, no network.
 *  CONNECTED — a remote host owns the log. Events are proposed and only applied
 *              when the host broadcasts them back. Asynchronous.
 */
public class MarketStateHolder {

    public enum Mode { LOCAL, CONNECTED, HOSTING }

    private static Mode mode = Mode.LOCAL;

    // LOCAL mode
    private static MarketState localState;
    private static EventLog localLog;

    private static PlayerKeys keys;
    private static Path currentWorldDir;

    private static MarketHighWater highWater;

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

    private static Consumer<SequencedEvent> onApplied = se -> {};

    public static void setOnApplied(Consumer<SequencedEvent> handler) {
        onApplied = handler;
        if (client != null) client.setOnApplied(handler);
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


    // ─────────── LOCAL mode ───────────

    public static void loadLocal(Path worldDir) {
        currentWorldDir = worldDir;
        mode = Mode.LOCAL;
        disconnectIfConnected();

        // Catches Exception, not just IOException: this runs on the server thread during
        // world load, so anything escaping here takes the whole world down before the
        // player can reach the Reset button that would fix it.
        highWater = new MarketHighWater(
                logPathFor(worldDir).resolveSibling("high-water.json"));

        try {
            localLog = new EventLog(logPathFor(worldDir));
            chainBrokenAt = localLog.verifyChain();
            damageReason = localLog.damageReason();
            if (chainBrokenAt != -1) {
                System.err.println("[economiesmod] log unusable: " + damageReason);
            }
            localState = EventApplier.replay(localLog);
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
            c.setOnApplied(onApplied);
            c.connect(host, port);

            client = c;
            localLog = log;
            localState = null;
            mode = targetMode;
            lastRefusal = null;

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
     * Drops back to LOCAL when the link died without an explicit Disconnect.
     *
     * Without this, mode stays CONNECTED after the host goes away: trading is still
     * permitted (and fails later with a vague "not connected"), the order book keeps
     * showing the dead connection's replica, and the local log is never reopened.
     * Cheap enough to call every frame — it only does work on the transition.
     */
    public static void pollConnection() {
        if (mode == Mode.LOCAL) return;
        if (client == null || client.isConnected()) return;

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
            // Genesis carries its own id; everything else is stamped with the market
            // it is being written into. See MarketClient.propose for the mirror.
            if (!(event instanceof Event.MarketCreated)) {
                event.marketId = get().marketId();
            }
            String signature;
            try {
                signature = keys.sign(EventCanonical.canonicalPayload(event));
            } catch (GeneralSecurityException e) {
                return Submission.failed("could not sign event: " + e.getMessage());
            }

            SequencedEvent se = localLog.append(event, signature);
            EventApplier.Result r = EventApplier.apply(get(), se);
            if (r.accepted) {
                onApplied.accept(se);
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
                onRejected.accept("port " + port + " already in use");
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

    private static Path logPathFor(Path worldDir) {
        return worldDir.resolve("economiesmod").resolve("market.jsonl");
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
            loadLocal(currentWorldDir);
            System.out.println("[economiesmod] local history discarded");
        } catch (IOException e) {
            System.err.println("[economiesmod] reset failed: " + e);
        }
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