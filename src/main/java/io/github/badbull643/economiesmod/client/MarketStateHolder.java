package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.*;
import io.github.badbull643.economiesmod.core.net.MarketClient;

import java.io.IOException;
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

    public enum Mode { LOCAL, CONNECTED }

    private static Mode mode = Mode.LOCAL;

    // LOCAL mode
    private static MarketState localState;
    private static EventLog localLog;

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
        if (mode == Mode.CONNECTED) {
            return client != null ? client.state() : new MarketState();
        }
        if (localState == null) localState = new MarketState();
        return localState;
    }

    // ─────────── LOCAL mode ───────────

    public static void loadLocal(Path worldDir) {
        mode = Mode.LOCAL;
        disconnectIfConnected();

        Path logFile = worldDir.resolve("economiesmod").resolve("market.jsonl");
        try {
            localLog = new EventLog(logFile);
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

    public static void connect(String host, int port, UUID userId) {
        try {
            MarketClient c = new MarketClient(userId);
            c.setOnRejected(onRejected);
            c.setOnApplied(onApplied);
            c.connect(host, port);
            client = c;
            mode = Mode.CONNECTED;
            System.out.println("[economiesmod] connected to " + host + ":" + port
                    + " at seq " + c.lastSeq());
        } catch (IOException e) {
            onRejected.accept("connect failed: " + e.getMessage());
            System.err.println("[economiesmod] connect failed: " + e);
        }
    }

    public static void disconnect() {
        disconnectIfConnected();
        mode = Mode.LOCAL;
    }

    private static void disconnectIfConnected() {
        if (client != null) {
            client.disconnect();
            client = null;
        }
    }

    public static boolean isConnected() {
        return mode == Mode.CONNECTED && client != null && client.isConnected();
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
        if (mode == Mode.CONNECTED) {
            if (client == null || !client.isConnected()) {
                return Submission.failed("not connected");
            }
            client.propose(event);
            return Submission.pending();
        }

        // LOCAL
        if (localLog == null) return Submission.failed("no log open");
        try {
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
}