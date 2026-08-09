package io.github.badbull643.economiesmod.core.net;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import io.github.badbull643.economiesmod.core.*;

import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
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


    public MarketClient(UUID userId, PlayerKeys keys, EventLog log,boolean persist) throws IOException {
        this.userId = userId;
        this.keys = keys;
        this.log = log;
        this.state = EventApplier.replay(log);
        this.persist = persist;
        this.appliedSeq = log.lastSeq();
        this.lastHash = log.lastHash();
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
        hello.protocolVersion = HostServer.PROTOCOL_VERSION;
        //test
        System.out.println("[client] hello: lastSeq=" + hello.lastSeq
                + " appliedSeq=" + appliedSeq + " persist=" + persist);
        //test
        channel.send(hello);

        Message reply = channel.receive();
        if (reply instanceof Message.Error) {
            String reason = ((Message.Error) reply).reason;
            channel.close();
            throw new IOException("host refused connection: " + reason);
        }
        if (!(reply instanceof Message.Sync)) {
            channel.close();
            throw new IOException("expected Sync, got " + (reply == null ? "nothing" : reply.type));
        }

        applySyncLines(((Message.Sync) reply).logLines);
        connected = true;

        reader = new Thread(this::readerLoop, "market-client-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** Sends a proposal. Returns immediately — the result arrives via broadcast or onRejected. */
    public String propose(Event event) {
        if (!connected) {
            onRejected.accept("not connected");
            return null;
        }
        String clientEventId = UUID.randomUUID().toString();
        event.clientEventId = clientEventId;   // must be set BEFORE signing

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
                    applyLine(((Message.Accepted) msg).logLine);
                    onStateChanged.run();
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
            connected = false;
            onStateChanged.run();
        }
    }

    private void applySyncLines(List<String> lines) {
        for (String line : lines) {
            applyLine(line);
        }
    }

    private volatile long appliedSeq = 0;

    private void applyLine(String line) {
        SequencedEvent se = EventLog.parseLine(line);

        if (se.seq != appliedSeq + 1) {
            System.err.println("[client] sequence gap: expected " + (appliedSeq + 1)
                    + " got " + se.seq + " (persist=" + persist + ")");
            return;
        }

        if (persist) {
            try {
                log.appendRaw(line);
            } catch (IOException e) {
                System.err.println("[client] failed to persist event: " + e);
                return;
            }
        }

        appliedSeq = se.seq;
        lastHash = se.hash;

        EventApplier.Result result = EventApplier.apply(state, se);
        if (result.accepted) {
            onApplied.accept(se);
        }
    }

    public void disconnect() {
        connected = false;
        if (channel != null) {
            try { channel.close(); } catch (IOException ignored) {}
        }
    }
}