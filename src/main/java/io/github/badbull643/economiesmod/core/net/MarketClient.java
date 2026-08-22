package io.github.badbull643.economiesmod.core.net;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import io.github.badbull643.economiesmod.core.*;

import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;


/**
 * Connects to a HostServer, keeps a local replica of market state by applying
 * every broadcast event in sequence order.
 *
 * The local state is read-only from the UI's perspective — it only ever changes
 * as a result of an Accepted broadcast from the host.
 */
public class MarketClient {

    private final Gson gson = new Gson();
    private final MarketState state;
    private final EventLog log;
    private final UUID userId;

    private MessageChannel channel;
    private Thread reader;
    private volatile boolean connected = false;
    private volatile long lastSeq = 0;
    private volatile String lastHash = "0";
    private final PlayerKeys keys;
    private final boolean persist;

    /** Called when a proposal is rejected, so the UI can report it. */
    private Consumer<String> onRejected = reason -> {};
    /** Called after any event is applied, so the UI can refresh. */
    private Runnable onStateChanged = () -> {};


    private final String displayName;

    private final PeerCache peerCache;

    private final int myHostPort;

    public MarketClient(UUID userId, String displayName, PlayerKeys keys,
                        EventLog log, boolean persist, PeerCache peerCache,
                        int myHostPort) throws IOException {
        this.userId = userId;
        this.displayName = displayName;
        this.keys = keys;
        this.log = log;
        this.persist = persist;
        this.peerCache = peerCache;
        this.myHostPort = myHostPort;
        this.appliedSeq = log.lastSeq();
        this.lastHash = log.lastHash();
        this.state = EventApplier.replay(log);
    }
    /**
     * A host declining the handshake, carrying the reason in a form the UI can act on.
     * A refusal the user can't do anything about is barely better than a crash.
     */
    public static class Refused extends IOException {
        public final String code;   // one of HostServer.Refusal, or null
        /** Where the refusing host's own log ends. Sent with AHEAD, so the client can
         *  tell "I extend you" from "we diverged", and with FORK, so a reset knows the
         *  point to compute its re-place checklist against. Zero/null otherwise. */
        public final long hostSeq;
        public final String hostHash;
        /** The refusing host's name, when it sent one. */
        public final String hostName;

        public Refused(String code, String reason, long hostSeq, String hostHash) {
            this(code, reason, hostSeq, hostHash, null);
        }

        public Refused(String code, String reason, long hostSeq, String hostHash,
                       String hostName) {
            super(reason == null ? "connection refused" : reason);
            this.code = code;
            this.hostSeq = hostSeq;
            this.hostHash = hostHash;
            this.hostName = hostName;
        }
    }

    public MarketState state() { return state; }
    public boolean isConnected() { return connected; }

    /**
     * Whether the host we synced from calls itself a dedicated server.
     *
     * Self-reported and only knowable once connected, which is why nothing offline
     * claims the opposite — "not dedicated" and "not yet known" are different answers
     * and only one of them is safe to draw.
     */
    private volatile boolean hostDedicated = false;
    public boolean hostIsDedicated() { return hostDedicated; }

    /**
     * What to tell a host about the world we are trading from, or null to say nothing.
     *
     * Set from outside rather than read here: describing a Minecraft world means
     * touching Minecraft, and core does not. The client package fills this in before
     * connecting.
     */
    private volatile WorldAttestation attestation;

    public void setAttestation(WorldAttestation a) { this.attestation = a; }

    /**
     * Tells the host the world has changed since the handshake described it.
     *
     * The handshake is a photograph, and connecting from a clean world before opening
     * it to LAN with cheats enabled would otherwise leave the host acting on a
     * description that stopped being true a minute later. Sent only when something
     * actually differs — see MarketStateHolder, which does the comparing.
     */
    public void reattest(WorldAttestation a) {
        if (a == null || !connected || channel == null) return;
        this.attestation = a;
        Message.Attest msg = new Message.Attest();
        msg.attestation = a;
        channel.send(msg);
    }
    public EventLog log() { return log; }
    public long lastSeq() { return appliedSeq; }

