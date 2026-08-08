package io.github.badbull643.economiesmod.core.net;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import io.github.badbull643.economiesmod.core.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
public class HostServer {

    public static final String PROTOCOL_VERSION = "1";

    private final int port;
    private final EventLog log;
    private final MarketState state;
    private final Gson gson = new Gson();

    private final List<MessageChannel> clients = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Proposal> queue = new LinkedBlockingQueue<>();
    private final Set<String> seenEventIds = new HashSet<>();

    private volatile boolean running = true;

    /** A proposal waiting to be sequenced, paired with who sent it. */
    private static class Proposal {
        final MessageChannel from;
        final Message.Propose msg;
        Proposal(MessageChannel from, Message.Propose msg) {
            this.from = from;
            this.msg = msg;
        }
    }

    public HostServer(int port, Path logFile) throws IOException {
        this.port = port;
        this.log = new EventLog(logFile);

        long bad = log.verifyChain();
        if (bad != -1) {
            throw new IOException("log chain broken at seq " + bad + " — refusing to start");
        }

        this.state = EventApplier.replay(log);
        System.out.println("[host] replayed " + log.lastSeq() + " events");
    }

    public void start() throws IOException {
        // The sequencer owns the log and state. Single thread, no locks needed.
        Thread sequencer = new Thread(this::sequencerLoop, "market-sequencer");
        sequencer.setDaemon(true);
        sequencer.start();

        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("[host] listening on port " + port);
            while (running) {
                Socket socket = ss.accept();
                Thread t = new Thread(() -> handleClient(socket), "market-client");
                t.setDaemon(true);
                t.start();
            }
        }
    }

    // ─────────── per-connection reader thread ───────────

    private void handleClient(Socket socket) {
        MessageChannel channel = null;
        try {
            channel = new MessageChannel(socket);
            System.out.println("[host] connection from " + channel.remoteAddress());

            // First message must be Hello
            Message first = channel.receive();
            if (!(first instanceof Message.Hello)) {
                sendError(channel, "expected Hello as first message");
                return;
            }

            if (!handshake(channel, (Message.Hello) first)) {
                return;   // handshake sent its own Error and we're done
            }

            clients.add(channel);
            System.out.println("[host] " + channel.remoteAddress() + " synced and live ("
                    + clients.size() + " connected)");

            // Read loop
            Message msg;
            while ((msg = channel.receive()) != null) {
                if (msg instanceof Message.Propose) {
                    queue.put(new Proposal(channel, (Message.Propose) msg));
                } else if (msg instanceof Message.Ping) {
                    channel.send(new Message.Pong());
                } else {
                    System.out.println("[host] ignoring unexpected " + msg.type);
                }
            }
        } catch (Exception e) {
            System.out.println("[host] client error: " + e.getMessage());
        } finally {
            if (channel != null) {
                clients.remove(channel);
                try { channel.close(); } catch (IOException ignored) {}
                System.out.println("[host] client disconnected (" + clients.size() + " remain)");
            }
        }
    }

    /** Returns true if the client is caught up and should join the live set. */
    private boolean handshake(MessageChannel channel, Message.Hello hello) throws IOException {
        if (!PROTOCOL_VERSION.equals(hello.protocolVersion)) {
            sendError(channel, "protocol version mismatch — server is " + PROTOCOL_VERSION);
            return false;
        }

        // Client ahead of us: we're stale, or they're on a different history.
        if (hello.lastSeq > log.lastSeq()) {
            System.err.println("[host] REFUSED: client at seq " + hello.lastSeq
                    + " is ahead of server at " + log.lastSeq());
            sendError(channel, "client is ahead of server (client " + hello.lastSeq
                    + ", server " + log.lastSeq() + ")");
            return false;
        }

        // Matching sequence numbers don't imply matching history — check the hash.
        String ourHash = log.hashAt(hello.lastSeq);
        if (ourHash == null || !ourHash.equals(hello.lastHash)) {
            System.err.println("[host] FORK DETECTED at seq " + hello.lastSeq
                    + " — client hash " + hello.lastHash + ", server hash " + ourHash);
            sendError(channel, "fork detected at seq " + hello.lastSeq);
            return false;
        }

        // Catch them up.
        List<SequencedEvent> missing = log.readFrom(hello.lastSeq + 1);
        Message.Sync sync = new Message.Sync();
        sync.logLines = log.rawLinesFrom(hello.lastSeq + 1);
        sync.complete = true;
        channel.send(sync);
        System.out.println("[host] synced " + missing.size() + " events to "
                + channel.remoteAddress());
        return true;
    }

    // ─────────── the sequencer: one thread, owns log + state ───────────

    private void sequencerLoop() {
        while (running) {
            try {
                Proposal p = queue.take();
                processProposal(p);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                System.err.println("[host] sequencer error: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void processProposal(Proposal p) throws IOException {
        Message.Propose msg = p.msg;

        // Deduplicate retried proposals.
        if (msg.clientEventId != null && !seenEventIds.add(msg.clientEventId)) {
            reject(p.from, msg.clientEventId, "duplicate proposal");
            return;
        }

        // Deserialise the event using the same dispatch the log uses.
        Event event;
        try {
            event = gson.fromJson(msg.eventJson, EventLog.classFor(msg.eventType));
        } catch (Exception e) {
            reject(p.from, msg.clientEventId, "malformed event: " + e.getMessage());
            return;
        }

        // Dry-run validation against a throwaway copy would be ideal, but we don't
        // have state cloning. Instead: append first, apply, and if it's rejected the
        // event stays in the log as a no-op. Not perfect — see note below.
        SequencedEvent se = log.append(event);
        EventApplier.Result result = EventApplier.apply(state, se);

        if (result.accepted) {
            Message.Accepted acc = new Message.Accepted();
            acc.logLine = log.rawLineFor(se.seq);
            broadcast(acc);
        } else {
            reject(p.from, msg.clientEventId, result.reason);
        }
    }

    private void broadcast(Message msg) {
        for (MessageChannel ch : clients) {
            try {
                ch.send(msg);
            } catch (Exception e) {
                System.out.println("[host] broadcast failed to " + ch.remoteAddress());
            }
        }
    }

    private void reject(MessageChannel to, String clientEventId, String reason) {
        Message.Rejected r = new Message.Rejected();
        r.clientEventId = clientEventId;
        r.reason = reason;
        to.send(r);
    }

    private void sendError(MessageChannel channel, String reason) {
        Message.Error err = new Message.Error();
        err.reason = reason;
        channel.send(err);
    }

    public void stop() {
        running = false;
    }

    // ─────────── standalone entry point ───────────

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 25555;
        Path logFile = Paths.get(args.length > 1 ? args[1] : "./server-market.jsonl");
        System.out.println("[host] log file: " + logFile.toAbsolutePath());
        new HostServer(port, logFile).start();
    }
}