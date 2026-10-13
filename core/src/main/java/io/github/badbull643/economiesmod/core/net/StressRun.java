package io.github.badbull643.economiesmod.core.net;

import io.github.badbull643.economiesmod.core.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;

/**
 * A stress run: a real dedicated server with every bot a market may have, deciding many times
 * faster than a real server lets them, and simulated players trading against them over real
 * connections. Prints a report every ten seconds, and at the end checks that the log verifies,
 * that every player's copy of the market agrees with the server's, and how long a newcomer
 * takes to sync.
 *
 * The server accepts game connections like any other, so a real player can join and watch.
 *
 *   java -cp economies-server.jar io.github.badbull643.economiesmod.core.net.StressRun
 *        --game-version 1.21.11 [--port 25560] [--players 10] [--minutes 10] [--pace 0.4]
 *
 * --pace is the seconds between one simulated player's actions: 0.4 (the fastest a server
 * allows) is a crowd of hard traders; 10 to 30 is closer to people who are also playing.
 *
 * With --connect host:port it starts no server and no bots: only the crowd, joining a real
 * server running somewhere else, so the server's machine carries the server's load alone.
 *
 *   java -cp economies-server.jar io.github.badbull643.economiesmod.core.net.StressRun
 *        --connect 192.168.1.20:25555 --game-version 1.21.11 --players 100 --pace 10
 *
 * Only the runner's pace is changed — how often bots decide and how fast their action budget
 * refills. Everything the market's rules govern (the bot cap, grants, production rate) is as
 * it is anywhere, because every player's game checks those.
 *
 * While it runs, the console takes the usual bot commands, plus "status" for a report now
 * and "stop" to finish early.
 */
public final class StressRun {
    private StressRun() {}

    private static final String[] ITEMS = BotRules.ITEMS.toArray(new String[0]);
    /** Which version's seed prices the simulated players start from: the bots' version. */
    private static volatile int seedVersion = BotRules.LATEST_VERSION;

