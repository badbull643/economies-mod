package io.github.badbull643.economiesmod.core.net;

import io.github.badbull643.economiesmod.core.Event;
import io.github.badbull643.economiesmod.core.EventLog;
import io.github.badbull643.economiesmod.core.GameVersions;
import io.github.badbull643.economiesmod.core.ItemVersions;
import io.github.badbull643.economiesmod.core.MarketBootstrap;
import io.github.badbull643.economiesmod.core.PeerCache;
import io.github.badbull643.economiesmod.core.PlayerKeys;
import io.github.badbull643.economiesmod.core.ServerConfig;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A market shared across Minecraft versions: one book per item for everyone, and each
 * player trading only what their own version has.
 *
 * The table first, then a real host over real sockets with clients claiming different
 * versions: who gets in, what each may deposit, buy, sell and withdraw, renamed items, and
 * a 1.16.5 buyer actually trading with a 26.3 seller. See docs/CROSS-VERSION-SPEC.md.
 */
public class CrossVersionTest {

    private static int failures = 0;
    private static int checksRun = 0;

    private static void check(String label, boolean ok) {
        checksRun++;
        if (!ok) failures++;
        System.out.println((ok ? "    ok   " : "    FAIL ") + label);
    }

    private static final UUID HOST = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final String SHARED_MAIN = "1.21.11";
    private static final List<String> SHARED_ALSO = Arrays.asList("1.16.5", "26.3");

    private static Path dir;
    private static int fileCounter = 0;