    public void setOnRejected(Consumer<String> handler) { this.onRejected = handler; }

    /**
     * Called with the clientEventId of a proposal the host turned down.
     *
     * Split from onRejected, which exists to put words on a screen. This one exists
     * because a refused deposit has already taken the items out of somebody's
     * inventory, and until this fired they came back only on the next startup, when the
     * journal was settled against the log — a rejected deposit meant losing your items
     * until you restarted the game.
     */
    private Consumer<String> onProposalRefused = id -> {};

    public void setOnProposalRefused(Consumer<String> handler) {
        this.onProposalRefused = handler;
    }
    public void setOnStateChanged(Runnable handler) { this.onStateChanged = handler; }


    /** Called after each event is applied, with the fills and whether it was live. */
    private Consumer<AppliedEvent> onApplied = a -> {};

    public void setOnApplied(Consumer<AppliedEvent> handler) { this.onApplied = handler; }

    /**
     * True while a whole history is being replayed into local state during connect.
     *
     * Applying a synced event and applying a broadcast one are the same operation, so
     * nothing downstream could tell them apart — and a handler with an effect outside
     * the ledger must, or it repeats every historical event of this player's each time
     * they join. Volatile because connect() and the reader thread both touch it.
     */
    private volatile boolean replaying = false;

    public void connect(String host, int port) throws IOException {
        Socket socket = new Socket(host, port);
        channel = new MessageChannel(socket);

        Message.Hello hello = new Message.Hello();
        hello.userId = userId.toString();
        hello.publicKey = keys.publicKeyString();
        hello.lastSeq = log.lastSeq();
        hello.lastHash = log.lastHash();
        hello.hostPort = myHostPort;
        hello.displayName = displayName;
        hello.protocolVersion = HostServer.PROTOCOL_VERSION;
        UUID myMarket = log.marketId();
        hello.marketId = myMarket != null ? myMarket.toString() : null;
        hello.marketName = log.marketName();
        hello.attestation = attestation;

        System.out.println("[client] hello: lastSeq=" + hello.lastSeq
                + " appliedSeq=" + appliedSeq + " persist=" + persist);

        channel.send(hello);

        Message reply = channel.receive();
        if (reply instanceof Message.Error) {
            Message.Error err = (Message.Error) reply;
            channel.close();
            throw new Refused(err.code, err.reason, err.hostSeq, err.hostHash,
                    err.hostName);
        }
        if (!(reply instanceof Message.Sync)) {
            channel.close();
            throw new IOException("expected Sync, got " + (reply == null ? "nothing" : reply.type));
        }

        Message.Sync sync = (Message.Sync) reply;
        hostDedicated = sync.dedicated;

        // The host already refused a mismatch, but check our own copy too — a client
        // should not adopt events into a log whose identity it did not agree to.
        if (myMarket != null && sync.marketId != null
                && !myMarket.toString().equals(sync.marketId)) {
            channel.close();
            throw new IOException("host serves a different market ("
                    + sync.marketName + ") — cannot join");
        }

        // A long history arrives across several frames. Identity rode on the first one;
        // gather the rest of the lines before applying any, so a transfer that dies
        // partway leaves our log untouched rather than half-advanced.
        List<String> syncLines = new ArrayList<>();
        if (sync.logLines != null) syncLines.addAll(sync.logLines);
        boolean complete = sync.complete;
        int frames = 1;
        while (!complete) {
            Message next = channel.receive();
            if (!(next instanceof Message.Sync)) {
                channel.close();
                throw new IOException("host stopped partway through the sync");
            }
            Message.Sync more = (Message.Sync) next;
            if (more.logLines != null) syncLines.addAll(more.logLines);
            complete = more.complete;
            frames++;
        }
        if (frames > 1) {
            System.out.println("[client] received " + syncLines.size()
                    + " events in " + frames + " chunks");
        }

        if (peerCache != null && sync.hostUserId != null
                && !sync.hostUserId.equals(userId.toString())
                && peerCache.keyChanged(sync.hostUserId, sync.hostPublicKey)) {
            System.err.println("[client] WARNING: " + sync.hostName
                    + "'s identity key has changed since last seen");
            onRejected.accept("Warning: " + sync.hostName + "'s key has changed");
        }

        boolean synced;
        try {
            synced = applySyncLines(syncLines);
        } catch (IllegalStateException e) {
            channel.close();
            throw new IOException("cannot read host's events — mod version mismatch?");
        }
        if (!synced) {
            channel.close();
            throw new IOException("host's history failed verification — refusing to join");
        }

        if (peerCache != null) {
            peerCache.merge(sync.knownPeers);
            // Record the host: the address we dialled, plus the identity they report.
            if (sync.hostUserId != null && !sync.hostUserId.equals(userId.toString())) {
                peerCache.record(sync.hostUserId, sync.hostName, host,
                        sync.hostPort, sync.hostPublicKey);
            }
        }

        connected = true;

        reader = new Thread(this::readerLoop, "market-client-reader");
        reader.setDaemon(true);
        reader.start();

        // Put our key in the log if it isn't there yet. Nothing we author is valid
        // until it is, and it's also what triggers the welcome grant — so this has to
        // happen before the player can do anything, not on their first trade attempt.
        if (!state.isRegistered(userId)) {
            Event.KeyRegistered kr = new Event.KeyRegistered();
            kr.userId = userId;
            kr.publicKey = keys.publicKeyString();
            kr.timestamp = System.currentTimeMillis();
            propose(kr);
            System.out.println("[client] registering identity in this market");
        }
    }

