package io.github.badbull643.economiesmod.core.net;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import io.github.badbull643.economiesmod.core.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
public class HostServer {

    public static final String PROTOCOL_VERSION = "1";
    private static final int MAX_CONNECTIONS = 64;

    private final int port;
    private final EventLog log;
    private final MarketState state;
    private final Gson gson = new Gson();

    private ServerSocket serverSocket;

    private final KeyRegistry keyRegistry;

    private Thread sequencerThread;
    private final PeerCache peerCache;

    private final List<MessageChannel> clients = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Proposal> queue = new LinkedBlockingQueue<>(1000);
    private static final int DEDUP_CACHE_SIZE = 10_000;

    private final Set<String> seenEventIds = Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > DEDUP_CACHE_SIZE;
                }
            });

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

    //////////////////////
    private static class RateLimiter {
        private final int maxPerWindow;
        private final long windowMillis;
        private long windowStart = System.currentTimeMillis();
        private int count = 0;

        RateLimiter(int maxPerWindow, long windowMillis) {
            this.maxPerWindow = maxPerWindow;
            this.windowMillis = windowMillis;
        }

        synchronized boolean allow() {
            long now = System.currentTimeMillis();
            if (now - windowStart > windowMillis) {
                windowStart = now;
                count = 0;
            }
            return ++count <= maxPerWindow;
        }
    }

    private final String hostName;

    private final String hostUserId;

    public HostServer(int port, Path logFile, String hostName, String hostUserId,
                      PeerCache peerCache) throws IOException {
        this.port = port;
        this.hostName = hostName;
        this.hostUserId = hostUserId;
        this.peerCache = peerCache;
        this.log = new EventLog(logFile);
        this.keyRegistry = new KeyRegistry(logFile.resolveSibling("known-keys.json"), true);

        long bad = log.verifyChain();
        if (bad != -1) {
            throw new IOException("log chain broken at seq " + bad + " — refusing to start");
        }

        this.state = EventApplier.replay(log);
        System.out.println("[host] replayed " + log.lastSeq() + " events");
    }

    private final CountDownLatch bound = new CountDownLatch(1);
    private volatile IOException bindError;

    public void start() throws IOException {
        sequencerThread = new Thread(this::sequencerLoop, "market-sequencer");
        sequencerThread.setDaemon(true);
        sequencerThread.start();

        try {
            serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            bindError = e;
            bound.countDown();          // release the waiter even on failure
            throw e;
        }
        bound.countDown();
        System.out.println("[host] listening on port " + port);

        try {
            while (running) {
                Socket socket = serverSocket.accept();

                if (clients.size() >= MAX_CONNECTIONS) {
                    System.out.println("[host] refusing connection — at capacity");
                    try (MessageChannel ch = new MessageChannel(socket)) {
                        Message.Error err = new Message.Error();
                        err.reason = "server full";
                        ch.send(err);
                    } catch (IOException ignored) {}
                    continue;
                }

                Thread t = new Thread(() -> handleClient(socket), "market-client");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            if (running) throw e;
        } finally {
            for (MessageChannel ch : clients) {
                try { ch.close(); } catch (IOException ignored) {}
            }
            clients.clear();
        }
    }

    public IOException awaitBound(long millis) throws InterruptedException {
        if (!bound.await(millis, TimeUnit.MILLISECONDS)) {
            return new IOException("timed out waiting for bind");
        }
        return bindError;
    }

    // ─────────── per-connection reader thread ───────────

    private void handleClient(Socket socket) {
        MessageChannel channel = null;
        RateLimiter limiter = new RateLimiter(30, 10_000);   // 30 proposals per 10s

        try {
            // Drop connections that don't complete a handshake promptly.
            socket.setSoTimeout(10_000);

            channel = new MessageChannel(socket);
            System.out.println("[host] connection from " + channel.remoteAddress());

            Message first = channel.receive();

            if (first instanceof Message.Query) {
                Message.QueryReply reply = new Message.QueryReply();
                reply.hosting = true;
                reply.userId = hostUserId;
                reply.lastSeq = log.lastSeq();
                reply.lastHash = log.lastHash();
                reply.hostName = hostName;
                reply.clientCount = clients.size();
                reply.protocolVersion = PROTOCOL_VERSION;
                channel.send(reply);
                return;   // probe done, close the connection
            }

            if (!(first instanceof Message.Hello)) {
                sendError(channel, "expected Hello as first message");
                return;
            }

            if (!handshake(channel, (Message.Hello) first)) {
                return;   // handshake sent its own Error
            }

            // Synced clients may sit idle indefinitely.
            socket.setSoTimeout(0);

            clients.add(channel);
            System.out.println("[host] " + channel.remoteAddress() + " synced and live ("
                    + clients.size() + " connected)");

            Message msg;
            while ((msg = channel.receive()) != null) {
                if (msg instanceof Message.Propose) {
                    Message.Propose prop = (Message.Propose) msg;

                    if (!limiter.allow()) {
                        Message.Rejected r = new Message.Rejected();
                        r.clientEventId = prop.clientEventId;
                        r.reason = "rate limited";
                        channel.send(r);
                        continue;
                    }

                    if (!queue.offer(new Proposal(channel, prop))) {
                        Message.Rejected r = new Message.Rejected();
                        r.clientEventId = prop.clientEventId;
                        r.reason = "server busy";
                        channel.send(r);
                    }
                } else if (msg instanceof Message.Ping) {
                    channel.send(new Message.Pong());
                } else {
                    System.out.println("[host] ignoring unexpected " + msg.type);
                }
            }
        } catch (Exception e) {
            if (running && !(e instanceof SocketException)) {
                System.out.println("[host] client error: " + e);
                e.printStackTrace();
            }
        } finally {
            if (channel != null) {
                clients.remove(channel);
                int remaining = clients.size();
                try { channel.close(); } catch (IOException ignored) {}
                System.out.println("[host] client disconnected (" + remaining + " remain)");
            }
        }
    }

    /** Returns true if the client is caught up and should join the live set. */
    private boolean handshake(MessageChannel channel, Message.Hello hello) throws IOException {
        if (!PROTOCOL_VERSION.equals(hello.protocolVersion)) {
            sendError(channel, "protocol version mismatch — server is " + PROTOCOL_VERSION);
            return false;
        }

        UUID claimedUser;
        try {
            claimedUser = UUID.fromString(hello.userId);
        } catch (Exception e) {
            sendError(channel, "malformed userId");
            return false;
        }

        if (hello.publicKey == null || hello.publicKey.isEmpty()) {
            sendError(channel, "no public key presented");
            return false;
        }

        if (!keyRegistry.register(claimedUser, hello.publicKey)) {
            System.err.println("[host] REFUSED " + claimedUser
                    + " — presented a key that doesn't match the one on record");
            sendError(channel, "public key does not match registered identity");
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

// Catch them up.



        //test here
        if (peerCache != null && !hello.userId.equals(hostUserId)) {
            peerCache.record(hello.userId, hello.displayName,
                    addressOf(channel), hello.hostPort);
        }

        List<SequencedEvent> missing = log.readFrom(hello.lastSeq + 1);
        Message.Sync sync = new Message.Sync();
        sync.logLines = log.rawLinesFrom(hello.lastSeq + 1);
        sync.complete = true;

        // Don't propagate loopback addresses — they're only valid on the machine
        // that recorded them.
        List<PeerCache.Peer> shareable = new ArrayList<>();
        if (peerCache != null) {
            for (PeerCache.Peer p : peerCache.all()) {
                if (!"127.0.0.1".equals(p.address) && !"localhost".equals(p.address)) {
                    shareable.add(p);
                }
            }
        }
        sync.knownPeers = shareable;

        channel.send(sync);
        System.out.println("[host] synced " + missing.size() + " events to "
                + channel.remoteAddress());
        return true;
    }

    /** Extracts just the IP from "/127.0.0.1:55148". */
    private static String addressOf(MessageChannel channel) {
        String raw = channel.remoteAddress();
        if (raw.startsWith("/")) raw = raw.substring(1);

        if (raw.startsWith("[")) {
            // IPv6: /[2001:db8::1]:55148 — take what's inside the brackets.
            int close = raw.indexOf(']');
            return close > 0 ? raw.substring(1, close) : raw;
        }

        int colon = raw.lastIndexOf(':');
        return colon > 0 ? raw.substring(0, colon) : raw;
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



        if (msg.clientEventId != null && !seenEventIds.add(msg.clientEventId)) {
            reject(p.from, msg.clientEventId, "duplicate proposal");
            return;
        }

        Event event;
        try {
            event = gson.fromJson(msg.eventJson, EventLog.classFor(msg.eventType));
        } catch (Exception e) {
            reject(p.from, msg.clientEventId, "malformed event: " + e.getMessage());
            return;
        }

        // Verify the signature before anything else.
        if (msg.signature == null || msg.signature.isEmpty()) {
            reject(p.from, msg.clientEventId, "unsigned proposal");
            return;
        }

        PublicKey key = keyRegistry.lookup(event.userId);
        if (key == null) {
            reject(p.from, msg.clientEventId, "unknown identity");
            return;
        }

        String payload = EventCanonical.canonicalPayload(event);
        if (!PlayerKeys.verify(payload, msg.signature, key)) {
            System.err.println("[host] BAD SIGNATURE from " + event.userId
                    + " — possible impersonation attempt");
            reject(p.from, msg.clientEventId, "invalid signature");
            return;
        }


        // Validate BEFORE logging — a rejected proposal must not enter history.
        SequencedEvent probe = new SequencedEvent();
        probe.seq = log.lastSeq() + 1;
        probe.event = event;
        EventApplier.Result check = EventApplier.validate(state, probe);
        if (!check.accepted) {
            System.out.println("[host] rejected " + msg.eventType + ": " + check.reason);
            reject(p.from, msg.clientEventId, check.reason);
            return;
        }

        // Valid — now it becomes history.
        SequencedEvent se = log.append(event);
        EventApplier.Result result = EventApplier.apply(state, se);

        if (result.accepted) {
            System.out.println("[host] seq " + se.seq + " " + msg.eventType);
            Message.Accepted acc = new Message.Accepted();
            acc.logLine = log.rawLineFor(se.seq);
            broadcast(acc);
        } else {
            // Shouldn't happen — validate said yes. Loud, because it means the two
            // paths disagree, which is a bug.
            System.err.println("[host] BUG: validate passed but apply rejected: " + result.reason);
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

        if (sequencerThread != null) {
            sequencerThread.interrupt();
            sequencerThread = null;
        }

        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }
    }
    // ─────────── standalone entry point ───────────

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 25555;
        Path logFile = Paths.get(args.length > 1 ? args[1] : "server-market.jsonl");
        String hostName = args.length > 2 ? args[2] : "dedicated";

        System.out.println("[host] log file: " + logFile.toAbsolutePath());
        PeerCache peers = new PeerCache(logFile.resolveSibling("server-peers.json"));
        String hostUserId = args.length > 3 ? args[3] : "00000000-0000-0000-0000-0000000000ff";
        new HostServer(port, logFile, hostName, hostUserId, peers).start();
    }
}