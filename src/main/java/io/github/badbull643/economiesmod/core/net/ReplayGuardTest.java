package io.github.badbull643.economiesmod.core.net;

import io.github.badbull643.economiesmod.core.AppliedEvent;
import io.github.badbull643.economiesmod.core.Event;
import io.github.badbull643.economiesmod.core.EventCanonical;
import io.github.badbull643.economiesmod.core.EventLog;
import io.github.badbull643.economiesmod.core.MarketBootstrap;
import io.github.badbull643.economiesmod.core.PeerCache;
import io.github.badbull643.economiesmod.core.PlayerKeys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Replayed history must be distinguishable from live traffic.
 *
 * Joining a market syncs its entire log, and every event in it is applied exactly as a
 * broadcast would be. Anything downstream with an effect outside the ledger — handing a
 * player items, sending a notification — therefore has to be told which is which, or it
 * repeats the whole history on every join.
 *
 * The concrete failure this guards: withdraw items, reset the log, connect again. The
 * sync replays your own Withdraw, the client hands over the items a second time, and
 * the ledger still says you withdrew once. Repeatable for as long as you care to.
 *
 * Not part of MarketTests: that suite is pure-core and instant, and this binds sockets
 * and signs RSA events.
 */
public class ReplayGuardTest {

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

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get("build", "test-scratch");
        Files.createDirectories(dir);

        Path hostLog = dir.resolve("replay-host.jsonl");
        Path joinerLog = dir.resolve("replay-joiner.jsonl");
        for (String f : new String[]{"replay-host.jsonl", "replay-joiner.jsonl",
                "replay-host-peers.json", "replay-joiner-peers.json", "known-keys.json"}) {
            Files.deleteIfExists(dir.resolve(f));
        }

        System.out.println("  [P1: a synced history is not mistaken for live traffic]");

        PlayerKeys hostKeys = PlayerKeys.generate();
        PlayerKeys joinerKeys = PlayerKeys.generate();

        // Build a history in which the joiner has already withdrawn. This is exactly
        // what a player's own log looks like before they reset it.
        EventLog log = new EventLog(hostLog);
        MarketBootstrap.createMarket(log, HOST, "replay test market", hostKeys);
        UUID marketId = log.marketId();
        // Counted from here, not from zero: what this builds is three events on top of
        // whatever genesis writes, and genesis is free to grow.
        long afterGenesis = log.lastSeq();

        Event.KeyRegistered kr = new Event.KeyRegistered();
        kr.userId = JOINER;
        kr.marketId = marketId;
        kr.publicKey = joinerKeys.publicKeyString();
        kr.timestamp = System.currentTimeMillis();
        log.append(kr, joinerKeys.sign(EventCanonical.canonicalPayload(kr)));

        Event.Deposit dep = new Event.Deposit();
        dep.userId = JOINER;
        dep.marketId = marketId;
        dep.itemId = IRON;
        dep.quantity = 10;
        dep.timestamp = System.currentTimeMillis();
        log.append(dep, joinerKeys.sign(EventCanonical.canonicalPayload(dep)));

        Event.Withdraw wd = new Event.Withdraw();
        wd.userId = JOINER;
        wd.marketId = marketId;
        wd.itemId = IRON;
        wd.quantity = 10;
        wd.timestamp = System.currentTimeMillis();
        log.append(wd, joinerKeys.sign(EventCanonical.canonicalPayload(wd)));

        check("history built", log.lastSeq() - afterGenesis, 3);

        int port = freePort();
        HostServer host = new HostServer(port, hostLog, "replayhost", HOST.toString(),
                hostKeys, new PeerCache(dir.resolve("replay-host-peers.json")), 0L);

        Thread hostThread = new Thread(() -> {
            try { host.start(); } catch (IOException e) { /* stopped */ }
        }, "replay-test-host");
        hostThread.setDaemon(true);
        hostThread.start();

        IOException bindError = host.awaitBound(5000);
        if (bindError != null) throw bindError;

        final List<AppliedEvent> seen = new ArrayList<>();

        try {
            // A fresh log, as if the joiner had just reset.
            EventLog fresh = new EventLog(joinerLog);
            MarketClient client = new MarketClient(JOINER, "Joiner", joinerKeys,
                    fresh, true, new PeerCache(dir.resolve("replay-joiner-peers.json")), 0);
            client.setOnApplied(a -> { synchronized (seen) { seen.add(a); } });
            client.connect("127.0.0.1", port);

            int replayedWithdraws = 0;
            int liveDuringSync = 0;
            synchronized (seen) {
                for (AppliedEvent a : seen) {
                    if (a.live) liveDuringSync++;
                    if (a.event.event instanceof Event.Withdraw
                            && JOINER.equals(a.event.event.userId)) {
                        replayedWithdraws++;
                        check("the replayed withdraw is not marked live", a.live ? 1 : 0, 0);
                    }
                }
            }
            check("the joiner's withdraw was replayed at all", replayedWithdraws, 1);
            check("nothing in a sync claims to be live", liveDuringSync, 0);

            // And the guard must not swallow real traffic: something authored now has
            // to come back live, or the flag would simply disable the handler forever.
            int before;
            synchronized (seen) { before = seen.size(); }

            Event.Deposit live = new Event.Deposit();
            live.userId = JOINER;
            live.itemId = IRON;
            live.quantity = 3;
            live.timestamp = System.currentTimeMillis();
            client.propose(live);

            long deadline = System.currentTimeMillis() + 5000;
            int liveSeen = 0;
            while (System.currentTimeMillis() < deadline) {
                synchronized (seen) {
                    if (seen.size() > before) {
                        liveSeen = 0;
                        for (int i = before; i < seen.size(); i++) {
                            if (seen.get(i).live) liveSeen++;
                        }
                        if (liveSeen > 0) break;
                    }
                }
                Thread.sleep(50);
            }
            check("an event authored now arrives live", liveSeen > 0 ? 1 : 0, 1);

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

    private static int freePort() throws IOException {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
