package io.github.badbull643.economiesmod.core.net;

import io.github.badbull643.economiesmod.core.Event;
import io.github.badbull643.economiesmod.core.EventCanonical;
import io.github.badbull643.economiesmod.core.EventLog;
import io.github.badbull643.economiesmod.core.MarketBootstrap;
import io.github.badbull643.economiesmod.core.PeerCache;
import io.github.badbull643.economiesmod.core.PlayerKeys;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A client that misses a broadcast must notice, and must stay connected while it does.
 *
 * The failure this guards is quiet and total. applyLine used to shrug at an out-of-order
 * sequence number and carry on, but appliedSeq only ever advances on a contiguous event —
 * so one dropped broadcast meant every subsequent event was also out of order, and the
 * client applied nothing for the rest of the session while still reporting itself
 * connected. No error, no disconnect, a frozen order book, and orders that silently never
 * matched.
 *
 * Recovery is a reconnect rather than a new message type: there is no "send me events
 * from N" in the protocol (CatchUp is the other direction, a client offering events to a
 * host that is behind), but the handshake already sends our lastSeq and gets everything
 * after it. P4 pins that down, since the whole fix depends on it.
 *
 * The three decisions are tested against a hand-built host rather than a real HostServer,
 * because a correct host never produces any of them — the point is what the client does
 * when one does.
 *
 * Not part of MarketTests: that suite is pure-core and instant, and this binds sockets
 * and signs RSA events.
 */
public class GapRecoveryTest {

    private static int failures = 0;
    private static int checksRun = 0;

    private static void check(String label, long actual, long expected) {
        checksRun++;
        boolean ok = actual == expected;
        if (!ok) failures++;
        System.out.println((ok ? "    ok   " : "    FAIL ") + label
                + " — expected " + expected + ", got " + actual);
    }

    private static final UUID HOST   = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID JOINER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final String IRON = "minecraft:iron_ingot";

    private static Path dir;
    private static UUID marketId;
    /** Where the built history ends, so assertions do not restate genesis's size. */
    private static long head;
    private static PlayerKeys hostKeys;
    private static PlayerKeys joinerKeys;

    /** The market's raw log lines, 1-based: line(1) is the genesis event. */
    private static List<String> lines;

    private static String line(int seq) { return lines.get(seq - 1); }

