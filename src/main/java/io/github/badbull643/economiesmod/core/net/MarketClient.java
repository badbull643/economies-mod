package io.github.badbull643.economiesmod.core.net;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import io.github.badbull643.economiesmod.core.*;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
        /** Only meaningful for AHEAD — where the refusing host's own log ends. */
        public final long hostSeq;
        public final String hostHash;

        public Refused(String code, String reason, long hostSeq, String hostHash) {
            super(reason == null ? "connection refused" : reason);
            this.code = code;
            this.hostSeq = hostSeq;
            this.hostHash = hostHash;
        }
    }

    public MarketState state() { return state; }
    public boolean isConnected() { return connected; }
    public EventLog log() { return log; }
    public long lastSeq() { return appliedSeq; }

    public void setOnRejected(Consumer<String> handler) { this.onRejected = handler; }
    public void setOnStateChanged(Runnable handler) { this.onStateChanged = handler; }


    /** Called after each event is applied, with the event itself. */
    private Consumer<SequencedEvent> onApplied = se -> {};

    public void setOnApplied(Consumer<SequencedEvent> handler) { this.onApplied = handler; }

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

        System.out.println("[client] hello: lastSeq=" + hello.lastSeq
                + " appliedSeq=" + appliedSeq + " persist=" + persist);

        channel.send(hello);

        Message reply = channel.receive();
        if (reply instanceof Message.Error) {
            Message.Error err = (Message.Error) reply;
            channel.close();
            throw new Refused(err.code, err.reason, err.hostSeq, err.hostHash);
        }
        if (!(reply instanceof Message.Sync)) {
            channel.close();
            throw new IOException("expected Sync, got " + (reply == null ? "nothing" : reply.type));
        }

        Message.Sync sync = (Message.Sync) reply;

        // The host already refused a mismatch, but check our own copy too — a client
        // should not adopt events into a log whose identity it did not agree to.
        if (myMarket != null && sync.marketId != null
                && !myMarket.toString().equals(sync.marketId)) {
            channel.close();
            throw new IOException("host serves a different market ("
                    + sync.marketName + ") — cannot join");
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
            synced = applySyncLines(sync.logLines);
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
    // Comfortably under MessageChannel's 1MB-per-line cap, leaving headroom for the
    // JSON escaping each raw log line picks up when it's nested inside this message
    // as a string. A long history is exactly the case migration exists to rescue, so
    // it has to survive being bigger than one frame.
    private static final int MIGRATE_CHUNK_BUDGET_BYTES = 700_000;

    public static Message.MigrateResult requestMigration(String host, int port,
                                                         UUID userId, List<String> logLines)
            throws IOException {
        Socket socket = new Socket(host, port);
        socket.setSoTimeout(30_000);   // verifying a whole branch is not instant
        try (MessageChannel ch = new MessageChannel(socket)) {
            List<List<String>> chunks = chunkByByteBudget(logLines, MIGRATE_CHUNK_BUDGET_BYTES);

            for (int i = 0; i < chunks.size(); i++) {
                Message.MigrateRequest req = new Message.MigrateRequest();
                req.userId = userId.toString();
                req.logLines = chunks.get(i);
                req.complete = (i == chunks.size() - 1);
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

    /** Splits lines into chunks that each stay under a byte budget. Always returns at
     *  least one chunk (possibly empty), so an empty input still sends a complete=true
     *  message rather than nothing. */
    private static List<List<String>> chunkByByteBudget(List<String> lines, int budget) {
        List<List<String>> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        long currentBytes = 0;

        for (String line : lines) {
            int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
            if (!current.isEmpty() && currentBytes + lineBytes > budget) {
                chunks.add(current);
                current = new ArrayList<>();
                currentBytes = 0;
            }
            current.add(line);
            currentBytes += lineBytes;
        }
        chunks.add(current);
        return chunks;
    }

    /** Offers a stale host the events it's missing from its own market. */
    public static Message.CatchUpResult offerCatchUp(String host, int port,
                                                     UUID userId, List<String> logLines)
            throws IOException {
        Socket socket = new Socket(host, port);
        socket.setSoTimeout(30_000);
        try (MessageChannel ch = new MessageChannel(socket)) {
            Message.CatchUp req = new Message.CatchUp();
            req.userId = userId.toString();
            req.logLines = logLines;
            ch.send(req);

            Message reply = ch.receive();
            if (reply instanceof Message.CatchUpResult) {
                return (Message.CatchUpResult) reply;
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
        String clientEventId = UUID.randomUUID().toString();
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
                    onRejected.accept(((Message.Rejected) msg).reason);
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
        for (String line : lines) {
            if (!applyLine(line)) return false;
        }
        return true;
    }

    private volatile long appliedSeq = 0;

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

        if (se.seq != appliedSeq + 1) {
            System.err.println("[client] sequence gap: expected " + (appliedSeq + 1)
                    + " got " + se.seq + " (persist=" + persist + ")");
            return true;   // a gap is not grounds to distrust the host
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
            onApplied.accept(se);
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