    // What the report counts, from one observer's stream of applied events.
    private static final ConcurrentHashMap<String, AtomicLong> byType = new ConcurrentHashMap<>();
    private static final AtomicLong refused = new AtomicLong();
    // Counted as they happen. The market's own trade history keeps only the latest 512 per
    // item, for its price chart, so it stops growing once a run has made that many — the
    // first long run read that as trading stopping.
    private static final AtomicLong fills = new AtomicLong();
    private static final AtomicLong filledUnits = new AtomicLong();
    /** Fills with a player on at least one side: the activity bots are there to create. */
    private static final AtomicLong playerFills = new AtomicLong();
    private static final AtomicLong proposed = new AtomicLong();
    private static final ConcurrentHashMap<String, AtomicLong> refusalReasons = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        String gameVersion = null;
        int port = 25560, players = 10;
        double minutes = 10;
        int botVersion = 0;
        double speed = 1;
        // Seconds between one simulated player's actions. 0.4 is a trader hammering the market
        // all session; somebody actually playing the game does something every half-minute or so.
        double playerPace = 0.4;
        String connectTo = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--connect":      connectTo = args[++i]; break;
                case "--game-version": gameVersion = args[++i]; break;
                case "--port":         port = Integer.parseInt(args[++i]); break;
                case "--players":      players = Integer.parseInt(args[++i]); break;
                case "--minutes":      minutes = Double.parseDouble(args[++i]); break;
                case "--bot-version":  botVersion = Integer.parseInt(args[++i]); break;
                case "--speed":        speed = Double.parseDouble(args[++i]); break;
                case "--pace":         playerPace = Double.parseDouble(args[++i]); break;
                default:
                    System.err.println("unknown argument " + args[i]);
                    System.err.println("usage: StressRun --game-version <e.g. 1.21.11>"
                            + " [--port 25560] [--players 10] [--minutes 10]"
                            + " [--bot-version 1|2] [--speed 60] [--pace 0.4]");
                    System.err.println("   or: StressRun --connect <host:port> --game-version <e.g. 1.21.11>"
                            + " [--players 10] [--minutes 10] [--pace 0.4]");
                    System.exit(2);
            }
        }
        if (playerPace < 0.4) {
            // A server lets one connection propose 30 events per 10 s; faster than that and the
            // run would be measuring its own refusals.
            System.err.println("--pace below 0.4 s is faster than a server lets one player go");
            System.exit(2);
        }
        if (!GameVersions.isSet(gameVersion)) {
            System.err.println("say which Minecraft version can join: --game-version 1.21.11");
            System.exit(2);
        }
        if (connectTo != null) {
            int colon = connectTo.lastIndexOf(':');
            String address = colon > 0 ? connectTo.substring(0, colon) : connectTo;
            int remotePort = colon > 0 ? Integer.parseInt(connectTo.substring(colon + 1)) : 25555;
            if (botVersion > 0 || speed != 1) {
                System.out.println("[stress] (--bot-version and --speed are ignored with --connect:"
                        + " the bots are the server's, spawned from its own console)");
            }
            System.exit(runCrowd(address, remotePort, gameVersion, players, minutes, playerPace));
        }

        Path dir = Paths.get("stress-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
        Files.createDirectories(dir);
        Path logPath = dir.resolve("server-market.jsonl");
        System.out.println("[stress] market in " + dir.toAbsolutePath());

        // ─── the server ───
        PlayerKeys serverKeys = PlayerKeys.loadOrCreate(dir.resolve("server-identity.key"));
        UUID serverId = UUID.randomUUID();
        EventLog log = new EventLog(logPath);
        MarketBootstrap.createMarket(log, serverId, "stress test market", serverKeys, 5_000, 0, 0);
        MarketBootstrap.enableBots(log, serverId, serverKeys, serverKeys.publicKeyString());

        ServerConfig cfg = ServerConfig.friendGroup(port);
        cfg.hostName = "stress";
        cfg.hostUserId = serverId.toString();
        cfg.gameVersion = gameVersion;
        cfg.dedicated = true;
        // Room for the whole crowd, the newcomer that syncs at the end, and a few real players
        // joining to watch. The default of 64 turned everyone past it away as "server full".
        cfg.maxConnections = Math.max(cfg.maxConnections, players + 16);
        HostServer host = new HostServer(cfg, logPath, serverKeys, new PeerCache(dir.resolve("server-peers.json")));

        BotRunner.Timing pace = new BotRunner.Timing();
        pace.tickMs = 50;
        pace.makerEvery = 2;          // 100 ms, against 10 s on a real server
        pace.producerEvery = 4;       // 200 ms, against 30 s
        pace.productionEvery = 20;    // looked at every second; the rate itself is the market's
        pace.budgetRefillMs = 50;     // about 20 actions a second each, against one per 15 s
        // --speed: the bots' clock runs this many times faster than the wall's, so production
        // and upkeep — fixed rates per hour — happen in minutes. The rules check production
        // against the bot's own earlier timestamps, which are on the same fast clock.
        final long clockStart = System.currentTimeMillis();
        final double clockSpeed = speed;
        pace.clock = () -> clockStart + (long) ((System.currentTimeMillis() - clockStart) * clockSpeed);
        host.useBotTiming(pace);

        Thread serverThread = new Thread(() -> {
            try { host.start(); } catch (IOException e) { System.err.println("[stress] server: " + e.getMessage()); }
        }, "stress-server");
        serverThread.setDaemon(true);
        serverThread.start();
        IOException bindError = host.awaitBound(10_000);
        if (bindError != null) throw bindError;
        while (host.botRunner() == null) Thread.sleep(50);
        if (botVersion > 0) host.botRunner().useSpawnVersion(botVersion);
        seedVersion = botVersion > 0 ? botVersion : BotRules.LATEST_VERSION;
        System.out.println("[stress] bots on algorithm version "
                + (botVersion > 0 ? botVersion : BotRules.LATEST_VERSION)
                + (speed != 1 ? ", their clock at " + speed + "x" : ""));

        // Every bot the market allows, half of each type on each scheduler.
        int half = BotRules.MAX_PER_TYPE / 2;
        for (String cmd : new String[]{
                "bot spawn producer " + half + " tick", "bot spawn producer " + half + " threads",
                "bot spawn maker " + half + " tick", "bot spawn maker " + half + " threads"}) {
            BotConsole.handle(host, cmd, s -> System.out.println("[bots] " + s));
        }
        while (host.state().bots().size() < BotRules.MAX_PER_TYPE * 2) Thread.sleep(50);

        // ─── the simulated players ───
        List<FakePlayer> crowd = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            FakePlayer p = new FakePlayer(i, dir, gameVersion);
            p.client.connect("127.0.0.1", port);
            crowd.add(p);
            Thread.sleep(150);   // under the server's limit on connections being set up at once
        }
        crowd.get(0).client.setOnApplied(a -> {
            byType.computeIfAbsent(a.event.eventType, k -> new AtomicLong()).incrementAndGet();
            if (a.result != null && a.result.fills != null) {
                MarketState st = crowd.get(0).client.state();
                for (Fill f : a.result.fills) {
                    fills.incrementAndGet();
                    filledUnits.addAndGet(f.quantity());
                    if (!st.isBot(f.buyerId()) || !st.isBot(f.sellerId())) playerFills.incrementAndGet();
                }
            }
        });
        ScheduledExecutorService playerPool = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "stress-player");
            t.setDaemon(true);
            return t;
        });
        long paceMs = (long) (playerPace * 1000);
        for (FakePlayer p : crowd) {
            // Spread across the interval so the crowd does not all act on the same beat.
            playerPool.scheduleWithFixedDelay(p::stepSafely, 200 + (p.index * 37L) % paceMs,
                    paceMs, TimeUnit.MILLISECONDS);
        }

        System.out.println("[stress] " + players + " players (one action every " + playerPace + " s each, about "
                + String.format(Locale.ROOT, "%.0f", players / playerPace) + " a second together) and "
                + host.state().bots().size()
                + " bots trading. Join on port " + port + " with Minecraft " + gameVersion
                + " to watch. Type 'status', 'stop', or any bot command.");

        // ─── run ───
        AtomicBoolean finish = new AtomicBoolean();
        Thread console = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.equals("stop") || line.equals("quit")) { finish.set(true); return; }
                    if (line.equals("status")) { report(host, logPath, crowd, 0, 0); continue; }
                    if (!line.isEmpty()) BotConsole.handle(host, line, s -> System.out.println("[bots] " + s));
                }
            } catch (IOException ignored) {}
        }, "stress-console");
        console.setDaemon(true);
        console.start();

        long started = System.currentTimeMillis();
        long until = minutes > 0 ? started + (long) (minutes * 60_000) : Long.MAX_VALUE;
        long lastSeq = host.headSeq(), lastAt = started;
        while (!finish.get() && System.currentTimeMillis() < until) {
            Thread.sleep(250);
            if (System.currentTimeMillis() - lastAt >= 10_000) {
                long now = System.currentTimeMillis();
                report(host, logPath, crowd, host.headSeq() - lastSeq, now - lastAt);
                lastSeq = host.headSeq();
                lastAt = now;
            }
        }

        // ─── checks ───
        System.out.println();
        System.out.println("[stress] finishing after " + (System.currentTimeMillis() - started) / 1000 + " s");
        playerPool.shutdownNow();
        host.botRunner().stop();          // bots stop deciding; what they queued drains
        Thread.sleep(1_000);
        long head = host.headSeq();

        long joinStart = System.currentTimeMillis();
        FakePlayer newcomer = new FakePlayer(players, dir, gameVersion);
        newcomer.client.connect("127.0.0.1", port);
        while (newcomer.client.lastSeq() < head && System.currentTimeMillis() - joinStart < 120_000) Thread.sleep(20);
        long joinMs = System.currentTimeMillis() - joinStart;

        // Wait for the market to go quiet — the newcomer's own registration and grant are
        // still landing — then for every replica to reach the same head, then compare.
        long deadline = System.currentTimeMillis() + 10_000;
        for (long seen = -1; System.currentTimeMillis() < deadline; Thread.sleep(500)) {
            long now = host.headSeq();
            if (now == seen && host.pendingWork() == 0) break;
            seen = now;
        }
        long finalHead = host.headSeq();
        deadline = System.currentTimeMillis() + 10_000;
        List<FakePlayer> everyone = new ArrayList<>(crowd);
        everyone.add(newcomer);
        for (FakePlayer p : everyone) {
            while (p.client.lastSeq() < finalHead && System.currentTimeMillis() < deadline) Thread.sleep(20);
        }
        // Players the server dropped for falling too far behind are counted apart. Their copies
        // stopped where they were cut off, which is the server doing its job, not a disagreement
        // — and these simulated players do not reconnect the way a real one would.
        int agree = 0, stillOn = 0;
        for (FakePlayer p : crowd) {
            if (!p.client.isConnected()) continue;
            stillOn++;
            if (sameMarket(host.state(), p.client.state())) agree++;
        }
        int dropped = crowd.size() - stillOn;
        boolean newcomerAgrees = sameMarket(host.state(), newcomer.client.state());

        for (FakePlayer p : crowd) p.client.disconnect();
        newcomer.client.disconnect();
        host.stop();

        MarketArchive.Verified verified = null;
        String verifyError = null;
        long verifyStart = System.currentTimeMillis();
        try {
            verified = MarketArchive.verifyLines(new EventLog(logPath).rawLinesFrom(1));
        } catch (IOException e) {
            verifyError = e.getMessage();
        }
        long verifyMs = System.currentTimeMillis() - verifyStart;

        System.out.println("[stress] final report");
        report(host, logPath, crowd, 0, 0);
        System.out.println("[stress]   events by type: " + new TreeMap<>(byType));
        System.out.println("[stress]   player proposals " + proposed.get() + ", refused " + refused.get()
                + (refusalReasons.isEmpty() ? "" : " " + topReasons()));
        System.out.println("[stress]   a newcomer synced " + head + " events in " + joinMs + " ms");
        System.out.println("[stress]   whole log verified in " + verifyMs + " ms");
        System.out.println();
        int failures = 0;
        failures += verdict("the log verifies: every hash and every signature",
                verified != null, verifyError);
        failures += verdict("a replay of the log matches the server's market",
                verified != null && sameMarket(host.state(), verified.state), null);
        failures += verdict("every connected player's copy matches the server's (" + agree + " of " + stillOn + ")",
                agree == stillOn, null);
        if (dropped > 0) {
            System.out.println("[stress]   note " + dropped + " player(s) were dropped by the server for"
                    + " falling too far behind, so their copies stop where they were cut off");
        }
        failures += verdict("a newcomer's copy matches too", newcomerAgrees, null);
        failures += verdict("bots stayed within the market's rules (holdings within the production ceiling)",
                botsWithinRules(host.state()), null);
        System.out.println();
        System.out.println(failures == 0 ? "[stress] PASSED" : "[stress] " + failures + " check(s) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ─────────── --connect: the crowd alone, against a server elsewhere ───────────

    /**
     * Only the simulated players, connected to a real server on another machine. The local
     * run shares one computer between the server and every player's game, so what it measures
     * is that computer as much as the server; this puts the crowd's load somewhere else, and
     * whatever the server then cannot keep up with is the server's limit.
     *
     * It cannot see the server's own copy of the market, so its checks are the ones a player
     * could make: every connected player's copy agrees with a newcomer's, and the history the
     * newcomer downloads verifies and replays to the same market. Bots are the server's: spawn
     * them from its console before starting this.
     *
     * Returns the exit code: 0 when every check passed.
     */
    private static int runCrowd(String address, int port, String gameVersion, int players,
                                double minutes, double playerPace) throws Exception {
        Path dir = Paths.get("stress-crowd-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
        Files.createDirectories(dir);
        System.out.println("[stress] crowd only, against " + address + ":" + port
                + " — the players' copies are in " + dir.toAbsolutePath());

        // ─── join ───
        List<FakePlayer> crowd = new ArrayList<>();
        Map<String, Integer> turnedAway = new TreeMap<>();
        for (int i = 0; i < players; i++) {
            FakePlayer p = new FakePlayer(i, dir, gameVersion);
            try {
                p.client.connect(address, port);
                crowd.add(p);
            } catch (IOException refusedAtDoor) {
                String why = refusedAtDoor.getMessage() == null ? "?" : refusedAtDoor.getMessage();
                turnedAway.merge(why.length() > 80 ? why.substring(0, 80) : why, 1, Integer::sum);
            }
            Thread.sleep(150);   // under the server's limit on connections being set up at once
        }
        if (!turnedAway.isEmpty()) {
            System.out.println("[stress] " + (players - crowd.size()) + " could not join: " + turnedAway);
        }
        if (crowd.isEmpty()) {
            System.out.println("[stress] nobody could join, so there is nothing to measure");
            return 1;
        }
        // Their grants and registrations land as they join; give them a moment to arrive.
        Thread.sleep(2_000);

        FakePlayer observer = crowd.get(0);
        observer.client.setOnApplied(a -> {
            byType.computeIfAbsent(a.event.eventType, k -> new AtomicLong()).incrementAndGet();
            if (a.result != null && a.result.fills != null) {
                MarketState st = observer.client.state();
                for (Fill f : a.result.fills) {
                    fills.incrementAndGet();
                    filledUnits.addAndGet(f.quantity());
                    if (!st.isBot(f.buyerId()) || !st.isBot(f.sellerId())) playerFills.incrementAndGet();
                }
            }
        });
        int bots = observer.client.state().bots().size();
        if (bots == 0) {
            System.out.println("[stress] (this market has no bots running yet; spawn some from the"
                    + " server's console if you want them in the run)");
        }

        ScheduledExecutorService playerPool = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "stress-player");
            t.setDaemon(true);
            return t;
        });
        long paceMs = (long) (playerPace * 1000);
        for (FakePlayer p : crowd) {
            playerPool.scheduleWithFixedDelay(p::stepSafely, 200 + (p.index * 37L) % paceMs,
                    paceMs, TimeUnit.MILLISECONDS);
        }
        System.out.println("[stress] " + crowd.size() + " players trading (one action every " + playerPace
                + " s each, about " + String.format(Locale.ROOT, "%.0f", crowd.size() / playerPace)
                + " a second together) with the server's " + bots + " bots."
                + " Watch the server's own window and its CPU. Type 'status' or 'stop'.");

        // ─── run ───
        AtomicBoolean finish = new AtomicBoolean();
        Thread console = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.equals("stop") || line.equals("quit")) { finish.set(true); return; }
                    if (line.equals("status")) crowdReport(crowd, 0, 0, 0, 0);
                    else if (!line.isEmpty()) System.out.println("[stress] only 'status' and 'stop' here —"
                            + " bot and server commands go in the server's own window");
                }
            } catch (IOException ignored) {}
        }, "stress-console");
        console.setDaemon(true);
        console.start();

        long started = System.currentTimeMillis();
        long until = minutes > 0 ? started + (long) (minutes * 60_000) : Long.MAX_VALUE;
        long lastSeq = headSeen(crowd), lastAt = started;
        long lastProposed = proposed.get(), lastRefused = refused.get();
        while (!finish.get() && System.currentTimeMillis() < until) {
            Thread.sleep(250);
            if (System.currentTimeMillis() - lastAt >= 10_000) {
                long now = System.currentTimeMillis(), head = headSeen(crowd);
                crowdReport(crowd, head - lastSeq, now - lastAt,
                        proposed.get() - lastProposed, refused.get() - lastRefused);
                lastSeq = head;
                lastAt = now;
                lastProposed = proposed.get();
                lastRefused = refused.get();
            }
        }

        // ─── checks ───
        System.out.println();
        System.out.println("[stress] finishing after " + (System.currentTimeMillis() - started) / 1000 + " s");
        playerPool.shutdownNow();
        // Quiet, as far as anyone here can tell: the newest event anybody has seen stops moving.
        // The server's bots go on trading, so it may never be still; ten seconds is enough to
        // tell the crowd's last orders have landed.
        long deadline = System.currentTimeMillis() + 10_000;
        for (long seen = -1; System.currentTimeMillis() < deadline; Thread.sleep(1_000)) {
            long now = headSeen(crowd);
            if (now == seen) break;
            seen = now;
        }
        long head = headSeen(crowd);

        long joinStart = System.currentTimeMillis();
        FakePlayer newcomer = new FakePlayer(players, dir, gameVersion);
        long joinMs;
        try {
            newcomer.client.connect(address, port);
            while (newcomer.client.lastSeq() < head && System.currentTimeMillis() - joinStart < 120_000) Thread.sleep(20);
            joinMs = System.currentTimeMillis() - joinStart;
        } catch (IOException e) {
            System.out.println("[stress] the newcomer could not join: " + e.getMessage());
            joinMs = -1;
        }

        playerPool.awaitTermination(1, TimeUnit.SECONDS);
        List<FakePlayer> compared = new ArrayList<>();
        for (FakePlayer p : crowd) if (p.client.isConnected()) compared.add(p);
        int dropped = crowd.size() - compared.size();

        System.out.println("[stress] final report");
        crowdReport(crowd, 0, 0, 0, 0);

        // Everyone, newcomer included, on the same event before comparing. The server's bots keep
        // the market moving, so wait for a moment between their batches when every copy has
        // applied the same last event, then disconnect at once, which freezes each copy where it
        // is. A copy that still slipped past or behind is left out rather than misjudged.
        // Together for a whole second, not merely at one instant: the first version froze the
        // moment everyone matched, which was usually the moment just after the newcomer joined
        // and before its own registration and grant had arrived — so everyone else received
        // those two while being disconnected, and most of the crowd went uncompared.
        deadline = System.currentTimeMillis() + 15_000;
        long steadyAt = -1, steadySince = 0;
        while (System.currentTimeMillis() < deadline) {
            long now = System.currentTimeMillis(), seq = newcomer.client.lastSeq();
            boolean together = true;
            for (FakePlayer p : compared) together &= p.client.lastSeq() == seq;
            if (!together) {
                steadyAt = -1;
            } else if (seq != steadyAt) {
                steadyAt = seq;
                steadySince = now;
            } else if (now - steadySince >= 1_000) {
                break;
            }
            Thread.sleep(20);
        }
        newcomer.client.disconnect();
        for (FakePlayer p : crowd) p.client.disconnect();
        long frozenAt = newcomer.client.lastSeq();
        int agree = 0, atSamePoint = 0;
        for (FakePlayer p : compared) {
            if (p.client.lastSeq() != frozenAt) continue;
            atSamePoint++;
            if (sameMarket(newcomer.client.state(), p.client.state())) agree++;
        }

        MarketArchive.Verified verified = null;
        String verifyError = null;
        long verifyStart = System.currentTimeMillis();
        try {
            verified = MarketArchive.verifyLines(newcomer.client.log().rawLinesFrom(1));
        } catch (IOException e) {
            verifyError = e.getMessage();
        }
        long verifyMs = System.currentTimeMillis() - verifyStart;

        System.out.println("[stress]   events by type, as one player saw them: " + new TreeMap<>(byType));
        System.out.println("[stress]   player proposals " + proposed.get() + ", refused " + refused.get()
                + (refusalReasons.isEmpty() ? "" : " " + topReasons()));
        System.out.println("[stress]   a newcomer synced " + head + " events in "
                + (joinMs < 0 ? "— (could not join)" : joinMs + " ms"));
        System.out.println("[stress]   the history it downloaded verified in " + verifyMs + " ms");
        System.out.println();
        int failures = 0;
        failures += verdict("the history a newcomer downloads verifies: every hash and every signature",
                verified != null, verifyError);
        failures += verdict("a replay of it matches the newcomer's copy",
                verified != null && sameMarket(newcomer.client.state(), verified.state), null);
        failures += verdict("every player who reached the same event agrees with the newcomer ("
                + agree + " of " + atSamePoint + ")", atSamePoint > 0 && agree == atSamePoint, null);
        if (compared.size() > atSamePoint) {
            Map<Long, Integer> where = new TreeMap<>();
            for (FakePlayer p : compared) {
                if (p.client.lastSeq() != frozenAt) where.merge(p.client.lastSeq(), 1, Integer::sum);
            }
            System.out.println("[stress]   note " + (compared.size() - atSamePoint) + " connected player(s)"
                    + " were on a different event from the newcomer (" + frozenAt + ") when the run"
                    + " stopped, so were not compared — event: players " + where);
        }
        if (dropped > 0) {
            System.out.println("[stress]   note " + dropped + " player(s) were dropped by the server for"
                    + " falling too far behind, so their copies stop where they were cut off");
        }
        System.out.println();
        System.out.println(failures == 0 ? "[stress] PASSED" : "[stress] " + failures + " check(s) FAILED");
        return failures == 0 ? 0 : 1;
    }

    /** The newest event any player here has applied: as close to the server's head as this side can see. */
    private static long headSeen(List<FakePlayer> crowd) {
        long head = 0;
        for (FakePlayer p : crowd) head = Math.max(head, p.client.lastSeq());
        return head;
    }

    private static void crowdReport(List<FakePlayer> crowd, long events, long ms, long sent, long turnedDown) {
        long head = headSeen(crowd), behind = 0;
        int connected = 0;
        for (FakePlayer p : crowd) {
            if (!p.client.isConnected()) continue;
            connected++;
            behind = Math.max(behind, head - p.client.lastSeq());
        }
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
        String rate = ms > 0 ? String.format(Locale.ROOT, " (+%d, %.0f/s)", events, events * 1000.0 / ms) : "";
        String sentRate = ms > 0 ? String.format(Locale.ROOT, " | sent %.0f/s, %d%% refused",
                sent * 1000.0 / ms, sent == 0 ? 0 : turnedDown * 100 / sent) : "";
        System.out.println(String.format(Locale.ROOT,
                "[stress] seq %d%s%s | trades %d | %d of %d players connected | slowest %d behind | heap %d MB here",
                head, rate, sentRate, fills.get(), connected, crowd.size(), behind, usedMb));
        System.out.println("[stress]   economy: " + economy(crowd.get(0).client.state(), fills.get()));
    }

    // ─────────── reporting ───────────

    private static void report(HostServer host, Path logPath, List<FakePlayer> crowd, long events, long ms) {
        long trades = fills.get(), units = filledUnits.get();
        long live = sizeOf(logPath), sealed = sizeOf(EventLog.historyPathFor(logPath));
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
        long behind = 0;
        for (FakePlayer p : crowd) behind = Math.max(behind, host.headSeq() - p.client.lastSeq());
        String rate = ms > 0 ? String.format(Locale.ROOT, " (+%d, %.0f/s)", events, events * 1000.0 / ms) : "";
        System.out.println(String.format(Locale.ROOT,
                "[stress] seq %d%s | trades %d (%d units) | log %s live + %s sealed | queue %d"
                        + " | slowest player %d behind | heap %d MB",
                host.headSeq(), rate, trades, units, mb(live), mb(sealed), host.pendingWork(), behind, usedMb));
        System.out.println("[stress]   economy: " + economy(host.state(), trades));
    }

    /**
     * How the economy is doing, in three figures: the share of all credits the bots hold
     * (version 1's producers soaked them up; it should hold steady), a price index (each
     * basket item's market price over its version 2 seed, averaged geometrically — 1.00
     * means prices sit where they were seeded), and the share of trades with a player in.
     */
    private static String economy(MarketState s, long trades) {
        Lock read = s.readLock();
        read.lock();
        try {
            long bots = 0, all = 0;
            for (UUID u : s.registeredUsers()) {
                long c = s.wallets().getBalance(u);
                all += c;
                if (s.isBot(u)) bots += c;
            }
            double logSum = 0;
            int priced = 0;
            StringBuilder prices = new StringBuilder();
            for (String item : BotRules.ITEMS) {
                long mp = MarketView.row(s, null, item, System.currentTimeMillis()).marketPrice;
                prices.append(' ').append(item.substring(10, Math.min(item.length(), 14))).append('=').append(MarketView.price(mp));
                if (mp > 0) {
                    logSum += Math.log((double) mp / BotBrain.basePriceV2(item));
                    priced++;
                }
            }
            String index = priced == 0 ? "-" : String.format(Locale.ROOT, "%.2f", Math.exp(logSum / priced));
            String withPlayers = trades == 0 ? "-" : (playerFills.get() * 100 / Math.max(1, trades)) + "%";
            return String.format(Locale.ROOT, "bots hold %d%% of credits | price index %s (%s) | %s of trades involve a player",
                    all == 0 ? 0 : bots * 100 / all, index, prices.toString().trim(), withPlayers);
        } finally {
            read.unlock();
        }
    }

    private static int verdict(String label, boolean ok, String detail) {
        System.out.println((ok ? "[stress]   ok   " : "[stress]   FAIL ") + label
                + (detail == null ? "" : " (" + detail + ")"));
        return ok ? 0 : 1;
    }

    private static String topReasons() {
        List<Map.Entry<String, AtomicLong>> e = new ArrayList<>(refusalReasons.entrySet());
        e.sort((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()));
        StringBuilder sb = new StringBuilder("— most common:");
        for (int i = 0; i < Math.min(3, e.size()); i++) {
            sb.append(" \"").append(e.get(i).getKey()).append("\" x").append(e.get(i).getValue().get());
        }
        return sb.toString();
    }

    private static long sizeOf(Path p) {
        try { return Files.exists(p) ? Files.size(p) : 0; } catch (IOException e) { return 0; }
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    /** Same balances, holdings and open orders for everyone either side knows about. */
    private static boolean sameMarket(MarketState a, MarketState b) {
        Set<UUID> who = new HashSet<>(a.registeredUsers());
        who.addAll(b.registeredUsers());
        Lock ra = a.readLock(), rb = b.readLock();
        ra.lock();
        rb.lock();
        try {
            for (UUID u : who) {
                if (a.wallets().getBalance(u) != b.wallets().getBalance(u)) return false;
                for (String item : ITEMS) {
                    if (a.itemBalances().getBalance(u, item) != b.itemBalances().getBalance(u, item)) return false;
                }
                if (a.restingOrdersOf(u).size() != b.restingOrdersOf(u).size()) return false;
            }
            return true;
        } finally {
            rb.unlock();
            ra.unlock();
        }
    }

    private static boolean botsWithinRules(MarketState s) {
        for (Map.Entry<UUID, MarketState.BotRecord> e : s.bots().entrySet()) {
            if (!e.getValue().type.equals(BotRules.PRODUCER)) continue;
            for (String item : ITEMS) {
                if (s.itemBalances().getBalance(e.getKey(), item) + s.restingVolume(e.getKey(), item, false)
                        > BotRules.PRODUCTION_CEILING) return false;
            }
        }
        return true;
    }

    // ─────────── a simulated player ───────────

    /**
     * Trades like a busy, slightly random person: deposits goods when low, places buys and
     * sells around the last price (sometimes crossing it, so things fill), cancels old orders
     * when it has too many open. Its own replica of the market is what it decides from, as a
     * real player's game would.
     */
    private static final class FakePlayer {
        final int index;
        final UUID id = UUID.randomUUID();
        final PlayerKeys keys;
        final MarketClient client;
        final Random rnd;

        FakePlayer(int index, Path dir, String gameVersion) throws Exception {
            this.index = index;
            this.keys = PlayerKeys.generate();
            this.rnd = new Random(index * 7919L + 17);
            Path p = dir.resolve("players").resolve("player-" + index);
            Files.createDirectories(p);
            this.client = new MarketClient(id, "Stress " + index, keys, new EventLog(p.resolve("market.jsonl")),
                    true, new PeerCache(p.resolve("peers.json")), 0);
            client.setGameVersion(gameVersion);
            client.setOnRejected(why -> {
                refused.incrementAndGet();
                String key = why == null ? "?" : why.length() > 60 ? why.substring(0, 60) : why;
                refusalReasons.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
            });
        }

        void stepSafely() {
            try {
                step();
            } catch (Throwable t) {
                System.err.println("[stress] player " + index + ": " + t);
            }
        }

        private void step() {
            if (!client.isConnected()) return;
            MarketState s = client.state();
            String item = ITEMS[rnd.nextInt(ITEMS.length)];
            long credits, stock, ref;
            List<Order> mine;
            Lock read = s.readLock();
            read.lock();
            try {
                credits = s.wallets().getBalance(id);
                stock = s.itemBalances().getBalance(id, item);
                long last = s.trades().lastPrice(item);
                ref = last > 0 ? last : seedVersion >= 2 ? BotBrain.basePriceV2(item) : BotBrain.basePrice(item);
                mine = s.restingOrdersOf(id);
            } finally {
                read.unlock();
            }

            // Every player gathers two of the basket items and sells them, and buys the other
            // three and uses them up — in the game, withdrawing them to place or craft. Like the
            // producers, and like real players, who both earn and spend. A crowd split into
            // gatherers and builders went broke on one side: the builders had no income, spent
            // their grant, and demand died with them, whatever the bots did.
            int itemIndex = java.util.Arrays.asList(ITEMS).indexOf(item);
            boolean gatherer = (itemIndex - index % ITEMS.length + ITEMS.length) % ITEMS.length < 2;
            if (!gatherer) {
                if (stock > 0 && rnd.nextInt(4) == 0) {
                    Event.Withdraw w = new Event.Withdraw();
                    w.userId = id;
                    w.itemId = item;
                    w.quantity = Math.min(stock, 8);
                    send(w);
                    return;
                }
            } else if (stock < 16 && rnd.nextInt(4) == 0) {
                Event.Deposit d = new Event.Deposit();
                d.userId = id;
                d.itemId = item;
                d.quantity = 64;
                send(d);
                return;
            }
            if (mine.size() > 6) {
                Order o = mine.get(rnd.nextInt(mine.size()));
                Event.CancelOrder c = new Event.CancelOrder();
                c.userId = id;
                c.orderId = o.orderId();
                c.itemId = o.itemID();
                c.isBid = o.isBid();
                send(c);
                return;
            }
            // Like a player who gathers: sells what they have plenty of, buys what they are
            // short of, and either in between. The first version picked a side at random, so
            // simulated players bought things they already held hundreds of until they were
            // broke — whoever was selling ended up with all the money, bot or not.
            boolean bid = gatherer ? stock < 4 : stock < 32 || (stock < 128 && rnd.nextInt(3) > 0);
            long price = Math.max(1, ref + rnd.nextInt(3) - (bid ? 0 : 1));   // leans towards crossing
            long volume = 1 + rnd.nextInt(16);
            if (bid && credits < price * volume) return;
            if (!bid && stock < volume) return;
            Event.PlaceOrder po = new Event.PlaceOrder();
            po.userId = id;
            po.itemId = item;
            po.isBid = bid;
            po.price = price;
            po.volume = volume;
            send(po);
        }

        private void send(Event e) {
            e.timestamp = System.currentTimeMillis();
            if (client.propose(e) != null) proposed.incrementAndGet();
        }
    }
}
