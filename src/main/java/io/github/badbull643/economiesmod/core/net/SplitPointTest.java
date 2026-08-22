package io.github.badbull643.economiesmod.core.net;

import io.github.badbull643.economiesmod.core.Event;
import io.github.badbull643.economiesmod.core.EventCanonical;
import io.github.badbull643.economiesmod.core.EventLog;
import io.github.badbull643.economiesmod.core.MarketBootstrap;
import io.github.badbull643.economiesmod.core.PeerCache;
import io.github.badbull643.economiesmod.core.PlayerKeys;
import io.github.badbull643.economiesmod.core.ServerConfig;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finding where two branches of one market parted.
 *
 * The protocol could say *that* two chains disagree and never *where*. A FORK refusal
 * compares one hash at one point, and the split is somewhere at or below it — so
 * everything a fork could offer somebody afterwards had nothing to stand on. The
 * re-place checklist was handed the client's own head and correctly found nothing; the
 * banner could only say "you disagree somewhere at or before here".
 *
 * This is the missing half. It is tested harder than its size suggests because a search
 * that returns a number is worse than one that returns nothing: a split point guessed
 * too high hides events that were only ever yours, and one guessed too low offers back
 * orders the host still holds, which is how a reset creates duplicates.
 *
 * Not part of MarketTests: that suite is pure-core and instant, and this binds sockets.
 */
public class SplitPointTest {

    private static int failures = 0;
    private static int checksRun = 0;

    private static void check(String label, long actual, long expected) {
        checksRun++;
        boolean ok = actual == expected;
        if (!ok) failures++;
        System.out.println((ok ? "    ok   " : "    FAIL ") + label
                + " — expected " + expected + ", got " + actual);
    }

    private static final UUID HOST = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final String IRON = "minecraft:iron_ingot";

    private static Path dir;
    private static PlayerKeys keys;

