package io.github.badbull643.economiesmod.core.net;

import io.github.badbull643.economiesmod.core.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * The bot runner on a real dedicated server: console commands, both schedulers, production,
 * pause and resume, a restart, and a log that still verifies after bots on several threads
 * have raced each other to the sequencer.
 *
 * BotTest covers what every replica accepts. This covers what the server does with it, on
 * fast clocks: ticks of 10 ms rather than 50, and decisions every few ticks rather than every
 * few hundred.
 */
public class BotRunnerTest {

    private static int failures = 0;
    private static int checksRun = 0;

    private static void check(String label, boolean ok) {
        checksRun++;
        if (!ok) failures++;
        System.out.println((ok ? "    ok   " : "    FAIL ") + label);
    }

    private static final UUID HOST = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final String WOOD = "minecraft:oak_log";

    private static Path dir;
    private static final AtomicLong offset = new AtomicLong();

    public static void main(String[] args) throws Exception {
        dir = Paths.get("build", "test-scratch", "bot-runner");
        deleteTree(dir);
        Files.createDirectories(dir);
        Path logPath = dir.resolve("market.jsonl");

        PlayerKeys serverKeys = PlayerKeys.generate();
        EventLog log = new EventLog(logPath);
        MarketBootstrap.createMarket(log, HOST, "bot runner market", serverKeys, 100_000, 0, 0);
        MarketBootstrap.enableBots(log, HOST, serverKeys, serverKeys.publicKeyString());

        System.out.println("  [R1: the console before bots can run]");
        HostServer host = server(logPath, serverKeys);
        check("before the server starts it says to wait",
                say(host, "bot list").contains("not running yet"));
        {
            Path plain = dir.resolve("plain.jsonl");
            EventLog plainLog = new EventLog(plain);
            PlayerKeys k = PlayerKeys.generate();
            MarketBootstrap.createMarket(plainLog, HOST, "no bots here", k);
            HostServer noBots = server(plain, k);
            check("a market without bots says how they are switched on",
                    say(noBots, "bot spawn producer").contains("--bots"));
        }
        check("help is there either way", say(host, "bot help").contains("bot spawn"));

        start(host);
        check("the runner starts on a dedicated server whose market has bots",
                await(() -> host.botRunner() != null, 5000));

        System.out.println("  [R2: spawning from the console]");
        BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        BotConsole.handle(host, "bot spawn producer 2", replies::add);
        BotConsole.handle(host, "bot spawn maker 2 threads", replies::add);
        List<String> spawned = take(replies, 4, 5000);
        check("four spawned", spawned.size() == 4 && spawned.stream().allMatch(s -> s.startsWith("spawned")));
        MarketState state = host.state();
        check("two of each type", state.botCount(BotRules.PRODUCER) == 2 && state.botCount(BotRules.MARKET_MAKER) == 2);
        check("each with a key file beside the log", countKeys() == 4);
        String registry = new String(Files.readAllBytes(dir.resolve("bots").resolve("bots.json")), "UTF-8");
        check("and the scheduler it runs on recorded",
                count(registry, "\"threads\"") == 2 && count(registry, "\"tick\"") == 2);
        check("an unknown type is refused", say(host, "bot spawn banker").contains("no bot type"));
        check("so is a count past the cap", say(host, "bot spawn producer 9").contains("between 1 and"));
        check("names come from the market", say(host, "bot list").contains("Producer #2"));

        UUID p1 = host.botRunner().resolve("producer#1");
        UUID p2 = host.botRunner().resolve("producer#2");
        UUID m1 = host.botRunner().resolve("maker#1");
        UUID m2 = host.botRunner().resolve("Market maker #2".replace(" ", ""));
        check("bots can be named the way the console shows them", p1 != null && p2 != null && m1 != null && m2 != null);
        check("or by the start of their id", p1 != null && p1.equals(host.botRunner().resolve(p1.toString().substring(0, 8))));

        System.out.println("  [R3: both schedulers act]");
        check("the tick scheduler's producers put orders on the book",
                await(() -> orders(host, p1) > 0 && orders(host, p2) > 0, 5000));
        check("the pool's market makers do too",
                await(() -> orders(host, m1) > 0 && orders(host, m2) > 0, 5000));

        System.out.println("  [R4: production refills what was sold, at the fixed rate]");
        // A player-sized buyer: the creator, with its welcome grant, wants 200 wood at 10.
        String why = asHost(host, serverKeys, buy(WOOD, 10, 200));
        check("(the creator bids for 200 wood)", why == null);
        check("producers sell into it",
                await(() -> holding(host, p1, WOOD) < BotRules.PRODUCTION_CEILING - BotRules.PRODUCTION_LOT, 5000));
        long before = holding(host, p1, WOOD);
        offset.addAndGet(BotRules.PRODUCTION_INTERVAL_MS * 3);
        check("once a few intervals are owed, the next production pass makes them",
                await(() -> holding(host, p1, WOOD) > before, 5000));
        check("and never past the ceiling",
                holding(host, p1, WOOD) <= BotRules.PRODUCTION_CEILING
                        && holding(host, p2, WOOD) <= BotRules.PRODUCTION_CEILING);

        System.out.println("  [R5: pause and resume from the console]");
        BotConsole.handle(host, "bot pause producer#1", replies::add);
        check("paused", take(replies, 1, 5000).stream().anyMatch(s -> s.startsWith("paused")));
        check("its orders are off the book", orders(host, p1) == 0);
        Thread.sleep(300);
        check("and stay off while it is paused", orders(host, p1) == 0);
        check("the list says so", say(host, "bot list").contains("paused"));
        BotConsole.handle(host, "bot pause producer#1", replies::add);
        check("pausing twice is refused", take(replies, 1, 5000).stream().anyMatch(s -> s.contains("already paused")));
        check("a bot nobody has is not found", say(host, "bot pause nobody").contains("no single bot"));
        BotConsole.handle(host, "bot resume producer#1", replies::add);
        check("resumed", take(replies, 1, 5000).stream().anyMatch(s -> s.startsWith("resumed")));
        check("and back on the book", await(() -> orders(host, p1) > 0, 5000));

        System.out.println("  [R6: a busy market's log still verifies]");
        Thread.sleep(1500);
        BotConsole.handle(host, "bot pause producer#2", replies::add);
        take(replies, 1, 5000);
        host.stop();
        Thread.sleep(300);
        List<String> lines = new EventLog(logPath).rawLinesFrom(1);
        MarketArchive.Verified verified = null;
        try {
            verified = MarketArchive.verifyLines(lines);
        } catch (IOException e) {
            System.out.println("    (" + e.getMessage() + ")");
        }
        check("every line chains and every signature checks out", verified != null);
        Map<UUID, Integer> batches = new HashMap<>();
        int[] overfull = {0}, grantsToBots = {0}, produced = {0};
        new EventLog(logPath).forEach(1, se -> {
            if (se.event instanceof Event.BotActions) {
                batches.merge(se.event.userId, 1, Integer::sum);
                if (((Event.BotActions) se.event).actions.size() > BotRules.MAX_ACTIONS_PER_BATCH) overfull[0]++;
            }
            if (se.event instanceof Event.BotProduced) produced[0]++;
            if (se.event instanceof Event.WelcomeGrant
                    && host.state().isBot(((Event.WelcomeGrant) se.event).targetUserId)) grantsToBots[0]++;
            return true;
        });
        check("every bot sent batches (" + batches.values() + ")",
                batches.keySet().containsAll(Arrays.asList(p1, p2, m1, m2)));
        check("none bigger than the cap", overfull[0] == 0);
        check("production is in the log", produced[0] > 0);
        check("no bot was given a welcome grant", grantsToBots[0] == 0);
        if (verified != null) {
            boolean same = true;
            for (UUID b : Arrays.asList(p1, p2, m1, m2)) {
                same &= verified.state.wallets().getBalance(b) == host.state().wallets().getBalance(b);
                for (String item : BotRules.ITEMS) {
                    same &= holding(verified.state, b, item) == holding(host.state(), b, item);
                }
                same &= verified.state.restingOrdersOf(b).size() == host.state().restingOrdersOf(b).size();
            }
            check("a replay of the log agrees with the live market about every bot", same);
        }
        // Bots may trade with each other, but a market maker stops buying once it holds twice
        // its starting stock. Without that, the first playtest's makers bought cobblestone off
        // the producers until each held about 570. One lot over is allowed: a bid placed just
        // under the limit can still fill.
        long limit = BotRules.startingItemsEach(BotRules.MARKET_MAKER)
                * BotBrain.PlaceholderMarketMaker.MAX_HOLDING_MULTIPLE + 8;
        boolean withinLimit = true;
        for (UUID m : Arrays.asList(m1, m2)) {
            for (String item : BotRules.ITEMS) withinLimit &= holding(host.state(), m, item) <= limit;
        }
        check("market makers stop buying at their holding limit", withinLimit);

        System.out.println("  [R7: a restart picks the bots back up]");
        HostServer again = server(logPath, serverKeys);
        int againPort = lastPort;
        start(again);
        check("the runner starts again", await(() -> again.botRunner() != null, 5000));
        String list = say(again, "bot list");
        check("market makers are back on the pool", count(list, "running (threads)") == 2);
        check("producer #1 is back on the tick", list.contains("running (tick)"));
        check("and producer #2 is still paused", count(list, "paused") == 1);
        BotConsole.handle(again, "bot spawn maker", replies::add);
        check("a bot spawned after the restart joins the rest",
                take(replies, 1, 5000).stream().anyMatch(s -> s.startsWith("spawned Market maker #3")));
        check("and acts", await(() -> orders(again, again.botRunner().resolve("maker#3")) > 0, 5000));

        System.out.println("  [R10: players joining a busy market miss nothing]");
        {
            // A player used to be added to the broadcast set only after its history was sent,
            // so an event written in between reached it neither way — the stress run hit that
            // on every join. The bots here go quiet once their quotes settle, so the creator
            // keeps the log busy while they join, the way a crowd of traders would.
            AtomicBoolean busy = new AtomicBoolean(true);
            Thread writer = new Thread(() -> {
                while (busy.get()) {
                    Event.Deposit d = new Event.Deposit();
                    d.userId = HOST;
                    d.itemId = WOOD;
                    d.quantity = 1;
                    try {
                        asHost(again, serverKeys, d);
                        Thread.sleep(5);   // a couple of hundred a second: busy, not a flood
                    } catch (Exception ignored) {}
                }
            }, "busy-writer");
            writer.setDaemon(true);   // never outlives the test, whatever happens to it
            writer.start();
            List<MarketClient> joiners = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                Path jd = dir.resolve("joiner-" + i);
                Files.createDirectories(jd);
                MarketClient c = new MarketClient(UUID.randomUUID(), "Joiner " + i, PlayerKeys.generate(),
                        new EventLog(jd.resolve("market.jsonl")), true, new PeerCache(jd.resolve("peers.json")), 0);
                c.connect("127.0.0.1", againPort);
                joiners.add(c);
            }
            Thread.sleep(500);
            busy.set(false);
            writer.join();
            Thread.sleep(500);
            boolean noGaps = true;
            for (MarketClient c : joiners) noGaps &= !c.needsResync();
            check("none of twenty joiners found a hole in what it was sent", noGaps);
            long head = again.headSeq();
            check("and every one is caught up", await(() -> {
                for (MarketClient c : joiners) if (c.lastSeq() < head) return false;
                return true;
            }, 5000));
            for (MarketClient c : joiners) c.disconnect();
        }
        again.stop();

