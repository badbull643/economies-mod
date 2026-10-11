package io.github.badbull643.economiesmod.core.net;

import io.github.badbull643.economiesmod.core.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * The rotating (friend-group) mode, end to end, over real sockets: hosting handed from one
 * player to another, a fork from both hosting at once, the refusal that names it, the
 * orders it would cost, and rejoining by resetting and placing them again.
 *
 * The pieces each have their own tests — the split point in SplitPointTest, the arithmetic
 * of what a fork costs in MarketTests — but nothing walked the whole story the way a group
 * of friends would, through everything that has changed since: compact storage, packed
 * sync, the join that now registers a link before sending history, profit and loss.
 */
public class RotationTest {

    private static int failures = 0;
    private static int checksRun = 0;

    private static void check(String label, boolean ok) {
        checksRun++;
        if (!ok) failures++;
        System.out.println((ok ? "    ok   " : "    FAIL ") + label);
    }

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000b0b");
    private static final String IRON = "minecraft:iron_ingot";
    private static final String WOOD = "minecraft:oak_log";

    private static Path dir;
    private static PlayerKeys aliceKeys, bobKeys;

    public static void main(String[] args) throws Exception {
        dir = Paths.get("build", "test-scratch", "rotation");
        deleteTree(dir);
        Files.createDirectories(dir);
        aliceKeys = PlayerKeys.generate();
        bobKeys = PlayerKeys.generate();

        Path aliceLog = dir.resolve("alice.jsonl");
        Path bobLog = dir.resolve("bob.jsonl");
        MarketBootstrap.createMarket(new EventLog(aliceLog), ALICE, "rotation test", aliceKeys, 1_000);

        System.out.println("  [T1: Alice hosts, Bob joins, they trade]");
        HostServer aliceHost = host(aliceLog, ALICE, aliceKeys);
        MarketClient aliceSelf = client(ALICE, aliceKeys, dir.resolve("alice-self-1.jsonl"), port(aliceHost));
        MarketClient bob = client(BOB, bobKeys, bobLog, port(aliceHost));
        check("Bob joins and is granted", await(() -> bob.state().wallets().getBalance(BOB) == 1_000));
        aliceSelf.propose(depositAndList(ALICE, IRON, 20, 5));
        check("Alice lists 20 iron at 5", await(() -> restingOf(aliceHost.state(), ALICE) == 1));
        bob.propose(place(BOB, IRON, true, 5, 8));
        check("Bob buys 8 of them", await(() -> bob.state().itemBalances().getBalance(BOB, IRON) == 8));
        check("which is profit for Alice (deposited goods cost nothing)",
                await(() -> aliceHost.state().realizedPnl(ALICE) == 40));
        long beforeHandover = aliceHost.headSeq();
        await(() -> bob.lastSeq() == beforeHandover);
        aliceSelf.disconnect();
        bob.disconnect();
        aliceHost.stop();

        System.out.println("  [T2: hosting passes to Bob, and Alice catches up from him]");
        HostServer bobHost = host(bobLog, BOB, bobKeys);
        MarketClient bobSelf = client(BOB, bobKeys, dir.resolve("bob-self-2.jsonl"), port(bobHost));
        bobSelf.propose(place(BOB, WOOD, true, 2, 10));   // something new while Bob hosts
        check("Bob, hosting, trades on", await(() -> restingOf(bobHost.state(), BOB) == 1));
        MarketClient alice = client(ALICE, aliceKeys, aliceLog, port(bobHost));
        check("Alice's own copy catches up to Bob's", await(() -> alice.lastSeq() == bobHost.headSeq()));
        check("and agrees with it to the last hash",
                new EventLog(aliceLog).lastHash().equals(new EventLog(bobLog).lastHash()));
        long shared = bobHost.headSeq();
        alice.disconnect();
        bobSelf.disconnect();
        bobHost.stop();

        System.out.println("  [T3: both host at once, and the market forks]");
        HostServer aliceAlone = host(aliceLog, ALICE, aliceKeys);
        MarketClient aliceSelf2 = client(ALICE, aliceKeys, dir.resolve("alice-self-3.jsonl"), port(aliceAlone));
        aliceSelf2.propose(depositAndList(ALICE, WOOD, 5, 4));
        check("Alice trades on her copy", await(() -> aliceAlone.headSeq() > shared));
        aliceSelf2.disconnect();

        HostServer bobAlone = new HostServer(friendGroupConfig(BOB), bobLog, bobKeys, peers("bob-peers-4"));
        startHost(bobAlone);
        MarketClient bobSelf2 = client(BOB, bobKeys, dir.resolve("bob-self-4.jsonl"), port(bobAlone));
        bobSelf2.propose(place(BOB, IRON, true, 4, 6));
        bobSelf2.propose(depositAndList(BOB, IRON, 3, 9));
        check("and Bob on his, two orders resting there",
                await(() -> restingOf(bobAlone.state(), BOB) == 3));   // the wood bid from T2 and these two
        long bobHead = bobAlone.headSeq();
        await(() -> bobSelf2.lastSeq() == bobHead);
        bobSelf2.disconnect();
        bobAlone.stop();

        System.out.println("  [T4: Bob is turned away for the fork, and told what it costs]");
        MarketClient bobBack = new MarketClient(BOB, "Bob", bobKeys, new EventLog(bobLog), true, peers("bob-peers-5"), 0);
        // Bob's branch is the longer one here, so the host's first answer is "you are ahead
        // of me", with its own head. The game then asks whether that head is on Bob's
        // branch, as it would be if Alice were merely behind; it is not, which is a fork.
        // Had Bob's branch been the shorter, the host would have said FORK outright.
        String code = null, hostHash = null;
        long hostSeq = -1;
        try {
            bobBack.connect("127.0.0.1", port(aliceAlone));
        } catch (MarketClient.Refused r) {
            code = r.code;
            hostSeq = r.hostSeq;
            hostHash = r.hostHash;
        } catch (IOException e) {
            System.out.println("    (" + e.getMessage() + ")");
        }
        boolean fork = HostServer.Refusal.FORK.equals(code)
                || (HostServer.Refusal.AHEAD.equals(code) && hostHash != null
                    && !hostHash.equals(hashAt(new EventLog(bobLog), hostSeq)));
        check("refused, and the host's head is not on Bob's branch: a fork, not a host that is behind", fork);
        long split = MarketClient.findSplitPoint("127.0.0.1", port(aliceAlone), new EventLog(bobLog));
        check("the split is found where they parted (seq " + shared + ")", split == shared);
        List<Order> lost = BranchDiff.ordersOnlyAfter(new EventLog(bobLog), split, BOB);
        check("the orders it would cost are exactly Bob's two since the split",
                lost.size() == 2 && lost.stream().anyMatch(o -> o.isBid() && o.value() == 4 && o.volume() == 6)
                        && lost.stream().anyMatch(o -> !o.isBid() && o.value() == 9 && o.volume() == 3));

        System.out.println("  [T5: Bob resets, rejoins, and places them again]");
        MarketClient bobFresh = client(BOB, bobKeys, dir.resolve("bob-reset.jsonl"), port(aliceAlone));
        check("a fresh copy joins Alice's market", await(() -> bobFresh.lastSeq() == aliceAlone.headSeq()));
        check("keeping everything from before the split: his 8 iron and the wood bid",
                bobFresh.state().itemBalances().getBalance(BOB, IRON) == 8 && restingOf(bobFresh.state(), BOB) == 1);
        for (Order o : lost) {
            if (o.isBid()) bobFresh.propose(place(BOB, o.itemID(), true, o.value(), o.volume()));
            else bobFresh.propose(depositAndList(BOB, o.itemID(), o.volume(), o.value()));
        }
        check("both are back on Alice's host", await(() -> restingOf(aliceAlone.state(), BOB) == 3));

        MarketArchive.Verified verified = null;
        try {
            verified = MarketArchive.verifyLines(new EventLog(aliceLog).rawLinesFrom(1));
        } catch (IOException e) {
            System.out.println("    (" + e.getMessage() + ")");
        }
        check("and Alice's log verifies, every hash and signature", verified != null);
        bobFresh.disconnect();
        aliceAlone.stop();

        System.out.println();
        System.out.println(failures == 0 ? "ALL " + checksRun + " CHECKS PASSED"
                : failures + " OF " + checksRun + " CHECKS FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ─────────── helpers ───────────

    private static ServerConfig friendGroupConfig(UUID hostId) throws IOException {
        ServerConfig cfg = ServerConfig.friendGroup(TestPorts.free());
        cfg.hostName = hostId.equals(ALICE) ? "Alice" : "Bob";
        cfg.hostUserId = hostId.toString();
        return cfg;
    }

    private static int peerFiles = 0;

    private static PeerCache peers(String name) {
        return new PeerCache(dir.resolve(name + "-" + (peerFiles++) + ".json"));
    }

    private static HostServer host(Path log, UUID hostId, PlayerKeys keys) throws Exception {
        HostServer h = new HostServer(friendGroupConfig(hostId), log, keys, peers("host"));
        startHost(h);
        return h;
    }

    private static void startHost(HostServer h) throws Exception {
        Thread t = new Thread(() -> {
            try { h.start(); } catch (IOException e) { /* stopped */ }
        }, "rotation-host");
        t.setDaemon(true);
        t.start();
        IOException bindError = h.awaitBound(5000);
        if (bindError != null) throw bindError;
    }

    private static int port(HostServer h) {
        return h.boundPort();
    }

    private static MarketClient client(UUID who, PlayerKeys keys, Path log, int port) throws Exception {
        MarketClient c = new MarketClient(who, who.equals(ALICE) ? "Alice" : "Bob", keys,
                new EventLog(log), true, peers("client"), 0);
        c.connect("127.0.0.1", port);
        return c;
    }

    private static Event.PlaceOrder place(UUID who, String item, boolean bid, long price, long volume) {
        Event.PlaceOrder p = new Event.PlaceOrder();
        p.userId = who;
        p.itemId = item;
        p.isBid = bid;
        p.price = price;
        p.volume = volume;
        p.timestamp = System.currentTimeMillis();
        return p;
    }

    private static Event.DepositAndList depositAndList(UUID who, String item, long qty, long price) {
        Event.DepositAndList d = new Event.DepositAndList();
        d.userId = who;
        d.itemId = item;
        d.quantity = qty;
        d.price = price;
        d.timestamp = System.currentTimeMillis();
        return d;
    }

    /** The hash of a log's entry at a sequence number, or null. */
    private static String hashAt(EventLog log, long seq) throws IOException {
        final String[] found = {null};
        log.forEach(seq, se -> {
            if (se.seq == seq) found[0] = se.hash;
            return false;
        });
        return found[0];
    }

    private static int restingOf(MarketState s, UUID who) {
        return s.restingOrdersOf(who).size();
    }

    private static boolean await(BooleanSupplier cond) throws InterruptedException {
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return cond.getAsBoolean();
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> {
                try { Files.delete(f); } catch (IOException ignored) {}
            });
        }
    }
}