    public static void main(String[] args) throws Exception {
        dir = Paths.get("build", "test-scratch");
        Files.createDirectories(dir);
        for (String f : new String[]{"gap-host.jsonl", "gap-client.jsonl",
                "gap-host-peers.json", "gap-client-peers.json"}) {
            Files.deleteIfExists(dir.resolve(f));
        }

        buildHistory();

        gapIsFlagged();
        duplicateIsTolerated();
        gapInsideSyncIsRefused();
        reconnectBackfills();

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL " + checksRun + " CHECKS PASSED");
        } else {
            System.out.println(failures + " of " + checksRun + " checks FAILED");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * Six events: genesis, the joiner's key, then four deposits it signed itself.
     *
     * The deposits are the joiner's own so that every line replayed out of order still
     * passes verification — the gap check has to be what stops them, not the signature
     * check, or the test would pass for the wrong reason.
     */
    private static void buildHistory() throws Exception {
        hostKeys = PlayerKeys.generate();
        joinerKeys = PlayerKeys.generate();

        Path hostLog = dir.resolve("gap-host.jsonl");
        EventLog log = new EventLog(hostLog);
        MarketBootstrap.createMarket(log, HOST, "gap test market", hostKeys);
        marketId = log.marketId();
        long afterGenesis = log.lastSeq();

        Event.KeyRegistered kr = new Event.KeyRegistered();
        kr.userId = JOINER;
        kr.marketId = marketId;
        kr.publicKey = joinerKeys.publicKeyString();
        kr.timestamp = System.currentTimeMillis();
        log.append(kr, joinerKeys.sign(EventCanonical.canonicalPayload(kr)));

        for (int i = 0; i < 4; i++) {
            Event.Deposit dep = new Event.Deposit();
            dep.userId = JOINER;
            dep.marketId = marketId;
            dep.itemId = IRON;
            dep.quantity = 5;
            dep.timestamp = System.currentTimeMillis();
            log.append(dep, joinerKeys.sign(EventCanonical.canonicalPayload(dep)));
        }

        lines = Files.readAllLines(hostLog, StandardCharsets.UTF_8);
        // Five events on top of whatever genesis writes, and remembered rather than
        // written out again below — genesis is free to grow, the history built here is
        // not.
        head = log.lastSeq();
        check("history built", head - afterGenesis, 5);
    }

    /**
     * P1 — a broadcast that skips a sequence number is noticed, and the connection
     * survives long enough for the owner to act on it.
     *
     * Without the fix needsResync() stays false here: the old branch logged the gap to
     * stderr and returned true, which is indistinguishable from having handled it.
     */
    private static void gapIsFlagged() throws Exception {
        System.out.println("  [P1: a missed broadcast is flagged, not shrugged at]");

        try (FakeHost host = new FakeHost(syncTo(3))) {
            MarketClient client = freshClient(host.port(), "gap-client.jsonl");
            check("synced to the host's head", client.lastSeq(), 3);
            check("a clean sync needs no resync", client.needsResync() ? 1 : 0, 0);

            // Skip 4. A host that dropped a frame, or a client that missed one.
            host.broadcast(line(5));
            awaitResync(client);

            check("the gap is flagged", client.needsResync() ? 1 : 0, 1);
            check("it names the sequence that exposed it", client.gapAt(), 5);
            check("the out-of-order event was not applied", client.lastSeq(), 3);

            // The recovery is a reconnect performed by whoever owns this client, on the
            // game thread. applyLine runs on the reader thread and, during a sync, on the
            // connecting thread — tearing the channel down from either would strand the
            // other, so it must report and leave the connection standing.
            check("the connection is still up", client.isConnected() ? 1 : 0, 1);

            client.disconnect();
        }
    }

    /**
     * P2 — an event we already have is not a gap.
     *
     * Reconnecting asks for everything after our lastSeq, so a broadcast that was already
     * in flight arrives a second time as a matter of course. Treating that as a fault
     * would make every successful recovery trigger another one.
     */
    private static void duplicateIsTolerated() throws Exception {
        System.out.println("  [P2: an event we already have is not a gap]");

        try (FakeHost host = new FakeHost(syncTo(3))) {
            MarketClient client = freshClient(host.port(), "gap-client.jsonl");
            check("synced to the host's head", client.lastSeq(), 3);

            host.broadcast(line(2));
            host.broadcast(line(3));
            Thread.sleep(300);

            check("a duplicate triggers no resync", client.needsResync() ? 1 : 0, 0);
            check("and does not move the head", client.lastSeq(), 3);
            check("the connection is still up", client.isConnected() ? 1 : 0, 1);

            client.disconnect();
        }
    }

    /**
     * P3 — a hole in the sync stream itself is a refusal, not something to retry.
     *
     * The host built that stream from the lastSeq we sent it. Reconnecting would ask the
     * same host the same question and get the same answer, so retrying is a loop rather
     * than a recovery, and joining anyway would adopt a history with a hole in it.
     */
    private static void gapInsideSyncIsRefused() throws Exception {
        System.out.println("  [P3: a hole in the sync stream is refused]");

        List<String> holed = new ArrayList<>(syncTo(3));
        holed.add(line(5));          // 1, 2, 3, 5

        try (FakeHost host = new FakeHost(holed)) {
            String refusal = null;
            try {
                freshClient(host.port(), "gap-client.jsonl");
            } catch (IOException e) {
                refusal = e.getMessage();
            }
            check("connecting was refused", refusal != null ? 1 : 0, 1);
            check("and said why", refusal != null && refusal.contains("failed verification")
                    ? 1 : 0, 1);
        }
    }

    /**
     * P4 — reconnecting backfills from wherever we left off.
     *
     * This is the whole recovery mechanism: the holder's resync() does nothing more than
     * reconnect, so if the handshake did not backfill, flagging the gap would achieve
     * nothing. Against a real HostServer, since this is the ordinary join path.
     */
    private static void reconnectBackfills() throws Exception {
        System.out.println("  [P4: reconnecting backfills from our own lastSeq]");

        // A client that stopped at 3 while the market went on to 6.
        Path clientLog = dir.resolve("gap-client.jsonl");
        Files.deleteIfExists(clientLog);
        EventLog behind = new EventLog(clientLog);
        for (int seq = 1; seq <= 3; seq++) behind.appendRaw(line(seq));
        check("client starts behind", behind.lastSeq(), 3);

        int port = freePort();
        HostServer host = new HostServer(port, dir.resolve("gap-host.jsonl"),
                "gaphost", HOST.toString(), hostKeys,
                new PeerCache(dir.resolve("gap-host-peers.json")), 0L);
        Thread t = new Thread(() -> {
            try { host.start(); } catch (IOException e) { /* stopped */ }
        }, "gap-test-host");
        t.setDaemon(true);
        t.start();

        IOException bindError = host.awaitBound(5000);
        if (bindError != null) throw bindError;

        try {
            MarketClient client = new MarketClient(JOINER, "Joiner", joinerKeys,
                    new EventLog(clientLog), true,
                    new PeerCache(dir.resolve("gap-client-peers.json")), 0);
            client.connect("127.0.0.1", port);

            check("caught up to the market's head", client.lastSeq(), head);
            check("with no gap reported", client.needsResync() ? 1 : 0, 0);

            client.disconnect();
        } finally {
            host.stop();
        }
    }

    // ─────────── helpers ───────────

    private static List<String> syncTo(int seq) {
        return new ArrayList<>(lines.subList(0, seq));
    }

    private static MarketClient freshClient(int port, String logName) throws Exception {
        Path p = dir.resolve(logName);
        Files.deleteIfExists(p);
        MarketClient c = new MarketClient(JOINER, "Joiner", joinerKeys,
                new EventLog(p), true,
                new PeerCache(dir.resolve("gap-client-peers.json")), 0);
        c.connect("127.0.0.1", port);
        return c;
    }

    /** The flag is set on the reader thread, so give it a moment rather than a sleep. */
    private static void awaitResync(MarketClient client) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !client.needsResync()) {
            Thread.sleep(25);
        }
    }

    /**
     * A host that says exactly what the test tells it to.
     *
     * A real HostServer cannot produce any of the streams P1–P3 need — it is the
     * component that guarantees they never happen — so the client's behaviour when they
     * do has to be driven from the other side of a real socket.
     */
    private static class FakeHost implements AutoCloseable {
        private final ServerSocket server;
        private final CountDownLatch synced = new CountDownLatch(1);
        private volatile MessageChannel channel;
        private volatile Socket socket;

        FakeHost(List<String> syncLines) throws IOException {
            server = new ServerSocket(0);
            Thread t = new Thread(() -> {
                try {
                    socket = server.accept();
                    MessageChannel ch = new MessageChannel(socket);
                    ch.receive();               // Hello; nothing here validates it

                    Message.Sync sync = new Message.Sync();
                    sync.logLines = syncLines;
                    sync.complete = true;
                    sync.knownPeers = new ArrayList<>();
                    sync.hostUserId = HOST.toString();
                    sync.hostName = "fakehost";
                    sync.hostPort = 0;
                    sync.hostPublicKey = hostKeys.publicKeyString();
                    sync.marketId = marketId.toString();
                    sync.marketName = "gap test market";
                    ch.send(sync);

                    channel = ch;
                    synced.countDown();

                    // Drain whatever the client proposes. Nothing is answered: these
                    // tests are about what arrives, not what is sent.
                    while (ch.receive() != null) { /* discard */ }
                } catch (Exception e) {
                    synced.countDown();
                }
            }, "fake-host");
            t.setDaemon(true);
            t.start();
        }

        int port() { return server.getLocalPort(); }

        void broadcast(String logLine) throws InterruptedException {
            synced.await(5, TimeUnit.SECONDS);
            Message.Accepted a = new Message.Accepted();
            a.logLine = logLine;
            channel.send(a);
        }

        @Override
        public void close() throws IOException {
            if (socket != null) try { socket.close(); } catch (IOException ignored) {}
            server.close();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