    /**
     * Offers a market we're abandoning to a host, so it can credit what we hold there.
     *
     * A standalone exchange on its own socket — we've already been refused a handshake,
     * so there is no session to do this inside. On success the caller should reset and
     * connect normally; nothing about the local log is touched here.
     */
    public static Message.MigrateResult requestMigration(String host, int port,
                                                         UUID userId, List<String> logLines,
                                                         WorldAttestation attestation)
            throws IOException {
        Socket socket = new Socket(host, port);
        socket.setSoTimeout(30_000);   // verifying a whole branch is not instant
        try (MessageChannel ch = new MessageChannel(socket)) {
            List<List<String>> chunks = MessageChannel.chunkByByteBudget(logLines);

            if (chunks.size() > 1) {
                System.out.println("[client] sending " + logLines.size()
                        + " events to migrate in " + chunks.size() + " chunks");
            }

            for (int i = 0; i < chunks.size(); i++) {
                Message.MigrateRequest req = new Message.MigrateRequest();
                req.userId = userId.toString();
                req.logLines = chunks.get(i);
                req.complete = (i == chunks.size() - 1);
                // First chunk only, like Sync: the host reads it off the message that
                // opened the exchange and the rest are just more history.
                if (i == 0) req.attestation = attestation;
                ch.send(req);
            }

            Message reply = ch.receive();
            if (reply instanceof Message.MigrateResult) {
                return (Message.MigrateResult) reply;
            }
            if (reply instanceof Message.Error) {
                throw new IOException(((Message.Error) reply).reason);
            }
            throw new IOException("host did not answer the migration request");
        }
    }

    /** Offers a stale host the events it's missing from its own market. Chunked for the
     *  same reason a migration is: how far a host has fallen behind is unbounded. */
    public static Message.CatchUpResult offerCatchUp(String host, int port,
                                                     UUID userId, List<String> logLines)
            throws IOException {
        Socket socket = new Socket(host, port);
        socket.setSoTimeout(30_000);
        try (MessageChannel ch = new MessageChannel(socket)) {
            List<List<String>> chunks = MessageChannel.chunkByByteBudget(logLines);

            if (chunks.size() > 1) {
                System.out.println("[client] offering " + logLines.size()
                        + " events to catch up in " + chunks.size() + " chunks");
            }

            for (int i = 0; i < chunks.size(); i++) {
                Message.CatchUp req = new Message.CatchUp();
                req.userId = userId.toString();
                req.logLines = chunks.get(i);
                req.complete = (i == chunks.size() - 1);
                ch.send(req);
            }

            Message reply = ch.receive();
            if (reply instanceof Message.CatchUpResult) {
                return (Message.CatchUpResult) reply;
            }
            if (reply instanceof Message.Error) {
                throw new IOException(((Message.Error) reply).reason);
            }
            throw new IOException("host did not answer the catch-up offer");
        }
    }

