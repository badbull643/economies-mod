package io.github.badbull643.economiesmod.core.net;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import io.github.badbull643.economiesmod.core.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class HostServer {

    // 6: MigrateBalance event; MigrateRequest/MigrateResult messages.
    // 8: MarketPolicy event (transaction tax); Sync.dedicated. Batched deliberately —
    //    each wire change costs a version and a window where mixed clients cannot talk,
    //    so the two that were ready went together.
    public static final String PROTOCOL_VERSION = "8";
    /** Only the fallback for the deprecated constructors now — see ServerConfig. */
    private static final int MAX_CONNECTIONS = 64;

    /** Everything the two hosting modes disagree about. Never null. */
    private final ServerConfig config;

    /**
     * How much each identity has handed this host lately. Off unless configured.
     *
     * Host-side rather than in EventApplier, and necessarily so — its answer depends on
     * when it is asked, so a replica replaying later would judge a window that has
     * passed and refuse events the market legitimately holds. See DepositLimiter.
     */
    private final DepositLimiter depositLimiter;

    /**
     * What each identity said about its world when it connected.
     *
     * Kept only for as long as this host runs, and only to be contradicted: the value
     * is in comparing a claim against what the identity subsequently did, which is the
     * one thing here the host observed rather than was told. Never written to the log —
     * a claim nobody can verify has no business in a history everybody replays.
     */
    private final Map<UUID, WorldAttestation> attestations = new ConcurrentHashMap<>();

    /** Which item a deposit is of, or null if the event is not one. */
    private static String depositItemOf(Event event) {
        if (event instanceof Event.Deposit) return ((Event.Deposit) event).itemId;
        if (event instanceof Event.DepositAndList) {
            return ((Event.DepositAndList) event).itemId;
        }
        return null;
    }

    /**
     * Items this event would add to the depositor's balance, or 0 if it is not a
     * deposit.
     *
     * DepositAndList counts the same as Deposit: the goods enter the market either way,
     * and a cap that only watched one of them would be a cap on which button was used.
     */
    private static long depositUnitsOf(Event event) {
        if (event instanceof Event.Deposit) {
            return ((Event.Deposit) event).quantity;
        }
        if (event instanceof Event.DepositAndList) {
            return ((Event.DepositAndList) event).quantity;
        }
        return 0;
    }

    private final int port;
    private final EventLog log;
    private final MarketState state;
    private final Gson gson = new Gson();

    private ServerSocket serverSocket;

    private final KeyRegistry keyRegistry;

    private Thread sequencerThread;
    private final PeerCache peerCache;

    private final List<ClientLink> clients = new CopyOnWriteArrayList<>();

    /**
     * One live client, with its own outbound queue and the thread that drains it.
     *
     * Fan-out used to be a loop of blocking writes on the sequencer thread. A socket
     * write blocks once the peer's receive window fills, so a single client that had
     * stopped reading — suspended laptop, saturated uplink, debugger breakpoint —
     * stopped the sequencer, and with it every other client's trades. That cost scales
     * with maxConnections, which is now something an operator sets.
     *
     * Each client gets a thread and a bounded queue instead. Order is preserved per
     * client because exactly one thread writes to each socket, which matters more than
     * it used to: a client that sees a sequence gap now tears down and resyncs.
     */
    private static final class ClientLink {
        final MessageChannel channel;
        private final BlockingQueue<Message> outbound;
        private final Thread writer;
        private volatile boolean open = true;

        ClientLink(MessageChannel channel, int queueDepth) {
            this.channel = channel;
            this.outbound = new ArrayBlockingQueue<>(queueDepth);
            this.writer = new Thread(this::drain, "market-writer");
            this.writer.setDaemon(true);
            this.writer.start();
        }

        /**
         * Queues one message. False means this client is beyond saving — either its
         * queue is full, so it is further behind than we are willing to buffer, or the
         * socket has already failed underneath it.
         */
        boolean enqueue(Message msg) {
            return open && outbound.offer(msg);
        }

        private void drain() {
            try {
                while (open) {
                    Message msg = outbound.take();
                    channel.send(msg);
                    // PrintWriter swallows IOException, so a dead socket is only
                    // visible as a flag. Without this the thread would spin happily
                    // writing into a closed connection for as long as it was queued.
                    if (channel.failed()) {
                        open = false;
                        return;
                    }
                }
            } catch (InterruptedException e) {
                // Closing. Nothing left to deliver that anyone is waiting for.
            }
        }

        /** Idempotent: both the broadcast path and the reader's finally may call it. */
        void close() {
            open = false;
            writer.interrupt();
            try { channel.close(); } catch (IOException ignored) {}
        }
    }
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
        /** The link rather than the channel: rejections are written by the sequencer,
         *  so they have to go through the same queue broadcasts do or a blocked client
         *  stalls it on the way out. Null for host-authored work. */
        final ClientLink from;
        final Message.Propose msg;
        /** Set instead of msg for work the host authors itself, so it still goes
         *  through the one thread that owns the log. */
        final Runnable hostAction;

        Proposal(ClientLink from, Message.Propose msg) {
            this.from = from;
            this.msg = msg;
            this.hostAction = null;
        }

        Proposal(Runnable hostAction) {
            this.from = null;
            this.msg = null;
            this.hostAction = hostAction;
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

    private final PlayerKeys hostKeys;



    //change too about 50
    /** Kept as an alias so existing callers still read naturally; ServerConfig owns it. */
    public static final long DEFAULT_WELCOME_GRANT = ServerConfig.DEFAULT_WELCOME_GRANT;

    public HostServer(int port, Path logFile, String hostName, String hostUserId,
                      PlayerKeys hostKeys, PeerCache peerCache) throws IOException {
        this(port, logFile, hostName, hostUserId, hostKeys, peerCache, DEFAULT_WELCOME_GRANT);
    }

    public HostServer(int port, Path logFile, String hostName, String hostUserId,
                      PlayerKeys hostKeys, PeerCache peerCache,
                      long welcomeGrantAmount) throws IOException {
        this(configFor(port, hostName, hostUserId, welcomeGrantAmount),
                logFile, hostKeys, peerCache);
    }

    /**
     * Wraps the loose arguments the client still passes in the same object the
     * dedicated launcher loads from disk, so there is one code path below this point
     * rather than two that have to be kept agreeing.
     */
    private static ServerConfig configFor(int port, String hostName, String hostUserId,
                                          long welcomeGrantAmount) {
        ServerConfig cfg = ServerConfig.friendGroup(port);
        cfg.hostName = hostName;
        cfg.hostUserId = hostUserId;
        cfg.welcomeGrant = welcomeGrantAmount;
        return cfg;
    }

    /**
     * The one constructor that matters. Everything the two hosting modes disagree about
     * arrives in the config; nothing below here can tell them apart, which is the whole
     * design — a dedicated server is a host whose operator never changes.
     */
    public HostServer(ServerConfig config, Path logFile, PlayerKeys hostKeys,
                      PeerCache peerCache) throws IOException {
        String configProblem = config.problem();
        if (configProblem != null) {
            throw new IOException("bad server config: " + configProblem);
        }

        this.config = config;
        // Counting is switched on by either feature. The cap needs a total to compare
        // against a ceiling; the attestation check needs one to compare against claimed
        // play time. With neither configured the window is zero and nothing is kept.
        boolean needsCounting = config.maxDepositUnitsPerWindow > 0
                || config.maxDepositUnitsPerPlayHour > 0;
        this.depositLimiter = new DepositLimiter(config.maxDepositUnitsPerWindow,
                needsCounting ? config.depositWindowMinutes * 60_000L : 0L);
        this.welcomeGrantAmount = config.welcomeGrant;
        this.port = config.port;
        this.hostName = config.hostName;
        this.hostUserId = config.hostUserId;
        this.hostKeys = hostKeys;
        this.peerCache = peerCache;
        this.log = new EventLog(logFile);
        this.keyRegistry = new KeyRegistry(logFile.resolveSibling("known-keys.json"), true);

        long bad = log.verifyChain();
        if (bad != -1) {
            throw new IOException("log chain broken at seq " + bad + " — refusing to start");
        }

        this.state = EventApplier.replay(log);

        // A host with no genesis event has no market to serve. Refusing here is what
        // stops a market being created silently, as a side effect of clicking Host.
        if (state.marketId() == null) {
            throw new IOException("this log holds no market — create one before hosting");
        }

        System.out.println("[host] serving '" + state.marketName() + "' ("
                + state.marketId() + ") — replayed " + log.lastSeq() + " events");

        warnIfGrantDisagrees();
    }

    /**
     * Says so when the configured welcome grant is not the one this market hands out.
     *
     * Nothing breaks when they disagree, and this deliberately does not claim
     * otherwise. issueWelcomeGrant already takes the amount from the market and reads
     * the config only as "issue grants at all, or not" — a host that used its own
     * figure would be overruling policy it has no authority over, on a market it may
     * not have created. So the grants that go out are correct whatever this says.
     *
     * What is worth reporting is the silence. An operator who edits welcomeGrant on a
     * running market sees the number change in the file and nothing change anywhere
     * else, because the amount was fixed when the market was created and this setting
     * only reaches a market this server creates itself. Left unsaid, the natural
     * conclusion is that the setting is broken.
     *
     * Zero is not a disagreement — it is the documented opt-out from issuing grants,
     * so it passes silently rather than being reported as a mistake.
     *
     * Said at startup rather than at the first join, because the first join is the
     * moment an operator is least likely to be reading the console.
     */
    public String grantMismatchWarning() {
        // Zero is the opt-out, not a disagreement: issueWelcomeGrant reads it as "do
        // not hand out grants at all", which is a decision a host is entitled to make
        // about its own sequencing. Nothing to correct.
        if (config.welcomeGrant <= 0) return null;

        long market = state.welcomeGrant();
        if (config.welcomeGrant == market) return null;

        return "this market grants " + market + ", and welcomeGrant is set to "
                + config.welcomeGrant + " — the configured figure is not used here."
                + " The amount is the market's, recorded when it was created, so every"
                + " host of it hands out the same one; this setting only chooses"
                + " whether this server issues grants at all, and only sets the amount"
                + " for a market it creates itself. Newcomers will receive " + market
                + ". To hand out " + config.welcomeGrant + ", create a market with that"
                + " figure (delete " + config.logFile + " first — that discards this"
                + " market's history).";
    }

    private void warnIfGrantDisagrees() {
        String warning = grantMismatchWarning();
        if (warning != null) {
            System.err.println("[host] welcome grant mismatch: " + warning);
        }
    }

    private final CountDownLatch bound = new CountDownLatch(1);
    private volatile IOException bindError;

    public void start() throws IOException {
        // Both of these exist because MarketBootstrap writes MarketCreated straight to
        // the log, bypassing processProposal — so the KeyRegistered path that normally
        // registers a player and issues their grant never runs for whoever is hosting.
        try {
            ensureHostRegistered();
            issueWelcomeGrant(creatorUserId());
            issueWelcomeGrant(hostUserIdAsUuid());
        } catch (Exception e) {
            System.err.println("[host] startup grants failed: " + e.getMessage());
        }

        sequencerThread = new Thread(this::sequencerLoop, "market-sequencer");
        sequencerThread.setDaemon(true);
        sequencerThread.start();

        try {
            // An explicit bindAddress listens on that interface only. Absent, this is
            // the same all-interfaces socket as before.
            String bind = config.bindAddress;
            if (bind == null || bind.trim().isEmpty()) {
                serverSocket = new ServerSocket(port);
            } else {
                serverSocket = new ServerSocket(port, 50,
                        java.net.InetAddress.getByName(bind.trim()));
            }
        } catch (IOException e) {
            bindError = e;
            bound.countDown();          // release the waiter even on failure
            throw e;
        }
        bound.countDown();
        System.out.println("[host] listening on port " + port
                + (config.bindAddress == null || config.bindAddress.trim().isEmpty()
                        ? "" : " (" + config.bindAddress.trim() + " only)"));

        try {
            while (running) {
                Socket socket = serverSocket.accept();

                if (clients.size() >= config.maxConnections) {
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
            for (ClientLink link : clients) {
                link.close();
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

    // Host-wide, unlike the per-connection limiter below: Query/MigrateRequest/CatchUp
    // are one-shot and pre-handshake, so a fresh socket resets a per-connection budget
    // to zero — the thing that actually needs bounding is how often anyone reachable
    // can make this host do RSA verification over an arbitrary supplied history before
    // any identity is established.
    private final RateLimiter preAuthLimiter = new RateLimiter(20, 10_000);

    /**
     * Ceiling on how much an unauthenticated sender can accumulate in memory here.
     *
     * preAuthLimiter above only ever sees a request's *first* frame — the follow-up
     * chunks are read inside handleMigrate/handleCatchUp, past the gate. Without a
     * ceiling, one admitted request could stream frames at us indefinitely before any
     * verification runs. Generous enough that no real market approaches it.
     */
    private static final int MAX_BULK_LINES = 200_000;

    /**
     * Reads the rest of a chunked bulk transfer, bounded. Returns null and sends the
     * caller's reply if the sender breaks the protocol or overruns the ceiling.
     */
    private List<String> accumulateChunks(MessageChannel channel, List<String> firstLines,
                                          boolean firstComplete, Class<? extends Message> expected,
                                          Message reply, String what) throws IOException {
        List<String> lines = new ArrayList<>();
        if (firstLines != null) lines.addAll(firstLines);
        boolean complete = firstComplete;
        int frames = 1;

        while (!complete) {
            if (lines.size() > MAX_BULK_LINES) {
                setReason(reply, "that history is too large to accept");
                channel.send(reply);
                return null;
            }
            Message next = channel.receive();
            if (!expected.isInstance(next)) {
                setReason(reply, "expected the rest of the " + what);
                channel.send(reply);
                return null;
            }
            if (next instanceof Message.MigrateRequest) {
                Message.MigrateRequest r = (Message.MigrateRequest) next;
                if (r.logLines != null) lines.addAll(r.logLines);
                complete = r.complete;
            } else {
                Message.CatchUp r = (Message.CatchUp) next;
                if (r.logLines != null) lines.addAll(r.logLines);
                complete = r.complete;
            }
            frames++;
        }

        if (lines.size() > MAX_BULK_LINES) {
            setReason(reply, "that history is too large to accept");
            channel.send(reply);
            return null;
        }
        if (frames > 1) {
            System.out.println("[host] received " + lines.size() + " events for the "
                    + what + " in " + frames + " chunks");
        }
        return lines;
    }

    private static void setReason(Message reply, String reason) {
        if (reply instanceof Message.MigrateResult) {
            ((Message.MigrateResult) reply).reason = reason;
        } else if (reply instanceof Message.CatchUpResult) {
            ((Message.CatchUpResult) reply).reason = reason;
        }
    }

    private void handleClient(Socket socket) {
        MessageChannel channel = null;
        // Only set once this connection becomes a live client. Probes and pre-handshake
        // exchanges never get one, which is what distinguishes them on the way out.
        ClientLink link = null;
        RateLimiter limiter = new RateLimiter(30, 10_000);   // 30 proposals per 10s

        try {
            // Drop connections that don't complete a handshake promptly.
            socket.setSoTimeout(10_000);

            channel = new MessageChannel(socket);

            Message first = channel.receive();

            if (first instanceof Message.Query
                    || first instanceof Message.MigrateRequest
                    || first instanceof Message.CatchUp) {
                if (!preAuthLimiter.allow()) {
                    sendError(channel, "rate limited");
                    return;
                }
            }

            if (first instanceof Message.Query) {
                Message.Query q = (Message.Query) first;
                Message.QueryReply reply = new Message.QueryReply();
                reply.hosting = true;
                reply.userId = hostUserId;
                reply.hostName = hostName;
                reply.lastSeq = log.lastSeq();
                reply.lastHash = log.lastHash();
                reply.clientCount = clients.size();
                reply.protocolVersion = PROTOCOL_VERSION;
                reply.publicKey = hostKeys.publicKeyString();
                reply.marketId = state.marketId() != null
                        ? state.marketId().toString() : null;
                reply.marketName = state.marketName();
                // Set before signing, or it would be the one field in this reply a
                // bystander could rewrite.
                reply.dedicated = config.dedicated;
                try {
                    reply.signature = hostKeys.sign(Probe.queryPayload(reply, q.nonce));
                } catch (GeneralSecurityException e) {
                    reply.signature = null;
                }
                channel.send(reply);
                return;   // probe done, close the connection
            }

            if (first instanceof Message.MigrateRequest) {
                handleMigrate(channel, (Message.MigrateRequest) first);
                return;   // one-shot, like a probe
            }

            if (first instanceof Message.CatchUp) {
                handleCatchUp(channel, (Message.CatchUp) first);
                return;
            }

            if (!(first instanceof Message.Hello)) {
                sendError(channel, "expected Hello as first message");
                return;
            }

            // Logged here rather than on accept: discovery polls open a connection every
            // few seconds and close it again, and printing those as connects/disconnects
            // buried the real ones.
            System.out.println("[host] connection from " + channel.remoteAddress());

            Message.Hello hello = (Message.Hello) first;
            if (!handshake(channel, hello)) {
                return;   // handshake sent its own Error
            }

            // Held for the rest of the connection: an attestation update arriving later
            // has to be filed against the identity that made the original claim, and
            // the Hello is the only place that is stated.
            UUID liveUser = UUID.fromString(hello.userId);

            // Synced clients may sit idle indefinitely.
            socket.setSoTimeout(0);

            link = new ClientLink(channel, config.outboundQueueDepth);
            clients.add(link);
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

                    if (!queue.offer(new Proposal(link, prop))) {
                        Message.Rejected r = new Message.Rejected();
                        r.clientEventId = prop.clientEventId;
                        r.reason = "server busy";
                        channel.send(r);
                    }
                } else if (msg instanceof Message.Attest) {
                    // The handshake described a world that has since changed. Re-judged
                    // against the same policy, because a description that was acceptable
                    // when given is not a licence for whatever the world becomes.
                    WorldAttestation now = ((Message.Attest) msg).attestation;
                    if (now == null) continue;

                    WorldAttestation was = attestations.put(liveUser, now);

                    // Logged whether or not policy acts on it. An operator who has not
                    // switched on refuseCheatWorlds still wants to know that somebody
                    // turned cheats on mid-session — that is the whole anomaly signal,
                    // and silence would leave "nothing happened" and "the policy is off"
                    // looking identical from the console.
                    boolean newlyCheating = now.cheatsAvailable()
                            && (was == null || !was.cheatsAvailable());
                    if (newlyCheating) {
                        System.out.println("[host] " + hello.displayName
                                + " enabled commands in their world"
                                + (now.cheatsEnabledLater() ? " after creating it" : ""));
                    }

                    List<String> objections = now.objections(config, 0);
                    if (!objections.isEmpty()) {
                        String why = String.join("; ", objections);
                        System.out.println("[host] " + hello.displayName
                                + " no longer passes: " + why);

                        // Banned only here, never at the door. Arriving with cheats is
                        // refused already and needs no permanent record; being admitted
                        // under one description and then changing the thing that was
                        // checked is the only case that looks like a decision.
                        if (config.banOnWorldChange && config.ban(liveUser.toString())) {
                            System.out.println("[host] banned " + liveUser
                                    + " — remove them from deny in the config to undo it");
                            sendError(channel, Refusal.NOT_ADMITTED, why
                                    + " — this server bans identities that change their"
                                    + " world after connecting, so speak to whoever runs"
                                    + " it");
                            break;
                        }

                        sendError(channel, Refusal.NOT_ADMITTED, why);
                        break;
                    }
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
                // Only announce a disconnect for something that was actually a live
                // client — a probe was never in the set, and reporting it as a
                // departure made the count jump around for no reason.
                boolean wasLive = link != null && clients.remove(link);
                if (link != null) {
                    link.close();       // stops the writer thread and closes the socket
                } else {
                    try { channel.close(); } catch (IOException ignored) {}
                }
                if (wasLive) {
                    System.out.println("[host] client disconnected ("
                            + clients.size() + " remain)");
                }
            }
        }
    }

    /** Returns true if the client is caught up and should join the live set. */
    private boolean handshake(MessageChannel channel, Message.Hello hello) throws IOException {
        if (!PROTOCOL_VERSION.equals(hello.protocolVersion)) {
            System.out.println("[host] refused " + hello.displayName + " — their protocol "
                    + hello.protocolVersion + ", ours " + PROTOCOL_VERSION);
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

        // Before the sync, which is the expensive part: an identity that is not welcome
        // should cost this server one comparison, not a whole history read and send.
        String notWelcome = config.refuses(hello.userId);
        if (notWelcome != null) {
            System.out.println("[host] refused " + hello.displayName + " ("
                    + hello.userId + ") — " + notWelcome);
            sendError(channel, Refusal.NOT_ADMITTED, notWelcome);
            return false;
        }

        if (config.requireAttestation && hello.attestation == null) {
            System.out.println("[host] refused " + hello.displayName
                    + " — described no world");
            sendError(channel, Refusal.NOT_ADMITTED, "this server asks connecting"
                    + " players to describe their world, and yours did not");
            return false;
        }

        if (hello.attestation != null) {
            // Nothing has been deposited yet this session, so only the claims that stand
            // on their own can be judged here. The one worth having — deposits against
            // claimed play time — needs something to compare against and is checked as
            // deposits arrive.
            List<String> objections = hello.attestation.objections(config, 0);
            if (!objections.isEmpty()) {
                System.out.println("[host] refused " + hello.displayName + " — "
                        + String.join("; ", objections));
                sendError(channel, Refusal.NOT_ADMITTED, String.join("; ", objections));
                return false;
            }
            attestations.put(claimedUser, hello.attestation);
            System.out.println("[host] " + hello.displayName + " reports "
                    + String.format("%.1f", hello.attestation.claimedHours())
                    + "h in a " + hello.attestation.gameMode + " world"
                    + (hello.attestation.cheatsEnabledLater()
                            ? " with commands switched on this session"
                            : hello.attestation.cheatsAvailable()
                                    ? " with commands enabled" : "")
                    + " (" + hello.attestation.worldIdHash + ")");
        }

        // Admission is decided by the log, not by known-keys.json. Two TOFU registries
        // that can disagree is the same hazard D1 removed from event verification: here
        // it locked a player out of their own market whenever the side file went stale,
        // with no in-game way back. The log is authoritative for who owns an identity;
        // if it has no key for them yet, KeyRegistered will settle it, self-certified.
        String knownKey = state.publicKeyOf(claimedUser);
        if (knownKey != null && !knownKey.equals(hello.publicKey)) {
            System.err.println("[host] REFUSED " + claimedUser
                    + " — key does not match the one registered in this market");
            sendError(channel, Refusal.KEY_MISMATCH,
                    "your signing key doesn't match the one this market has for you"
                            + " — if you moved computers, copy your identity file across");
            return false;
        }

        // Still recorded, for peer display and so a changed key is visible in the logs,
        // but it no longer decides anything.
        keyRegistry.register(claimedUser, hello.publicKey);

        // Market identity is checked before sequence numbers, because "you are on a
        // different market" and "you have diverged from this market" are different
        // problems with different fixes, and the seq/hash check cannot tell them apart.
        String ourMarket = state.marketId() != null ? state.marketId().toString() : null;

        if (hello.marketId == null) {
            // No history at all — free to adopt this market. This is the bootstrap
            // path, and it must keep working: it is how anyone ever joins.
            if (hello.lastSeq != 0) {
                sendError(channel, Refusal.NO_IDENTITY, "your log has " + hello.lastSeq
                        + " events but belongs to no market — it predates market identity");
                return false;
            }
        } else if (!hello.marketId.equals(ourMarket)) {
            System.err.println("[host] REFUSED " + claimedUser + " — different market ("
                    + hello.marketId + " vs " + ourMarket + ")");
            String theirs = hello.marketName != null ? hello.marketName : "another market";
            sendError(channel, Refusal.DIFFERENT_MARKET,
                    "you hold '" + theirs + "', this host serves '" + state.marketName()
                            + "' — separate economies, which cannot be merged");
            return false;
        }

        // Client ahead of us: we're stale, or they're on a different history.
        if (hello.lastSeq > log.lastSeq()) {
            // Not a terminal refusal, unlike the two above: we're telling them where our
            // head is so they can work out whether they merely extend us — which we can't
            // tell from here — and offer the tail back. Reads as a failure on stderr when
            // it is usually the first half of a successful fast-forward.
            System.out.println("[host] behind: client at seq " + hello.lastSeq
                    + ", we're at " + log.lastSeq()
                    + " — sent our head so they can offer a catch-up");
            Message.Error err = new Message.Error();
            err.code = Refusal.AHEAD;
            err.reason = "you have events this host doesn't (you " + hello.lastSeq
                    + ", host " + log.lastSeq() + ")";
            err.hostSeq = log.lastSeq();
            err.hostHash = log.lastHash();
            err.hostName = hostName;
            channel.send(err);
            return false;
        }

        // Matching sequence numbers don't imply matching history — check the hash.
        String ourHash = log.hashAt(hello.lastSeq);
        if (ourHash == null || !ourHash.equals(hello.lastHash)) {
            System.err.println("[host] FORK DETECTED at seq " + hello.lastSeq
                    + " — client hash " + hello.lastHash + ", server hash " + ourHash);
            // The point of disagreement rather than our head: this branch is only
            // reached when the client is at or behind us, so our head says nothing
            // about where the two chains parted, while our hash at their head is
            // exactly the value they cannot compute for themselves.
            //
            // It does not locate the split — that is somewhere at or before this seq,
            // and finding it would need hashes below their head that neither side
            // sends. It is enough to state the disagreement honestly and to raise the
            // FORKED banner, which is what a client on this branch needs.
            Message.Error err = new Message.Error();
            err.code = Refusal.FORK;
            err.reason = "your history diverged from this market at event "
                    + hello.lastSeq;
            err.hostSeq = hello.lastSeq;
            err.hostHash = ourHash;
            err.hostName = hostName;
            channel.send(err);
            return false;
        }

// Catch them up.



        //test here
        if (peerCache != null && !hello.userId.equals(hostUserId)) {
            peerCache.record(hello.userId, hello.displayName,
                    addressOf(channel), hello.hostPort,hello.publicKey);
        }

        List<String> raw = log.rawLinesFrom(hello.lastSeq + 1);

        // Who else is here, for a market where hosting rotates and the next host is one
        // of these people.
        //
        // A dedicated server is not that market. It never hands over, so nobody needs
        // to reach its clients; those clients are behind NAT with nothing forwarded, so
        // the port they advertise is one no one can open; and a residential address is
        // wrong again within days. Sharing the roster buys nobody a connection they
        // could actually make, and costs every joiner the addresses of everyone who
        // came before — merged into their own cache and written to disk. So a dedicated
        // host tells nobody about anybody.
        //
        // Still recorded either way: server-peers.json is the operator's own note of
        // who connected from where, which is an ordinary thing for a server to keep.
        // The broadcast is what does not survive the reasoning, not the note.
        List<PeerCache.Peer> shareable = new ArrayList<>();
        if (peerCache != null && !config.dedicated) {
            for (PeerCache.Peer p : peerCache.all()) {
                // Loopback is only valid on the machine that recorded it.
                if (!"127.0.0.1".equals(p.address) && !"localhost".equals(p.address)) {
                    shareable.add(p);
                }
            }
        }

        // A fresh joiner syncs from seq 1, so this is the bulk path that outgrows one
        // frame first. Identity and peers ride on the first chunk only; the client has
        // everything it needs to set up before the history finishes arriving.
        List<List<String>> chunks = MessageChannel.chunkByByteBudget(raw);
        for (int i = 0; i < chunks.size(); i++) {
            Message.Sync sync = new Message.Sync();
            sync.logLines = chunks.get(i);
            sync.complete = (i == chunks.size() - 1);
            if (i == 0) {
                sync.hostUserId = hostUserId;
                sync.hostName = hostName;
                sync.hostPort = port;
                sync.hostPublicKey = hostKeys.publicKeyString();
                sync.marketId = ourMarket;
                sync.marketName = state.marketName();
                sync.dedicated = config.dedicated;
                sync.knownPeers = shareable;
            }
            channel.send(sync);
        }

        System.out.println("[host] synced " + raw.size() + " events to "
                + channel.remoteAddress()
                + (chunks.size() > 1 ? " in " + chunks.size() + " chunks" : ""));
        return true;
    }

    /**
     * Honours a migration from a market this player is abandoning.
     *
     * The branch is verified here, on the connection thread, because checking an RSA
     * signature per event over a whole history is far too slow to run on the sequencer
     * — but the resulting write is queued, so the log still only ever has one writer.
     * The claim is recomputed from the verified branch, never taken from the request.
     */
    private void handleMigrate(MessageChannel channel, Message.MigrateRequest first) {
        Message.MigrateResult reply = new Message.MigrateResult();
        try {
            // Migration is a pre-handshake exchange that writes a MigrateBalance and
            // credits the sender. Gating only the handshake would leave the admission
            // policy bypassable by the one path that hands out money.
            String notWelcome = config.refuses(first.userId);
            if (notWelcome != null) {
                System.out.println("[host] refused migration from " + first.userId
                        + " — " + notWelcome);
                reply.reason = notWelcome;
                channel.send(reply);
                return;
            }

            // A whole history can arrive as several chunks — see MessageChannel's
            // CHUNK_BUDGET_BYTES — so keep reading until the sender marks the last one,
            // accumulating before the expensive verify runs once over all of it.
            List<String> lines = accumulateChunks(channel, first.logLines, first.complete,
                    Message.MigrateRequest.class, reply, "migration request");
            if (lines == null) return;

            UUID who = UUID.fromString(first.userId);

            MarketArchive.Verified foreign = MarketArchive.verifyLines(lines);

            if (foreign.state.marketId().equals(state.marketId())) {
                reply.reason = "that is this market, not a different one";
                channel.send(reply);
                return;
            }
            if (!foreign.state.isRegistered(who)) {
                reply.reason = "you have no identity in that market";
                channel.send(reply);
                return;
            }

            NetPosition position = NetPosition.of(foreign.state, who);
            if (position.isEmpty()) {
                reply.reason = "you hold nothing in that market";
                channel.send(reply);
                return;
            }

            Event.MigrateBalance mb = new Event.MigrateBalance();
            mb.userId = hostUserIdAsUuid();
            mb.marketId = state.marketId();
            mb.fromMarketId = foreign.state.marketId();
            mb.fromMarketName = foreign.state.marketName();
            mb.fromHeadSeq = foreign.headSeq;
            mb.fromHeadHash = foreign.headHash;
            mb.beneficiary = who;
            mb.credits = position.credits;
            mb.items = position.items;
            mb.foreignParticipants = new ArrayList<>(foreign.state.registeredUsers());
            mb.clientEventId = UUID.randomUUID().toString();
            mb.timestamp = System.currentTimeMillis();

            // Hand the write to the sequencer and wait for its verdict, so the client
            // learns whether it actually landed before it resets its own log.
            final CountDownLatch done = new CountDownLatch(1);
            final String[] failure = new String[1];

            if (!queue.offer(new Proposal(() -> {
                try {
                    failure[0] = appendHostEvent(mb);
                } catch (Exception e) {
                    failure[0] = "host error: " + e.getMessage();
                } finally {
                    done.countDown();
                }
            }))) {
                reply.reason = "server busy";
                channel.send(reply);
                return;
            }

            if (!done.await(10, TimeUnit.SECONDS)) {
                reply.reason = "timed out waiting for the host to record it";
                channel.send(reply);
                return;
            }

            if (failure[0] != null) {
                reply.reason = failure[0];
            } else {
                reply.accepted = true;
                reply.credits = position.credits;
                reply.summary = position.describe();
                System.out.println("[host] migrated " + position.describe() + " for " + who
                        + " from '" + mb.fromMarketName + "'");
            }
            channel.send(reply);

        } catch (MarketArchive.InvalidArchive e) {
            reply.reason = "that history failed verification: " + e.getMessage();
            channel.send(reply);
        } catch (Exception e) {
            reply.reason = "could not process migration: " + e.getMessage();
            channel.send(reply);
        }
    }

    /** Validates, signs, appends and broadcasts an event the host authored. Returns
     *  null on success, or why it was refused. Sequencer thread only. */
    private String appendHostEvent(Event event) throws IOException {
        SequencedEvent probe = new SequencedEvent();
        probe.seq = log.lastSeq() + 1;
        probe.event = event;

        EventApplier.Result check = EventApplier.validate(state, probe);
        if (!check.accepted) return check.reason;

        String signature;
        try {
            signature = hostKeys.sign(EventCanonical.canonicalPayload(event));
        } catch (GeneralSecurityException e) {
            return "could not sign: " + e.getMessage();
        }

        SequencedEvent se = log.append(event, signature);
        EventApplier.Result result = EventApplier.apply(state, se);
        if (!result.accepted) return result.reason;

        System.out.println("[host] seq " + se.seq + " "
                + event.getClass().getSimpleName());
        Message.Accepted acc = new Message.Accepted();
        acc.logLine = log.rawLineFor(se.seq);
        broadcast(acc);
        return null;
    }

    /**
     * Fast-forwards this host onto a longer branch of the market it already serves.
     *
     * Safe precisely because it is a fast-forward and nothing else: the offered events
     * must chain onto our current head, carry valid signatures, and validate against
     * cumulative state — the same three tests they'd have faced arriving live. If our
     * head isn't an ancestor of theirs the chain check fails and this is a fork, which
     * is a different problem with a different answer.
     */
    private void handleCatchUp(MessageChannel channel, Message.CatchUp req) {
        Message.CatchUpResult reply = new Message.CatchUpResult();
        try {
            // Also pre-handshake, and also a write: it appends the offered events to
            // this log. The events are verified and must fast-forward cleanly, so this
            // is not a hole in the trust model — but who may append to this server's
            // copy is exactly the question admission exists to answer.
            String notWelcome = config.refuses(req.userId);
            if (notWelcome != null) {
                System.out.println("[host] refused catch-up from " + req.userId
                        + " — " + notWelcome);
                reply.reason = notWelcome;
                channel.send(reply);
                return;
            }

            // Chunked like a migration, and for the same reason: there is no bound on
            // how far a host can have fallen behind its own market.
            final List<String> offered = accumulateChunks(channel, req.logLines, req.complete,
                    Message.CatchUp.class, reply, "catch-up offer");
            if (offered == null) return;

            if (offered.isEmpty()) {
                reply.reason = "nothing offered";
                channel.send(reply);
                return;
            }

            final CountDownLatch done = new CountDownLatch(1);
            final String[] failure = new String[1];
            final int[] applied = new int[1];

            if (!queue.offer(new Proposal(() -> {
                try {
                    adoptLines(offered, applied);
                } catch (Exception e) {
                    failure[0] = e.getMessage();
                } finally {
                    done.countDown();
                }
            }))) {
                reply.reason = "server busy";
                channel.send(reply);
                return;
            }

            if (!done.await(30, TimeUnit.SECONDS)) {
                reply.reason = "timed out";
                channel.send(reply);
                return;
            }

            // applied[0] is filled in as adoptLines goes, so it reflects what actually
            // landed even when it stops partway — the host has already broadcast those
            // events to its live clients by the time a later one fails, so telling the
            // caller "nothing happened" would be wrong, not just uninformative.
            reply.applied = applied[0];
            if (failure[0] != null) {
                reply.reason = failure[0];
            } else {
                reply.accepted = true;
            }
            System.out.println("[host] caught up " + applied[0] + " events from "
                    + req.userId + " — now at seq " + log.lastSeq()
                    + (failure[0] != null ? " (stopped: " + failure[0] + ")" : ""));
            channel.send(reply);

        } catch (Exception e) {
            reply.reason = "could not catch up: " + e.getMessage();
            channel.send(reply);
        }
    }

    /**
     * Sequencer thread only. Throws with a reason if any line is unacceptable.
     *
     * Takes the counter to increment as an out-parameter rather than returning a count,
     * so a caller reading it from the catch block still sees how many events were
     * adopted before the one that failed — those are already applied and broadcast,
     * not rolled back.
     */
    private void adoptLines(List<String> lines, int[] appliedCounter) throws IOException {
        for (String line : lines) {
            SequencedEvent se;
            try {
                se = EventLog.parseLine(line);
            } catch (Exception e) {
                throw new IOException("unreadable event: " + e.getMessage());
            }

            if (se.seq != log.lastSeq() + 1) {
                throw new IOException("event " + se.seq + " doesn't follow our head "
                        + log.lastSeq() + " — this is a fork, not a catch-up");
            }

            String problem = EventVerifier.verify(state, se.event, se.signature);
            if (problem != null) {
                throw new IOException("event " + se.seq + ": " + problem);
            }

            EventApplier.Result check = EventApplier.validate(state, se);
            if (!check.accepted) {
                throw new IOException("event " + se.seq + " invalid here: " + check.reason);
            }

            // appendRaw re-checks seq and prevHash against our head, so a branch that
            // doesn't actually descend from us is refused at the file level too.
            log.appendRaw(line);
            EventApplier.apply(state, se);
            appliedCounter[0]++;

            Message.Accepted acc = new Message.Accepted();
            acc.logLine = line;
            broadcast(acc);
        }
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
                if (p.hostAction != null) {
                    p.hostAction.run();
                } else {
                    processProposal(p);
                }
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

        // Verified against the log's key directory, not known-keys.json — so the host
        // and every replica reach the same verdict about the same event. See
        // EventVerifier for why two sources of truth here was a hazard.
        String problem = EventVerifier.verify(state, event, msg.signature);
        if (problem != null) {
            System.err.println("[host] REFUSED event from " + event.userId + ": " + problem);
            reject(p.from, msg.clientEventId, problem);
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

        // After validate, so an event refused for some other reason costs nobody any of
        // their allowance, and before append, because this is the last point at which
        // declining is free.
        long depositUnits = depositUnitsOf(event);
        if (depositUnits > 0 && !depositLimiter.allows(event.userId, depositUnits,
                System.currentTimeMillis())) {
            long remaining = depositLimiter.remainingFor(event.userId,
                    System.currentTimeMillis());
            // The refusal an operator actually wants to see. Nothing else here reports
            // a client behaving implausibly rather than incorrectly.
            System.out.println("[host] deposit cap: " + event.userId + " tried "
                    + depositUnits + ", has " + remaining + " left of "
                    + depositLimiter.maxUnits());
            reject(p.from, msg.clientEventId, "deposit limit reached — this server"
                    + " accepts " + depositLimiter.maxUnits() + " items per identity"
                    + " per " + config.depositWindowMinutes + " minutes, and you have "
                    + remaining + " left");
            return;
        }

        // The contradiction check, at the only moment both halves of it exist: a claim
        // made at handshake, and a quantity this host has now actually been handed.
        // Neither is checkable alone; together they can be impossible.
        // Against the player's own statistics, which they did not write. Minecraft
        // counts what is mined, crafted and picked up, and /give increments none of it,
        // so a deposit far beyond that total is a contradiction in a record the
        // depositor cannot quietly restate.
        //
        // A generous multiple, not a limit: the count is a floor, since smelted output
        // and anything taken from a chest never touch PICKED_UP. The case worth catching
        // is out by hundreds, not by three.
        if (depositUnits > 0 && config.maxDepositMultipleOfHandled > 0) {
            WorldAttestation claim = attestations.get(event.userId);
            String itemId = depositItemOf(event);
            if (claim != null && itemId != null) {
                long handled = claim.handledOf(itemId);

                // Plus whatever this market has already given them. A withdrawal lands
                // in an inventory through insertStack, which increments no statistic —
                // the same reason /give leaves no trace — so without this the rule would
                // refuse somebody re-depositing goods it handed out itself, which is the
                // one case where provenance is not in question: it was in the ledger a
                // moment ago. Read from state rather than the claim, so it is the
                // market's own record and not the depositor's account of it.
                long fromThisMarket = state.withdrawnBy(event.userId, itemId);
                long allowed = Math.addExact(
                        Math.multiplyExact(handled,
                                (long) config.maxDepositMultipleOfHandled),
                        fromThisMarket);
                long already = depositLimiter.usedBy(event.userId, itemId,
                        System.currentTimeMillis());

                if (already + depositUnits > allowed) {
                    System.out.println("[host] implausible deposit from " + event.userId
                            + ": " + (already + depositUnits) + " " + itemId
                            + " against statistics showing " + handled + " ever handled");
                    reject(p.from, msg.clientEventId, "you have handled " + handled + " "
                            + itemId + " by your own statistics, and this server accepts"
                            + " deposits up to " + config.maxDepositMultipleOfHandled
                            + " times that"
                            + (fromThisMarket > 0
                                    ? ", plus the " + fromThisMarket
                                            + " this market gave you" : ""));
                    return;
                }
            }
        }

        if (depositUnits > 0) {
            WorldAttestation claim = attestations.get(event.userId);
            if (claim != null) {
                long already = depositLimiter.usedBy(event.userId,
                        System.currentTimeMillis());
                List<String> objections = claim.objections(config, already + depositUnits);
                if (!objections.isEmpty()) {
                    String why = String.join("; ", objections);
                    System.out.println("[host] implausible deposit from " + event.userId
                            + ": " + why);
                    reject(p.from, msg.clientEventId, why);
                    return;
                }
            }
        }

        // Valid — now it becomes history. The signature is stored alongside the event
        // so anyone replaying this log later can check authorship without having been
        // present when it was written.
        SequencedEvent se = log.append(event, msg.signature);
        EventApplier.Result result = EventApplier.apply(state, se);

        // Counted only once it is genuinely in the log.
        if (result.accepted && depositUnits > 0) {
            depositLimiter.record(event.userId, depositItemOf(event), depositUnits,
                    System.currentTimeMillis());
        }

        if (result.accepted) {
            // Deposits say who and what. Everything else is a line in a ledger anybody
            // can read back; a deposit is the one event a rule may have just refused,
            // and "seq 15 DepositAndList" cannot be told apart from the one that was
            // turned away a moment earlier.
            String detail = depositUnits > 0
                    ? " — " + depositUnits + " " + depositItemOf(event)
                            + " from " + event.userId
                    : "";
            System.out.println("[host] seq " + se.seq + " " + msg.eventType + detail);
            Message.Accepted acc = new Message.Accepted();
            acc.logLine = log.rawLineFor(se.seq);
            broadcast(acc);

            // A newly registered identity gets its starting balance immediately. Done
            // on the sequencer thread, so it lands right after the registration it
            // responds to and can't interleave with anything.
            if (event instanceof Event.KeyRegistered) {
                issueWelcomeGrant(event.userId);
            }
        } else {
            // Shouldn't happen — validate said yes. Loud, because it means the two
            // paths disagree, which is a bug.
            System.err.println("[host] BUG: validate passed but apply rejected: " + result.reason);
            reject(p.from, msg.clientEventId, result.reason);
        }
    }

    /**
     * Starting balance for an identity new to this market. Zero disables grants
     * entirely — a reasonable choice for a public deployment that would rather all
     * currency be earned than issued.
     */
    private final long welcomeGrantAmount;

    private UUID creatorUserId() {
        return state.creator();
    }

    /**
     * Puts the host's own key in the log if it isn't already there.
     *
     * The host authors events of its own — welcome grants — and EventApplier requires
     * an event's author to be registered. A host that never registers can't issue any,
     * which is the state a dedicated server lands in when it serves a market it didn't
     * create: it never self-connects, so nothing else would ever register it.
     */
    private void ensureHostRegistered() throws IOException {
        UUID me;
        try {
            me = hostUserIdAsUuid();
        } catch (IllegalArgumentException e) {
            System.err.println("[host] malformed host userId — cannot register: " + hostUserId);
            return;
        }
        if (state.isRegistered(me)) return;

        Event.KeyRegistered kr = new Event.KeyRegistered();
        kr.userId = me;
        kr.marketId = state.marketId();
        kr.publicKey = hostKeys.publicKeyString();
        kr.clientEventId = UUID.randomUUID().toString();
        kr.timestamp = System.currentTimeMillis();

        SequencedEvent probe = new SequencedEvent();
        probe.seq = log.lastSeq() + 1;
        probe.event = kr;
        EventApplier.Result check = EventApplier.validate(state, probe);
        if (!check.accepted) {
            System.err.println("[host] could not register host identity: " + check.reason);
            return;
        }

        String signature;
        try {
            signature = hostKeys.sign(EventCanonical.canonicalPayload(kr));
        } catch (GeneralSecurityException e) {
            System.err.println("[host] could not sign host registration: " + e.getMessage());
            return;
        }

        SequencedEvent se = log.append(kr, signature);
        if (EventApplier.apply(state, se).accepted) {
            System.out.println("[host] seq " + se.seq + " KeyRegistered (host identity)");
        }
    }

    private void issueWelcomeGrant(UUID userId) throws IOException {
        // Two different questions, and only one of them is the market's to answer.
        //
        // Whether to issue grants at all is this host's business: declining is not a
        // policy violation, it just means an event nobody was owed does not exist. A
        // configured zero is that opt-out.
        //
        // How much is emphatically not. A host that used its own figure would author
        // grants every replica rejected, and on a market it did not create it would be
        // overruling policy it has no authority over.
        if (config.welcomeGrant <= 0) return;

        long amount = state.welcomeGrant();
        if (amount <= 0) return;
        if (userId == null) return;
        if (state.hasBeenGranted(userId)) return;

        Event.WelcomeGrant wg = new Event.WelcomeGrant();
        wg.userId = hostUserIdAsUuid();
        wg.marketId = state.marketId();
        wg.targetUserId = userId;
        wg.amount = amount;
        wg.clientEventId = UUID.randomUUID().toString();
        wg.timestamp = System.currentTimeMillis();

        SequencedEvent probe = new SequencedEvent();
        probe.seq = log.lastSeq() + 1;
        probe.event = wg;
        EventApplier.Result check = EventApplier.validate(state, probe);
        if (!check.accepted) {
            // Was a silent return, which made a server that issued no grants at all
            // impossible to diagnose from the console.
            System.err.println("[host] welcome grant for " + userId
                    + " refused: " + check.reason);
            return;
        }

        String signature;
        try {
            signature = hostKeys.sign(EventCanonical.canonicalPayload(wg));
        } catch (GeneralSecurityException e) {
            System.err.println("[host] could not sign welcome grant: " + e.getMessage());
            return;
        }

        SequencedEvent se = log.append(wg, signature);
        if (EventApplier.apply(state, se).accepted) {
            System.out.println("[host] seq " + se.seq + " WelcomeGrant " + amount
                    + " to " + userId);
            Message.Accepted acc = new Message.Accepted();
            acc.logLine = log.rawLineFor(se.seq);
            broadcast(acc);
        }
    }

    private UUID hostUserIdAsUuid() {
        return UUID.fromString(hostUserId);
    }

    private void broadcast(Message msg) {
        // Hands off to each client's writer thread and returns. Nothing here can block
        // on a socket, so one unresponsive client no longer holds up sequencing for
        // everyone else — it just falls behind in its own queue until it is dropped.
        for (ClientLink link : clients) {
            if (!link.enqueue(msg)) {
                System.out.println("[host] dropping " + link.channel.remoteAddress()
                        + " — too far behind to keep up");
                clients.remove(link);
                link.close();
            }
        }
    }

    /**
     * Queued rather than written, because this runs on the sequencer.
     *
     * A direct send here would block that thread against a client whose socket has
     * filled — the same stall the broadcast fan-out was just moved off, reachable by
     * any client that submits something invalid. A rejection that cannot be queued is
     * dropped: the client is already beyond its backlog and about to be disconnected,
     * and there is no one left to tell.
     */
    private void reject(ClientLink to, String clientEventId, String reason) {
        if (to == null) return;      // host-authored work has no one to answer to
        Message.Rejected r = new Message.Rejected();
        r.clientEventId = clientEventId;
        r.reason = reason;
        to.enqueue(r);
    }

    /**
     * Refusal kinds a client can act on. The prose changes; these don't.
     *
     * DIFFERENT_MARKET and FORK both mean "your history can't join mine", but the
     * remedies differ: one is "you're in the wrong place", the other is "your branch
     * diverged". Telling them apart is the whole point of market identity.
     */
    public static final class Refusal {
        public static final String DIFFERENT_MARKET = "different_market";
        public static final String FORK             = "fork";
        public static final String AHEAD            = "ahead";
        /** History, but no MarketCreated — a log written before market identity. */
        public static final String NO_IDENTITY      = "no_identity";
        /** Right identity, wrong signing key — usually a moved or lost key file. */
        public static final String KEY_MISMATCH     = "key_mismatch";
        /**
         * Turned away by this server's admission policy.
         *
         * Nothing is wrong with the client's history, so unlike every other code here
         * there is no remedy it can carry out — the answer is with whoever runs the
         * server. Safe to add without a protocol bump: code is an existing field and a
         * client that does not know this value falls back to showing the reason text.
         */
        public static final String NOT_ADMITTED     = "not_admitted";
        private Refusal() {}
    }

    private void sendError(MessageChannel channel, String reason) {
        sendError(channel, null, reason);
    }

    private void sendError(MessageChannel channel, String code, String reason) {
        Message.Error err = new Message.Error();
        err.reason = reason;
        err.code = code;
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

    private static final String USAGE =
            "usage: HostServer [--config <file>] [--write-config] [--creator-key <file>]\n"
            + "                  [--port N] [--log <file>] [--name <host name>]\n"
            + "                  [--market <market name>] [--bind <address>]\n"
            + "\n"
            + "  --config        config file to read (default server-config.json)\n"
            + "  --write-config  write the effective config back out and exit\n"
            + "  --creator-key   key file that signs genesis when bootstrapping a market;\n"
            + "                  requires creatorUserId in the config. The server keeps its\n"
            + "                  own key for grants — this one need not stay on the box.\n"
            + "\n"
            + "Flags override the config file. Everything has a default.";

    public static void main(String[] args) throws Exception {
        Path configFile = Paths.get("server-config.json");
        Path creatorKeyFile = null;
        boolean writeConfig = false;

        // Read --config first: the rest override what it loaded, so it has to be known
        // before any of them are applied.
        for (int i = 0; i < args.length - 1; i++) {
            if ("--config".equals(args[i])) configFile = Paths.get(args[i + 1]);
        }

        // Said out loud, because a server quietly running on defaults is how an operator
        // ends up enforcing a policy they did not choose. Absent is a legitimate state —
        // a first run has no file — but it is not one to discover from the outside when
        // somebody who should have been refused walks in.
        if (!Files.exists(configFile)) {
            System.out.println("[host] no " + configFile + " — starting on defaults:"
                    + " open admission, no deposit cap, no world checks");
        }

        ServerConfig cfg;
        try {
            cfg = ServerConfig.load(configFile);
        } catch (IOException e) {
            // Existing-but-unreadable. Defaults would be a guess at policy that mints
            // money, so this stops instead — see ServerConfig.load.
            System.err.println("[host] " + e.getMessage());
            System.exit(2);
            return;
        }

        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--config":       i++; break;   // already handled
                    case "--write-config": writeConfig = true; break;
                    case "--creator-key":  creatorKeyFile = Paths.get(args[++i]); break;
                    case "--port":         cfg.port = Integer.parseInt(args[++i]); break;
                    case "--log":          cfg.logFile = args[++i]; break;
                    case "--name":         cfg.hostName = args[++i]; break;
                    case "--market":       cfg.marketName = args[++i]; break;
                    case "--bind":         cfg.bindAddress = args[++i]; break;
                    case "--help":
                    case "-h":
                        System.out.println(USAGE);
                        return;
                    default:
                        System.err.println("unknown argument: " + args[i]);
                        System.err.println(USAGE);
                        System.exit(2);
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            System.err.println("missing value for the last argument");
            System.err.println(USAGE);
            System.exit(2);
        } catch (NumberFormatException e) {
            System.err.println("expected a number: " + e.getMessage());
            System.exit(2);
        }

        String bad = cfg.problem();
        if (bad != null) {
            System.err.println("[host] " + bad);
            System.exit(2);
        }

        if (writeConfig) {
            cfg.save(configFile);
            System.out.println("[host] wrote " + configFile.toAbsolutePath());
            return;
        }

        // Reached only from the standalone launcher, so this is what "dedicated" means:
        // started from a command line rather than from inside somebody's game. Forced
        // rather than read, so a config copied from a client cannot claim otherwise.
        cfg.dedicated = true;

        Path logFile = Paths.get(cfg.logFile);
        System.out.println("[host] log file: " + logFile.toAbsolutePath());

        PlayerKeys keys = PlayerKeys.loadOrCreate(logFile.resolveSibling("server-identity.key"));
        PeerCache peers = new PeerCache(logFile.resolveSibling("server-peers.json"));

        // Remembered once rather than defaulted every start. The old fallback was a
        // hardcoded UUID, so two dedicated servers that never had one configured were
        // literally the same participant — fine while there was one of them, wrong the
        // moment any client met both.
        if (cfg.hostUserId == null || cfg.hostUserId.trim().isEmpty()) {
            cfg.hostUserId = UUID.randomUUID().toString();
            cfg.save(configFile);
            System.out.println("[host] assigned this server the identity " + cfg.hostUserId);
        }

        // A dedicated server starting on an empty log is deliberately creating a
        // market — unlike a player clicking Host, there is no ambiguity about intent.
        EventLog log = new EventLog(logFile);
        if (log.lastSeq() == 0) {
            try {
                bootstrap(log, cfg, keys, creatorKeyFile);
            } catch (IOException e) {
                // An operator misconfiguration, not a crash. A stack trace here buries
                // the one line that says what to change.
                System.err.println("[host] " + e.getMessage());
                System.exit(2);
            }
        } else if (creatorKeyFile != null) {
            System.out.println("[host] --creator-key ignored: this log already holds a market");
        }

        try {
            new HostServer(cfg, logFile, keys, peers).start();
        } catch (java.net.BindException e) {
            // The in-game path has named this fix since the two-client dev setup made it
            // routine; the launcher was still dumping a stack trace at an operator who
            // has no Port field to look at. Same cause, overwhelmingly: something else
            // is already on the port, usually a client still hosting from inside a game.
            System.err.println("[host] port " + cfg.port + " is already in use.");
            System.err.println("[host] Something else is listening there — commonly a"
                    + " Minecraft client still hosting from the Network tab, or an"
                    + " earlier server that did not exit.");
            System.err.println("[host] Either stop that one, or start this server on a"
                    + " different port: --port " + (cfg.port + 1));
            System.exit(2);
        } catch (IOException e) {
            // Anything else that stops it listening: a bind address that is not on this
            // machine, a permission problem on a low port, an unreadable log.
            System.err.println("[host] could not start: " + e.getMessage());
            System.exit(2);
        }
    }

    /**
     * Writes genesis, signed by the operator's key when one is supplied.
     *
     * Creator-gating is currently unused — WelcomeGrant checks only its target, never
     * its author, which is what lets hosting rotate — so the creator identity is free
     * to become the rule for who may set policy later. Recording the operator rather
     * than the box is what makes that worth having: compromising the server then gets
     * grant-signing and denial of service, not authority over the market.
     */
    private static void bootstrap(EventLog log, ServerConfig cfg, PlayerKeys serverKeys,
                                  Path creatorKeyFile) throws Exception {
        String name = cfg.marketName != null && !cfg.marketName.trim().isEmpty()
                ? cfg.marketName
                : cfg.hostName + "'s market";

        if (creatorKeyFile == null) {
            // The server is its own creator. Simplest case, and the only one that needs
            // nothing kept anywhere else.
            UUID creatorId = UUID.fromString(cfg.hostUserId);
            MarketBootstrap.createMarket(log, creatorId, name, serverKeys,
                    cfg.welcomeGrant);
            System.out.println("[host] created '" + name + "' owned by this server");
            return;
        }

        if (cfg.creatorUserId == null || cfg.creatorUserId.trim().isEmpty()) {
            throw new IOException("--creator-key needs creatorUserId set in the config file"
                    + " — the key signs the market into existence, but the identity is"
                    + " what gets recorded as owning it");
        }
        if (!Files.exists(creatorKeyFile)) {
            throw new IOException("no such key file: " + creatorKeyFile.toAbsolutePath());
        }

        PlayerKeys creator = PlayerKeys.loadOrCreate(creatorKeyFile);
        UUID creatorId = UUID.fromString(cfg.creatorUserId.trim());
        MarketBootstrap.createMarket(log, creatorId, name, creator, cfg.welcomeGrant);
        System.out.println("[host] created '" + name + "' owned by " + cfg.creatorUserId
                + " — that key is not needed on this machine again");
    }
}