        System.out.println("  [R8: the action budget]");
        {
            BotRunner.Budget b = new BotRunner.Budget(8, 1000, 0);
            check("a full bucket allows its capacity", b.take(10, 0) == 8);
            check("then nothing", b.take(1, 0) == 0 && b.take(1, 999) == 0);
            check("one more per interval", b.take(5, 1000) == 1);
            check("never more than its capacity, however long it waits", b.take(20, 1_000_000) == 8);
            b.take(0, 2_000_000);
            check("and a full bucket does not bank time", b.take(20, 2_000_000) == 8 && b.take(1, 2_000_500) == 0);
        }

        System.out.println("  [R9: a client from before bots is told to update]");
        check("protocol 9", HostServer.PROTOCOL_VERSION.equals("9"));
        check("an older client hears it needs a newer mod",
                HostServer.protocolRefusal("8").contains("needs a newer version of EconomiesMod"));
        check("a newer client hears the server is the old one",
                HostServer.protocolRefusal("10").contains("older version of EconomiesMod"));
        check("nonsense still gets the plain refusal",
                HostServer.protocolRefusal("x").contains("mismatch"));

        System.out.println();
        System.out.println(failures == 0
                ? "  all " + checksRun + " checks passed"
                : "  " + failures + " of " + checksRun + " checks FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ─────────── helpers ───────────

    private static BotRunner.Timing fast() {
        BotRunner.Timing t = new BotRunner.Timing();
        t.tickMs = 10;
        t.producerEvery = 3;
        t.makerEvery = 2;
        t.productionEvery = 5;
        t.poolSize = 3;
        t.budgetCapacity = 8;
        t.budgetRefillMs = 20;
        t.clock = () -> System.currentTimeMillis() + offset.get();
        return t;
    }

    /** The port the last server() call picked. */
    private static int lastPort;

    private static HostServer server(Path logPath, PlayerKeys keys) throws IOException {
        ServerConfig cfg = ServerConfig.friendGroup(TestPorts.free());
        lastPort = cfg.port;
        cfg.hostName = "bot runner";
        cfg.hostUserId = HOST.toString();
        cfg.dedicated = true;
        HostServer host = new HostServer(cfg, logPath, keys,
                new PeerCache(logPath.resolveSibling(logPath.getFileName() + ".peers.json")));
        host.useBotTiming(fast());
        return host;
    }

    private static void start(HostServer host) throws Exception {
        Thread t = new Thread(() -> {
            try { host.start(); } catch (IOException e) { /* stopped */ }
        }, "bot-runner-test-host");
        t.setDaemon(true);
        t.start();
        IOException bindError = host.awaitBound(5000);
        if (bindError != null) throw bindError;
    }

    /** A console command whose answer comes straight back (anything that does not queue work). */
    private static String say(HostServer host, String command) {
        StringBuilder out = new StringBuilder();
        BotConsole.handle(host, command, s -> out.append(s).append('\n'));
        return out.toString();
    }

    private static List<String> take(BlockingQueue<String> q, int n, long ms) throws InterruptedException {
        List<String> got = new ArrayList<>();
        long until = System.currentTimeMillis() + ms;
        while (got.size() < n) {
            String s = q.poll(Math.max(1, until - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (s == null) break;
            got.add(s);
        }
        return got;
    }

    private static boolean await(BooleanSupplier cond, long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return cond.getAsBoolean();
    }

    private static int orders(HostServer host, UUID bot) {
        if (bot == null) return 0;
        MarketState s = host.state();
        Lock read = s.readLock();
        read.lock();
        try {
            return s.restingOrdersOf(bot).size();
        } finally {
            read.unlock();
        }
    }

    private static long holding(HostServer host, UUID bot, String item) {
        Lock read = host.state().readLock();
        read.lock();
        try {
            return holding(host.state(), bot, item);
        } finally {
            read.unlock();
        }
    }

    private static long holding(MarketState s, UUID bot, String item) {
        return s.itemBalances().getBalance(bot, item) + s.restingVolume(bot, item, false);
    }

    private static Event.PlaceOrder buy(String item, long price, long volume) {
        Event.PlaceOrder p = new Event.PlaceOrder();
        p.userId = HOST;
        p.itemId = item;
        p.isBid = true;
        p.price = price;
        p.volume = volume;
        return p;
    }

    /** Signs an event as the creator and puts it through the sequencer, waiting for the verdict. */
    private static String asHost(HostServer host, PlayerKeys keys, Event e) throws Exception {
        e.marketId = host.state().marketId();
        e.clientEventId = UUID.randomUUID().toString();
        e.timestamp = System.currentTimeMillis() + offset.get();
        String sig = keys.sign(EventCanonical.canonicalPayload(e));
        CountDownLatch done = new CountDownLatch(1);
        String[] result = {"never ran"};
        host.enqueueHostWork(() -> {
            try {
                result[0] = host.appendSignedEvent(e, sig);
            } catch (IOException ex) {
                result[0] = ex.getMessage();
            } finally {
                done.countDown();
            }
        });
        done.await(5, TimeUnit.SECONDS);
        return result[0];
    }

    private static int countKeys() throws IOException {
        try (Stream<Path> s = Files.list(dir.resolve("bots"))) {
            return (int) s.filter(p -> p.getFileName().toString().endsWith(".key")).count();
        }
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
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