    /** Sends a proposal. Returns immediately — the result arrives via broadcast or onRejected. */
    public String propose(Event event) {
        if (!connected) {
            onRejected.accept("not connected");
            return null;
        }
        // Honour an id the caller already chose. A deposit has to write its journal
        // entry before it touches the inventory, and that entry is keyed by this id —
        // so the id must exist before the event is proposed, not be minted here.
        String clientEventId = event.clientEventId != null
                ? event.clientEventId : UUID.randomUUID().toString();
        event.clientEventId = clientEventId;   // must be set BEFORE signing
        // Stamped here rather than at each call site: a caller that forgets would
        // produce an event that is valid nowhere, and one that lied would be caught
        // by the host anyway.
        event.marketId = state.marketId();

        Message.Propose p = new Message.Propose();
        p.clientEventId = clientEventId;
        p.eventType = event.getClass().getSimpleName();
        p.eventJson = gson.toJson(event);
        try {
            p.signature = keys.sign(EventCanonical.canonicalPayload(event));
        } catch (GeneralSecurityException e) {
            onRejected.accept("failed to sign: " + e.getMessage());
            return null;
        }
        channel.send(p);
        return clientEventId;
    }

    private void readerLoop() {
        try {
            Message msg;
            while (connected && (msg = channel.receive()) != null) {
                if (msg instanceof Message.Accepted) {
                    boolean ok = applyLine(((Message.Accepted) msg).logLine);
                    onStateChanged.run();
                    if (!ok) break;
                } else if (msg instanceof Message.Rejected) {
                    Message.Rejected r = (Message.Rejected) msg;
                    onRejected.accept(r.reason);
                    // Separately from the message, because a refusal is not only
                    // something to say: a deposit takes the items out of the inventory
                    // before proposing, so somebody has to give them back.
                    if (r.clientEventId != null) {
                        onProposalRefused.accept(r.clientEventId);
                    }
                } else if (msg instanceof Message.Error) {
                    onRejected.accept("host error: " + ((Message.Error) msg).reason);
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("[client] reader stopped: " + e.getMessage());
        } finally {
            // Close the socket, don't just mark ourselves disconnected. The loop exits
            // on a refused event as well as a dead link, and in that case the socket is
            // still healthy — leaving it open kept a connection to a host we had just
            // decided not to trust, until the UI's next poll happened to tidy it up.
            connected = false;
            if (channel != null) {
                try { channel.close(); } catch (IOException ignored) {}
            }
            onStateChanged.run();
        }
    }

    /** Returns false if a line failed verification, meaning the connection is finished. */
    private boolean applySyncLines(List<String> lines) {
        replaying = true;
        try {
            for (String line : lines) {
                if (!applyLine(line)) return false;
            }
            return true;
        } finally {
            replaying = false;
        }
    }

    private volatile long appliedSeq = 0;

    /**
     * The sequence number that arrived when we were expecting an earlier one, or -1.
     *
     * Set on the reader thread, read from the game thread by whoever owns this
     * connection. Never cleared: a client that has missed events cannot catch up in
     * place, so recovery replaces the whole client rather than resetting a flag.
     */
    private volatile long gapAt = -1;

    /** True once a broadcast has arrived out of order — see gapAt. */
    public boolean needsResync() { return gapAt != -1; }

    /** Which sequence number exposed the gap. Meaningless unless needsResync(). */
    public long gapAt() { return gapAt; }

    /**
     * Applies one broadcast or sync line. Returns false if the host sent something
     * unverifiable, in which case the caller must tear the connection down.
     *
     * Deliberately does not disconnect itself: this runs both on the reader thread and,
     * during sync, on the connecting thread — where closing the channel mid-handshake
     * would leave connect() to carry on and start a reader over a dead socket.
     */
    private boolean applyLine(String line) {
        SequencedEvent se;
        try {
            se = EventLog.parseLine(line);
        } catch (IllegalStateException e) {
            System.err.println("[client] cannot parse event: " + e.getMessage()
                    + " — your mod version may be out of date");
            onRejected.accept("Unknown event type — update the mod");
            return false;
        }

        // Already have it. Not a fault and not worth mentioning: a resync asks the host
        // for everything after our lastSeq, so a broadcast that was already in flight
        // when we reconnected lands a second time as a matter of course.
        if (se.seq <= appliedSeq) {
            return true;
        }

        if (se.seq != appliedSeq + 1) {
            // Events are missing. Nothing after this one can be applied either — the
            // chain check in appendRaw would reject it and appliedSeq would never
            // advance past the hole — so tolerating it silently is what turned one
            // dropped broadcast into a client that stayed "connected" and applied
            // nothing for the rest of the session.
            if (replaying) {
                // The hole is in the host's own sync stream, which it built from our
                // lastSeq. Reconnecting would ask the same host the same question and
                // get the same answer, so this is a refusal, not something to retry.
                System.err.println("[client] host's sync skips from " + appliedSeq
                        + " to " + se.seq + " — refusing");
                return false;
            }

            // A live broadcast. Recovery is a reconnect: the handshake sends our
            // lastSeq and the host replies with everything after it, which is the
            // same path that already backfills a client joining late. Performed by
            // the owner of the connection, not here — this runs on the reader thread
            // and during sync on the connecting thread, and tearing the channel down
            // from either would strand the other.
            System.err.println("[client] sequence gap: expected " + (appliedSeq + 1)
                    + " got " + se.seq + " — resync needed");
            gapAt = se.seq;
            return true;
        }

        // Check the signature before this event goes anywhere near our log or state.
        // A host is no more trusted than a file from a stranger: it computes the hash
        // chain itself, so the chain proves only internal consistency, never authorship.
        // Without this, a modified host can invent trades in anyone's name and every
        // replica will persist them and re-serve them when it hosts next.
        String problem = EventVerifier.verify(state, se.event, se.signature);
        if (problem != null) {
            System.err.println("[client] REFUSING event " + se.seq + " from host: " + problem);
            onRejected.accept("host sent an event that failed verification ("
                    + problem + ") — disconnected");
            return false;
        }

        // And the market's own rules, which the signature says nothing about.
        //
        // A signature proves who wrote an event, not that the event was allowed. The
        // money rules — a grant must be this market's published figure, once per
        // identity; a stipend must be the market's and its interval must have elapsed;
        // policy is the creator's alone — all live in validate, because that is where a
        // host asks them before it appends. This ran apply and nothing else, so a
        // modified host could sequence a grant to itself for any sum, correctly signed
        // with its own key, and every connected replica would apply it, persist it, and
        // re-serve it the next time that player hosted. The same hole MarketArchive had
        // on the migration path.
        //
        // Safe to ask, because it is the identical question asked against the identical
        // state: every path by which a host appends validates first, against its state
        // at seq-1, and ours is that state if replay is deterministic — which is the
        // premise the whole project rests on. So a refusal here means the host is lying
        // or we have diverged, and both are reasons to stop rather than to carry on.
        //
        // Before persisting. An event we refuse must not reach the log, or we would
        // re-serve the thing we just declined.
        EventApplier.Result allowed = EventApplier.validate(state, se);
        if (!allowed.accepted) {
            System.err.println("[client] REFUSING event " + se.seq + " from host: breaks"
                    + " this market's rules — " + allowed.reason);
            onRejected.accept("host sent an event this market's own rules refuse ("
                    + allowed.reason + ") — disconnected");
            return false;
        }

        if (persist) {
            try {
                log.appendRaw(line);
            } catch (IOException e) {
                System.err.println("[client] failed to persist event: " + e);
                return true;
            }
        }

        appliedSeq = se.seq;
        lastHash = se.hash;

        EventApplier.Result result = EventApplier.apply(state, se);
        if (result.accepted) {
            onApplied.accept(new AppliedEvent(se, result, !replaying));
        }
        return true;
    }

    public void disconnect() {
        connected = false;
        if (channel != null) {
            try { channel.close(); } catch (IOException ignored) {}
        }
    }

}