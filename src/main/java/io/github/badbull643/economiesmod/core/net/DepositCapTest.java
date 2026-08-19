package io.github.badbull643.economiesmod.core.net;

import io.github.badbull643.economiesmod.core.Event;
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
import java.util.UUID;

/**
 * A deposit cap has to hold against a real client over a real socket.
 *
 * The unit checks in MarketTests cover the counting; this covers the wiring, which is
 * where a cap that counts correctly and is never consulted would still pass everything.
 * It also pins the two ordering rules the enforcement depends on: an event refused for
 * some other reason must not consume anyone's allowance, and one that is written must.
 *
 * Not part of MarketTests: that suite is pure-core and instant, and this binds sockets
 * and signs RSA events.
 */
public class DepositCapTest {

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

    /** Small enough to reach in a test, large enough to need more than one deposit. */
    private static final long CAP = 100;

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get("build", "test-scratch");
        Files.createDirectories(dir);
        for (String f : new String[]{"cap-host.jsonl", "cap-client.jsonl",
                "cap-host-peers.json", "cap-client-peers.json"}) {
            Files.deleteIfExists(dir.resolve(f));
        }

        System.out.println("  [D1: a host refuses more than its cap allows]");

        PlayerKeys hostKeys = PlayerKeys.generate();
        PlayerKeys joinerKeys = PlayerKeys.generate();

        Path hostLog = dir.resolve("cap-host.jsonl");
        EventLog log = new EventLog(hostLog);
        MarketBootstrap.createMarket(log, HOST, "cap test market", hostKeys);

        ServerConfig cfg = ServerConfig.friendGroup(freePort());
        cfg.hostName = "capped";
        cfg.hostUserId = HOST.toString();
        cfg.maxDepositUnitsPerWindow = CAP;
        cfg.depositWindowMinutes = 60;

        HostServer host = new HostServer(cfg, hostLog, hostKeys,
                new PeerCache(dir.resolve("cap-host-peers.json")));
        Thread t = new Thread(() -> {
            try { host.start(); } catch (IOException e) { /* stopped */ }
        }, "cap-test-host");
        t.setDaemon(true);
        t.start();

        IOException bindError = host.awaitBound(5000);
        if (bindError != null) throw bindError;

        final String[] refusal = new String[1];

        try {
            Path clientLog = dir.resolve("cap-client.jsonl");
            MarketClient client = new MarketClient(JOINER, "Joiner", joinerKeys,
                    new EventLog(clientLog), true,
                    new PeerCache(dir.resolve("cap-client-peers.json")), 0);
            client.setOnRejected(reason -> refusal[0] = reason);
            client.connect("127.0.0.1", cfg.port);

            long headBefore = client.lastSeq();

            // Well under the cap. Has to be accepted, or the test proves only that the
            // server refuses things.
            deposit(client, 60);
            long afterFirst = awaitSeqAbove(client, headBefore);
            check("a deposit under the cap is accepted", afterFirst > headBefore ? 1 : 0, 1);

            // 60 + 60 is over 100. The allowance is what refuses this, not the balance.
            deposit(client, 60);
            awaitRefusal(refusal);
            check("the one that breaches it is refused", refusal[0] != null ? 1 : 0, 1);
            check("and says so in terms of the limit",
                    refusal[0] != null && refusal[0].contains("deposit limit") ? 1 : 0, 1);

            // A refusal must cost nothing. If the breaching attempt had been counted,
            // the 40 still owed to this identity would have been eaten by it.
            refusal[0] = null;
            long headBeforeThird = client.lastSeq();
            deposit(client, 40);
            long afterThird = awaitSeqAbove(client, headBeforeThird);
            check("a refused deposit consumed no allowance",
                    afterThird > headBeforeThird ? 1 : 0, 1);
            check("and nothing was refused this time", refusal[0] == null ? 1 : 0, 1);

            // Now exactly at the cap: 60 + 40 = 100.
            deposit(client, 1);
            awaitRefusal(refusal);
            check("one unit past the cap is refused", refusal[0] != null ? 1 : 0, 1);

            client.disconnect();
        } finally {
            host.stop();
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL " + checksRun + " CHECKS PASSED");
        } else {
            System.out.println(failures + " of " + checksRun + " checks FAILED");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void deposit(MarketClient client, long qty) {
        Event.Deposit d = new Event.Deposit();
        d.userId = JOINER;
        d.itemId = IRON;
        d.quantity = qty;
        d.timestamp = System.currentTimeMillis();
        client.propose(d);
    }

    private static long awaitSeqAbove(MarketClient client, long was)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && client.lastSeq() <= was) {
            Thread.sleep(25);
        }
        return client.lastSeq();
    }

    private static void awaitRefusal(String[] slot) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && slot[0] == null) {
            Thread.sleep(25);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
