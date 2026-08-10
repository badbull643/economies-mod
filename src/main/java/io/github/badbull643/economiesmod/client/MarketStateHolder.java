package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.*;
import io.github.badbull643.economiesmod.core.net.HostServer;
import io.github.badbull643.economiesmod.core.net.MarketClient;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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


    private static HostServer hostServer;
    private static Thread hostThread;

    // in MarketStateHolder
    private static int myHostPort = 25555;

    public static void setMyHostPort(int port) { myHostPort = port; }
    public static int myHostPort() { return myHostPort; }


    /** Loads (or generates) this player's signing identity. Call once at mod init. */
    public static void loadKeys(Path keyFile) {
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

        try {
            localLog = new EventLog(logPathFor(worldDir));
            long bad = localLog.verifyChain();
            if (bad != -1) {
                System.err.println("[economiesmod] log chain broken at seq " + bad);
            }
            localState = EventApplier.replay(localLog);
            System.out.println("[economiesmod] local: replayed " + localLog.lastSeq() + " events");
        } catch (IOException e) {
            System.err.println("[economiesmod] local log load failed: " + e);
            localState = new MarketState();
        }
    }

    // ─────────── CONNECTED mode ───────────

    public static void connect(String host, int port, UUID userId, String displayName) {
        connect(host, port, userId, displayName, Mode.CONNECTED, true);
    }

    private static void connect(String host, int port, UUID userId, String displayName,
                                Mode targetMode, boolean persist) {

        if (keys == null) {
            onRejected.accept("no identity loaded");
            return;
        }
        try {
            EventLog log = localLog != null
                    ? localLog
                    : new EventLog(logPathFor(currentWorldDir));

            MarketClient c = new MarketClient(userId, displayName, keys, log, persist,
                    peerCache, myHostPort);
            c.setOnRejected(onRejected);
            c.setOnApplied(onApplied);
            c.connect(host, port);

            client = c;
            localLog = log;
            localState = null;
            mode = targetMode;

            System.out.println("[economiesmod] connected to " + host + ":" + port
                    + " at seq " + c.lastSeq());
        } catch (IOException e) {
            onRejected.accept("connect failed: " + e.getMessage());
            System.err.println("[economiesmod] connect failed: " + e);
        }
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

    // ─────────── submitting events ───────────

    /**
     * Submits an event.
     *
     * In LOCAL mode this is synchronous — the returned Result is meaningful.
     * In CONNECTED mode it returns a "pending" result; the real outcome arrives
     * later via the state-changed callback or onRejected.
     */
    public static Submission submit(Event event) {
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

            SequencedEvent se = localLog.append(event);
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


    public static void startHosting(Path worldDir, int port, UUID userId, String playerName) {
        currentWorldDir = worldDir;
        myHostPort = port;
        disconnectIfConnected();
        localLog = null;   // the HostServer's own EventLog owns the file while hosting
        localState = null;

        try {
            hostServer = new HostServer(port, logPathFor(worldDir), playerName,
                    userId.toString(), peerCache);
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
            Files.deleteIfExists(logPathFor(currentWorldDir));
            loadLocal(currentWorldDir);
            System.out.println("[economiesmod] local history discarded");
        } catch (IOException e) {
            System.err.println("[economiesmod] reset failed: " + e);
        }
    }


    /** Summarises what the local player would lose if the log were discarded. */
    public static String describeLoss(UUID userId) {
        MarketState s = get();
        if (s == null) return "nothing";

        StringBuilder sb = new StringBuilder();
        long credits = s.wallets().getBalance(userId);
        if (credits > 0) sb.append(credits).append(" credits");

        for (String itemId : s.activeItems()) {
            long held = s.itemBalances().getBalance(userId, itemId);
            long resting = 0;
            for (Order o : s.bookFor(itemId).restingAsks()) {
                if (o.userID().equals(userId)) resting += o.volume();
            }
            long total = held + resting;
            if (total > 0) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(total).append(" ").append(itemId);
            }
        }

        return sb.length() > 0 ? sb.toString() : "nothing";
    }

    public static PeerCache peers() {
        return peerCache;
    }



}