    public static void main(String[] args) throws Exception {
        dir = Paths.get("build", "test-scratch");
        Files.createDirectories(dir);

        System.out.println("  [X1: the item table]");
        check("oak logs are in every version",
                ItemVersions.availableIn("minecraft:oak_log", "1.16.5")
                        && ItemVersions.availableIn("minecraft:oak_log", "26.3"));
        check("copper arrived in 1.20.1 here",
                !ItemVersions.availableIn("minecraft:copper_ingot", "1.16.5")
                        && ItemVersions.availableIn("minecraft:copper_ingot", "1.20.1")
                        && "1.20.1".equals(ItemVersions.firstVersion("minecraft:copper_ingot")));
        check("poplar logs only in 26.3",
                !ItemVersions.availableIn("minecraft:poplar_log", "1.21.11")
                        && ItemVersions.availableIn("minecraft:poplar_log", "26.3"));
        check("a renamed item keeps its first name",
                "minecraft:chain".equals(ItemVersions.canonical("minecraft:iron_chain"))
                        && "minecraft:grass".equals(ItemVersions.canonical("minecraft:short_grass")));
        check("and each version is told its own name for it",
                "minecraft:iron_chain".equals(ItemVersions.nameIn("minecraft:chain", "26.3"))
                        && "minecraft:chain".equals(ItemVersions.nameIn("minecraft:chain", "1.21.1"))
                        && "minecraft:dirt_path".equals(ItemVersions.nameIn("minecraft:grass_path", "1.20.1"))
                        && "minecraft:grass_path".equals(ItemVersions.nameIn("minecraft:grass_path", "1.16.5")));
        check("which exists in every version, under one name or the other",
                ItemVersions.availableIn("minecraft:chain", "1.16.5")
                        && ItemVersions.availableIn("minecraft:iron_chain", "26.3"));
        check("only first names are known items",
                ItemVersions.isKnownItem("minecraft:chain") && !ItemVersions.isKnownItem("minecraft:iron_chain"));
        check("another mod's items are not",
                !ItemVersions.isKnownItem("create:andesite_alloy")
                        && "create:andesite_alloy".equals(ItemVersions.canonical("create:andesite_alloy")));
        check("nor are versions the table doesn't cover",
                !ItemVersions.knowsVersion("1.21.4") && ItemVersions.knowsVersion(null));

        System.out.println("  [X2: a config that would make a bad shared market is refused]");
        {
            ServerConfig c = ServerConfig.friendGroup(25555);
            c.gameVersions = new ArrayList<>(Collections.singletonList("26.3"));
            check("gameVersions without gameVersion", c.problem() != null
                    && c.problem().contains("gameVersion"));
            c.gameVersion = "1.21.11";
            check("with it, fine", c.problem() == null && c.crossVersion());
            c.gameVersions.add("1.21.4");
            check("a version the table doesn't know", c.problem() != null
                    && c.problem().contains("1.21.4"));
            ServerConfig single = ServerConfig.friendGroup(25555);
            single.gameVersion = "1.21.11";
            check("one version is not shared", !single.crossVersion()
                    && single.acceptedVersions().equals(Collections.singletonList("1.21.11")));
            single.gameVersions = new ArrayList<>(Collections.singletonList("1.21.11"));
            check("and listing it twice still isn't", !single.crossVersion());
        }

        Running host = startHost();
        try {
            System.out.println("  [X3: every listed version gets in, and learns the market is shared]");
            Player p116 = Player.join(host, "1.16.5", "Old");
            Player p1211 = Player.join(host, "1.21.11", "Mid");
            Player p263 = Player.join(host, "26.3", "New");
            check("1.16.5 connects", p116.client.isConnected());
            check("1.21.11 connects", p1211.client.isConnected());
            check("26.3 connects", p263.client.isConnected());
            check("each knows the market is shared",
                    p116.client.crossVersion() && p263.client.crossVersion());
            check("and by which versions",
                    p263.client.sharedVersions().containsAll(Arrays.asList("1.16.5", "1.21.11", "26.3")));
            MarketClient.Refused off = refusedAs("1.20.1");
            check("a version that isn't listed is refused",
                    off != null && HostServer.Refusal.GAME_VERSION.equals(off.code));

            System.out.println("  [X4: a mod from before shared markets is asked to update]");
            Message.Error oldMod = oldModHello(host.port, "1.21.11");
            check("refused, though its version is listed",
                    oldMod != null && HostServer.Refusal.GAME_VERSION.equals(oldMod.code));
            check("and told to update", oldMod != null && oldMod.reason.contains("newer EconomiesMod"));

            System.out.println("  [X5: each trades what their version has]");
            check("1.16.5 deposits oak logs", p116.accept(deposit(p116, "minecraft:oak_log", 20)));
            String copper = p116.refusal(deposit(p116, "minecraft:copper_ingot", 5));
            check("but not copper", copper != null && copper.contains("1.20.1"));
            check("1.21.11 deposits copper", p1211.accept(deposit(p1211, "minecraft:copper_ingot", 5)));
            check("26.3 deposits poplar logs", p263.accept(deposit(p263, "minecraft:poplar_log", 10)));
            check("and lists some", p263.accept(order(p263, "minecraft:poplar_log", 4, 5, false)));
            check("1.21.11 can't bid for them", p1211.refusal(order(p1211, "minecraft:poplar_log", 4, 1, true)) != null);
            check("1.16.5 neither", p116.refusal(order(p116, "minecraft:poplar_log", 4, 1, true)) != null);

            System.out.println("  [X6: renamed items are one item]");
            check("26.3 deposits chains by their first name",
                    p263.accept(deposit(p263, "minecraft:chain", 3)));
            String later = p263.refusal(deposit(p263, "minecraft:iron_chain", 3));
            check("their later name is refused, and named", later != null && later.contains("minecraft:chain"));
            check("1.16.5 bids for the same chains", p116.accept(order(p116, "minecraft:chain", 2, 3, true)));
            check("26.3 sells them, and they trade",
                    p263.accept(order(p263, "minecraft:chain", 2, 3, false))
                            && p116.held("minecraft:chain") == 3);

            System.out.println("  [X7: one book for everyone]");
            check("1.16.5 lists oak logs", p116.accept(order(p116, "minecraft:oak_log", 3, 6, false)));
            check("and so does 1.21.11", p1211.accept(deposit(p1211, "minecraft:oak_log", 6))
                    && p1211.accept(order(p1211, "minecraft:oak_log", 3, 6, false)));
            check("26.3 buys from both in one order",
                    p263.accept(order(p263, "minecraft:oak_log", 3, 12, true))
                            && p263.held("minecraft:oak_log") == 12);
            String modded = p263.refusal(deposit(p263, "create:andesite_alloy", 1));
            check("another mod's item is refused", modded != null);

            System.out.println("  [X7b: things that aren't items are refused, every way in]");
            String[] notItems = {"minecraft:air", "Not An Item!", "minecraft:not_a_real_item",
                    "oak_log", "MINECRAFT:OAK_LOG", "minecraft:oak_log ", ""};
            int refusedAll = 0;
            for (String bad : notItems) {
                boolean dep = p263.refusal(deposit(p263, bad, 1)) != null;
                boolean bid = p263.refusal(order(p263, bad, 1, 1, true)) != null;
                boolean ask = p263.refusal(order(p263, bad, 1, 1, false)) != null;
                boolean out = p263.refusal(withdraw(p263, bad, 1)) != null;
                if (dep && bid && ask && out) refusedAll++;
                else System.out.println("         (got through: '" + bad + "' dep=" + dep + " bid=" + bid
                        + " ask=" + ask + " withdraw=" + out + ")");
            }
            check("all " + notItems.length + " refused as deposit, buy, sell and withdrawal ("
                    + refusedAll + ")", refusedAll == notItems.length);
            boolean botsEverywhere = true;
            for (String item : io.github.badbull643.economiesmod.core.BotRules.ITEMS) {
                if (!ItemVersions.availableIn(item, "1.16.5")) botsEverywhere = false;
            }
            check("every item bots trade exists in every version", botsEverywhere);

            System.out.println("  [X8: holdings follow the player, not the version]");
            p263.leave();
            Player back = Player.rejoin(host, p263, "1.16.5");
            check("the 26.3 player comes back on 1.16.5", back.client.isConnected());
            check("and can still sell their poplar logs",
                    back.accept(order(back, "minecraft:poplar_log", 5, 2, false)));
            String take = back.refusal(withdraw(back, "minecraft:poplar_log", 1));
            check("but not withdraw them here", take != null && take.contains("26.3"));

            System.out.println("  [X9: the probe says the market is shared, and is signed]");
            Probe.Result r = Probe.probe("127.0.0.1", host.port, 4000);
            check("the reply verifies", r.reachable);
            check("and lists every version", r.reachable && r.reply.gameVersions != null
                    && r.reply.gameVersions.containsAll(Arrays.asList("1.16.5", "1.21.11", "26.3")));
            PeerPoll.HostInfo info = new PeerPoll.HostInfo(null, r.reply);
            check("so a 26.3 player sees it as theirs", info.servesVersion("26.3"));
            check("and a 1.20.1 one doesn't", !info.servesVersion("1.20.1"));

            p116.leave(); p1211.leave(); back.leave();
        } finally {
            host.stop();
        }

        System.out.println("  [X10: a market of one version is exactly as before]");
        {
            Running single = startHost("1.21.11", Collections.<String>emptyList());
            try {
                Player one = Player.join(single, "1.21.11", "Solo");
                check("it isn't shared", !one.client.crossVersion());
                check("and checks no items", one.accept(deposit(one, "create:andesite_alloy", 1)));
                Message.Error stillFine = oldModHello(single.port, "1.21.11");
                check("an older mod still gets past the version check",
                        stillFine == null || !HostServer.Refusal.GAME_VERSION.equals(stillFine.code));
                one.leave();
            } finally {
                single.stop();
            }
        }

        System.out.println("  [X11: a connection that isn't EconomiesMod is dropped quietly]");
        {
            Running h = startHost("1.21.11", Collections.<String>emptyList());
            java.io.PrintStream real = System.out;
            java.io.ByteArrayOutputStream seen = new java.io.ByteArrayOutputStream();
            try {
                System.setOut(new java.io.PrintStream(seen, true, "UTF-8"));
                String[] strangers = {
                        "GET / HTTP/1.1\r\nHost: example\r\n\r\n",      // a web scanner
                        "12345\n",                                       // JSON, but not an object
                        "{\"type\":\"Nope\"}\n",                         // an object, but no message
                        "\u0016\u0003\u0001\u0000¥\u0001\u0000¡\u0003\u0003Zq9xâk\n", // a TLS hello
                        // Nothing but control characters reads as a blank line: closed without
                        // a word, which is quieter still.
                        "\u0016\u0003\u0001\u0002\u0000\u0001\u0000\n",
                };
                for (String s : strangers) {
                    try (Socket sock = new Socket("127.0.0.1", h.port)) {
                        sock.getOutputStream().write(s.getBytes("ISO-8859-1"));
                        sock.getOutputStream().flush();
                        sock.setSoTimeout(2000);
                        try { sock.getInputStream().read(); } catch (IOException ignored) { }
                    }
                }
                Thread.sleep(500);
            } finally {
                System.setOut(real);
            }
            String out = seen.toString("UTF-8");
            int quiet = out.split("isn't EconomiesMod", -1).length - 1;
            check("each stranger gets one quiet line (" + quiet + " of 4)", quiet == 4);
            check("and no stack trace", !out.contains("client error") && !out.contains("\tat "));
            Player after = Player.join(h, "1.21.11", "After");
            check("a real player still gets in afterwards", after.client.isConnected());
            after.leave();
            h.stop();
        }

        System.out.println();
        System.out.println(failures == 0
                ? "ALL " + checksRun + " CHECKS PASSED"
                : failures + " OF " + checksRun + " CHECKS FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ─── players ───

    private static final class Player {
        final UUID id;
        final PlayerKeys keys;
        final Path log;
        final String name;
        final MarketClient client;
        final List<String> rejections = Collections.synchronizedList(new ArrayList<>());

        private Player(UUID id, PlayerKeys keys, Path log, String name, MarketClient client) {
            this.id = id;
            this.keys = keys;
            this.log = log;
            this.name = name;
            this.client = client;
            client.setOnRejected(rejections::add);
        }

        static Player join(Running host, String version, String name) throws Exception {
            int n = ++fileCounter;
            Path log = dir.resolve("xv-client-" + n + ".jsonl");
            Files.deleteIfExists(log);
            return connect(host, UUID.randomUUID(), PlayerKeys.generate(), log, version, name);
        }

        static Player rejoin(Running host, Player before, String version) throws Exception {
            return connect(host, before.id, before.keys, before.log, version, before.name);
        }

        private static Player connect(Running host, UUID id, PlayerKeys keys, Path log,
                                      String version, String name) throws Exception {
            Path peers = dir.resolve("xv-peers-" + (++fileCounter) + ".json");
            Files.deleteIfExists(peers);
            MarketClient c = new MarketClient(id, name, keys, new EventLog(log), true,
                    new PeerCache(peers), 0);
            c.setGameVersion(version);
            Player p = new Player(id, keys, log, name, c);
            c.connect("127.0.0.1", host.port);
            waitFor(() -> c.state().isRegistered(id) && c.state().wallets().getBalance(id) > 0);
            return p;
        }

        /** Proposes, and returns the refusal, or null once the event is in this copy. */
        String refusal(Event e) throws Exception {
            int before = rejections.size();
            long seq = client.lastSeq();
            e.timestamp = System.currentTimeMillis();
            client.propose(e);
            waitFor(() -> rejections.size() > before || client.lastSeq() > seq);
            return rejections.size() > before ? rejections.get(before) : null;
        }

        boolean accept(Event e) throws Exception {
            String why = refusal(e);
            if (why != null) System.out.println("         (refused: " + why + ")");
            return why == null;
        }

        long held(String item) throws Exception {
            waitFor(() -> client.state().itemBalances().getBalance(id, item) > 0);
            return client.state().itemBalances().getBalance(id, item);
        }

        void leave() {
            client.disconnect();
        }
    }

    private static Event.Deposit deposit(Player p, String item, long qty) {
        Event.Deposit d = new Event.Deposit();
        d.userId = p.id;
        d.itemId = item;
        d.quantity = qty;
        return d;
    }

    private static Event.Withdraw withdraw(Player p, String item, long qty) {
        Event.Withdraw w = new Event.Withdraw();
        w.userId = p.id;
        w.itemId = item;
        w.quantity = qty;
        return w;
    }

    private static Event.PlaceOrder order(Player p, String item, long price, long qty, boolean bid) {
        Event.PlaceOrder o = new Event.PlaceOrder();
        o.userId = p.id;
        o.itemId = item;
        o.price = price;
        o.volume = qty;
        o.isBid = bid;
        return o;
    }

    private interface Condition { boolean holds() throws Exception; }

    private static void waitFor(Condition c) throws Exception {
        long until = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < until) {
            if (c.holds()) return;
            Thread.sleep(20);
        }
    }

    // ─── doors ───

    private static MarketClient.Refused refusedAs(String version) throws Exception {
        Running h = current;
        int n = ++fileCounter;
        Path log = dir.resolve("xv-refused-" + n + ".jsonl");
        Files.deleteIfExists(log);
        MarketClient c = new MarketClient(UUID.randomUUID(), "Refused", PlayerKeys.generate(),
                new EventLog(log), true, new PeerCache(dir.resolve("xv-refused-peers-" + n + ".json")), 0);
        c.setGameVersion(version);
        try {
            c.connect("127.0.0.1", h.port);
        } catch (MarketClient.Refused e) {
            return e;
        }
        c.disconnect();
        return null;
    }

    /** A Hello the way a mod from before shared markets sends it: no crossVersion. */
    private static Message.Error oldModHello(int port, String version) throws Exception {
        try (MessageChannel ch = new MessageChannel(new Socket("127.0.0.1", port))) {
            Message.Hello hello = new Message.Hello();
            hello.userId = UUID.randomUUID().toString();
            hello.publicKey = PlayerKeys.generate().publicKeyString();
            hello.displayName = "OldMod";
            hello.protocolVersion = HostServer.PROTOCOL_VERSION;
            hello.gameVersion = GameVersions.toWire(version);
            ch.send(hello);
            Message reply = ch.receive();
            return reply instanceof Message.Error ? (Message.Error) reply : null;
        }
    }

    // ─── host ───

    private static Running current;

    private static final class Running {
        final HostServer host;
        final int port;
        Running(HostServer host, int port) { this.host = host; this.port = port; }
        void stop() { host.stop(); }
    }

    private static Running startHost() throws Exception {
        current = startHost(SHARED_MAIN, SHARED_ALSO);
        return current;
    }

    private static Running startHost(String main, List<String> also) throws Exception {
        int n = ++fileCounter;
        PlayerKeys hostKeys = PlayerKeys.generate();
        Path log = dir.resolve("xv-host-" + n + ".jsonl");
        Path peers = dir.resolve("xv-host-peers-" + n + ".json");
        Files.deleteIfExists(log);
        Files.deleteIfExists(peers);
        MarketBootstrap.createMarket(new EventLog(log), HOST, "shared market", hostKeys);

        ServerConfig cfg = ServerConfig.friendGroup(TestPorts.free());
        cfg.hostName = "shared";
        cfg.hostUserId = HOST.toString();
        cfg.gameVersion = main;
        cfg.gameVersions = new ArrayList<>(also);
        if (cfg.problem() != null) throw new IllegalStateException(cfg.problem());

        HostServer host = new HostServer(cfg, log, hostKeys, new PeerCache(peers));
        Thread t = new Thread(() -> {
            try { host.start(); } catch (IOException e) { /* stopped */ }
        }, "xv-test-host");
        t.setDaemon(true);
        t.start();

        IOException bindError = host.awaitBound(5000);
        if (bindError != null) throw bindError;
        return new Running(host, cfg.port);
    }
}