    public static void main(String[] args) throws Exception {
        dir = Paths.get("build", "test-scratch");
        Files.createDirectories(dir);
        keys = PlayerKeys.generate();

        spreadCoversItsRange();
        findsTheSplit();

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL " + checksRun + " CHECKS PASSED");
        } else {
            System.out.println(failures + " of " + checksRun + " checks FAILED");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * S1: the probe spread, on its own.
     *
     * Cheap to get subtly wrong and expensive to debug through a socket. The properties
     * that matter: it never proposes the floor (already known to agree), it always
     * proposes the ceiling (the overwhelmingly common case is a disagreement at the top,
     * settled in one probe), and it never returns duplicates — a repeated probe wastes a
     * round and can stall the bracket.
     */
    private static void spreadCoversItsRange() {
        System.out.println("  [S1: the probe spread]");

        List<Long> wide = MarketClient.spread(0, 1000, 10);
        check("asks for what it was told to", wide.size(), 10);
        check("never the floor, which is already agreed",
                wide.contains(0L) ? 1 : 0, 0);
        check("always the ceiling", wide.get(wide.size() - 1), 1000);

        long previous = 0;
        int outOfOrder = 0;
        int duplicated = 0;
        for (long s : wide) {
            if (s <= previous) {
                if (s == previous) duplicated++; else outOfOrder++;
            }
            previous = s;
        }
        check("in order", outOfOrder, 0);
        check("without repeats", duplicated, 0);

        // A range smaller than the probe budget is enumerated exactly, or the last few
        // events before the split could never be probed at all.
        List<Long> tight = MarketClient.spread(40, 44, 24);
        check("a short range is enumerated", tight.size(), 4);
        check("starting one above the floor", tight.get(0), 41L);
        check("and ending at the ceiling", tight.get(3), 44L);

        check("an empty range asks nothing", MarketClient.spread(7, 7, 24).size(), 0);
        check("and a backwards one asks nothing",
                MarketClient.spread(9, 4, 24).size(), 0);
    }

    /**
     * S2: two branches of one market, over a real socket.
     *
     * Both sides share a genesis and a prefix, then each appends events the other never
     * saw. The answer has to be the last event they both hold — not the head of either,
     * and not the first disagreement.
     */
    private static void findsTheSplit() throws Exception {
        System.out.println("  [S2: locating a real divergence]");

        Path hostLog = dir.resolve("split-host.jsonl");
        Path ourLog = dir.resolve("split-ours.jsonl");
        Files.deleteIfExists(hostLog);
        Files.deleteIfExists(ourLog);

        EventLog theirs = new EventLog(hostLog);
        MarketBootstrap.createMarket(theirs, HOST, "split market", keys);
        UUID marketId = theirs.marketId();

        // A shared prefix, written once and copied, so both chains are byte-identical
        // up to the split — which is what having a split point means.
        for (int i = 0; i < 40; i++) deposit(theirs, marketId, 1);
        long shared = theirs.lastSeq();
        Files.copy(hostLog, ourLog);

        EventLog ours = new EventLog(ourLog);
        check("both start from the same place", ours.lastSeq(), shared);

        // Now they part. Different counts on each side, so neither head is the answer
        // and a search that just returned "the shorter head" would look right by luck.
        for (int i = 0; i < 25; i++) deposit(theirs, marketId, 7);
        for (int i = 0; i < 11; i++) deposit(ours, marketId, 9);

        check("their branch grew", theirs.lastSeq(), shared + 25);
        check("ours grew differently", ours.lastSeq(), shared + 11);

        int port = freePort();
        HostServer host = serve(hostLog, port);
        try {
            check("the split is the last shared event",
                    MarketClient.findSplitPoint("127.0.0.1", port, ours), shared);

            // A client that merely trails the host has no split at all: its head is the
            // answer, and reporting anything lower would offer back events both hold.
            Path behindLog = dir.resolve("split-behind.jsonl");
            Files.deleteIfExists(behindLog);
            Files.copy(hostLog, behindLog);
            EventLog behind = new EventLog(behindLog);
            check("a client that only trails agrees to its own head",
                    MarketClient.findSplitPoint("127.0.0.1", port, behind),
                    behind.lastSeq());

            // And one that extends the host agrees up to the host's head, not its own.
            for (int i = 0; i < 5; i++) deposit(behind, marketId, 3);
            // headSeqOnDisk, not lastSeq: the host has appended through its own handle
            // since ours was built — it issues a welcome grant on startup — so this
            // instance's cached answer is one short. The same staleness that has now
            // produced a client applying its own grant twice and two tests that passed
            // by never changing.
            check("a client that extends agrees to the host's head",
                    MarketClient.findSplitPoint("127.0.0.1", port, behind),
                    theirs.headSeqOnDisk());

            // Sharing nothing but genesis. Zero is a real answer and must not read as
            // "could not find out", which is -1.
            Path strangerLog = dir.resolve("split-stranger.jsonl");
            Files.deleteIfExists(strangerLog);
            EventLog stranger = new EventLog(strangerLog);
            MarketBootstrap.createMarket(stranger, HOST, "a different market", keys);
            check("two chains sharing nothing split at zero",
                    MarketClient.findSplitPoint("127.0.0.1", port, stranger), 0);

            // An empty log has nothing to compare and says so without asking.
            Path emptyLog = dir.resolve("split-empty.jsonl");
            Files.deleteIfExists(emptyLog);
            check("an empty log needs no round trip",
                    MarketClient.findSplitPoint("127.0.0.1", port, new EventLog(emptyLog)), 0);
        } finally {
            host.stop();
        }

        // The batched read underneath it, since the whole reason for batching is that
        // the obvious implementation re-reads the log once per probe.
        Map<Long, String> some = ours.hashesAt(Arrays.asList(0L, 1L, shared, 99999L));
        check("genesis's prefix is the fixed point", "0".equals(some.get(0L)) ? 1 : 0, 1);
        check("a real seq comes back", some.get(shared) != null ? 1 : 0, 1);
        check("one we do not have is absent, not null-mapped",
                some.containsKey(99999L) ? 1 : 0, 0);
        check("and it agrees with the one-at-a-time reader",
                some.get(shared).equals(ours.hashAt(shared)) ? 1 : 0, 1);
    }

    private static void deposit(EventLog log, UUID marketId, long qty) throws Exception {
        Event.Deposit d = new Event.Deposit();
        d.userId = HOST;
        d.marketId = marketId;
        d.itemId = IRON;
        d.quantity = qty;
        d.clientEventId = UUID.randomUUID().toString();
        d.timestamp = System.currentTimeMillis();
        log.append(d, keys.sign(EventCanonical.canonicalPayload(d)));
    }

    private static HostServer serve(Path logFile, int port) throws Exception {
        ServerConfig cfg = ServerConfig.friendGroup(port);
        cfg.hostName = "split-host";
        cfg.hostUserId = HOST.toString();
        HostServer host = new HostServer(cfg, logFile, keys,
                new PeerCache(dir.resolve("split-peers.json")));
        Thread t = new Thread(() -> {
            try { host.start(); } catch (IOException e) { /* stopped */ }
        }, "split-test-host");
        t.setDaemon(true);
        t.start();
        IOException bindError = host.awaitBound(5000);
        if (bindError != null) throw bindError;
        return host;
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
