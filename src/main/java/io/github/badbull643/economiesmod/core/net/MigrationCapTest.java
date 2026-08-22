package io.github.badbull643.economiesmod.core.net;

import io.github.badbull643.economiesmod.core.Event;
import io.github.badbull643.economiesmod.core.EventCanonical;
import io.github.badbull643.economiesmod.core.EventLog;
import io.github.badbull643.economiesmod.core.MarketBootstrap;
import io.github.badbull643.economiesmod.core.PeerCache;
import io.github.badbull643.economiesmod.core.PlayerKeys;
import io.github.badbull643.economiesmod.core.ServerConfig;
import io.github.badbull643.economiesmod.core.WorldAttestation;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

/**
 * Migration is the other way items enter a market, and it has to answer to the same
 * rules as a deposit.
 *
 * DepositCapTest covers the Deposit path. This covers the one beside it: a
 * MigrateBalance carries items too, but it is written by the host as its own event
 * through a pre-handshake exchange, so none of the checks hung off a proposal see it.
 * A market that refuses a 5000-item deposit and accepts a 5000-item migration has a
 * cap in name only — and the migrating branch can be a market the sender created
 * themselves, in a world of their choosing, which is what makes it worth closing.
 *
 * Not part of MarketTests: that suite is pure-core and instant, and this binds sockets
 * and verifies RSA-signed histories.
 */
public class MigrationCapTest {

    private static int failures = 0;
    private static int checksRun = 0;

    private static void check(String label, long actual, long expected) {
        checksRun++;
        boolean ok = actual == expected;
        if (!ok) failures++;
        System.out.println((ok ? "    ok   " : "    FAIL ") + label
                + " — expected " + expected + ", got " + actual);
    }

    private static final UUID HOST        = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID MIGRANT     = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID BENEFICIARY = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final String IRON      = "minecraft:iron_ingot";

    /** Small enough to reach in a test, large enough to need more than one arrival. */
    private static final long CAP = 100;

    private static Path dir;

    public static void main(String[] args) throws Exception {
        dir = Paths.get("build", "test-scratch");
        Files.createDirectories(dir);

        overCapIsRefused();
        underCapIsAccepted();
        migrationSpendsTheAllowance();
        aClientCannotWriteItsOwnMigration();
        attestationIsRequiredHereToo();
        aCreativeWorldIsRefused();
        statisticsContradictAFabricatedMarket();
        theStatisticsRuleAccumulates();

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL " + checksRun + " CHECKS PASSED");
        } else {
            System.out.println(failures + " of " + checksRun + " checks FAILED");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * The attack in one check: a market the sender built themselves, holding far more
     * than this host will accept from anyone, arriving by the path that writes no
     * Deposit.
     */
    private static void overCapIsRefused() throws Exception {
        System.out.println("  [M1: a migration over the cap is refused]");

        ServerConfig cfg = cappedConfig();
        HostServer host = start(cfg, "mig-cap-host.jsonl");
        try {
            List<String> branch = foreignBranchHolding(5000, "mig-fat.jsonl");
            Message.MigrateResult result = MarketClient.requestMigration(
                    "127.0.0.1", cfg.port, MIGRANT, branch, null);

            check("refused", result != null && !result.accepted ? 1 : 0, 1);
            check("and says why, in terms of the limit",
                    result != null && result.reason != null
                            && result.reason.contains("deposit limit") ? 1 : 0, 1);
        } finally {
            host.stop();
        }
    }

    /** So M1 proves a cap and not a wall. */
    private static void underCapIsAccepted() throws Exception {
        System.out.println("  [M2: a migration under the cap still works]");

        ServerConfig cfg = cappedConfig();
        HostServer host = start(cfg, "mig-ok-host.jsonl");
        try {
            List<String> branch = foreignBranchHolding(60, "mig-slim.jsonl");
            Message.MigrateResult result = MarketClient.requestMigration(
                    "127.0.0.1", cfg.port, MIGRANT, branch, null);

            check("accepted", result != null && result.accepted ? 1 : 0, 1);
        } finally {
            host.stop();
        }
    }

    /**
     * The half a per-migration ceiling alone would miss. If migrated items are simply
     * exempt, the allowance is untouched and the same identity can walk in with a
     * migration and then deposit its full budget on top of it.
     */
    private static void migrationSpendsTheAllowance() throws Exception {
        System.out.println("  [M3: migrated items consume the deposit allowance]");

        ServerConfig cfg = cappedConfig();
        HostServer host = start(cfg, "mig-spend-host.jsonl");
        try {
            List<String> branch = foreignBranchHolding(60, "mig-spend-foreign.jsonl");
            Message.MigrateResult migrated = MarketClient.requestMigration(
                    "127.0.0.1", cfg.port, MIGRANT, branch, null);
            check("the migration lands", migrated != null && migrated.accepted ? 1 : 0, 1);

            // Same identity, arriving normally now, with 40 of its 100 left.
            Path clientLog = dir.resolve("mig-spend-client.jsonl");
            Files.deleteIfExists(clientLog);
            MarketClient client = new MarketClient(MIGRANT, "Migrant",
                    PlayerKeys.generate(), new EventLog(clientLog), true,
                    new PeerCache(dir.resolve("mig-spend-client-peers.json")), 0);

            client.connect("127.0.0.1", cfg.port);

            Event.Deposit d = new Event.Deposit();
            d.userId = MIGRANT;
            d.itemId = IRON;
            d.quantity = 60;               // 60 migrated + 60 deposited is over 100
            d.timestamp = System.currentTimeMillis();
            client.propose(d);

            // On the balance rather than on a refusal string: an unrelated rejection
            // arrives on this connection too — the welcome grant this identity is no
            // longer entitled to, having been accounted for by the migration — and a
            // test that merely counts refusals passes on that one instead.
            awaitQuiet(client);
            check("the deposit did not land",
                    client.state().itemBalances().getBalance(MIGRANT, IRON), 60);

            client.disconnect();
        } finally {
            host.stop();
        }
    }

    /**
     * The shortcut past the whole exchange.
     *
     * handleMigrate is careful: it verifies the branch, recomputes the position from it
     * and never takes the numbers from the request. But MigrateBalance is an ordinary
     * event type, so a registered client can also just propose one — and that route
     * reaches none of that care. There is no branch to verify because none was sent;
     * fromMarketId is whatever the sender typed, so the replay guard is defeated by
     * picking a fresh one each time.
     */
    private static void aClientCannotWriteItsOwnMigration() throws Exception {
        System.out.println("  [M4: a client cannot propose a MigrateBalance of its own]");

        ServerConfig cfg = cappedConfig();
        HostServer host = start(cfg, "mig-forge-host.jsonl");
        try {
            Path clientLog = dir.resolve("mig-forge-client.jsonl");
            Files.deleteIfExists(clientLog);
            MarketClient client = new MarketClient(MIGRANT, "Migrant",
                    PlayerKeys.generate(), new EventLog(clientLog), true,
                    new PeerCache(dir.resolve("mig-forge-client-peers.json")), 0);

            client.connect("127.0.0.1", cfg.port);

            Event.MigrateBalance mb = new Event.MigrateBalance();
            mb.userId = MIGRANT;                     // authored by the client itself
            mb.fromMarketId = UUID.randomUUID();     // a market that never existed
            mb.fromMarketName = "nowhere";
            mb.fromHeadSeq = 1;
            mb.fromHeadHash = "made up";
            mb.beneficiary = BENEFICIARY;            // an identity holding nothing here
            mb.credits = 1_000_000;
            mb.items = new java.util.TreeMap<>();
            mb.items.put(IRON, 5000L);
            mb.foreignParticipants = java.util.Collections.singletonList(BENEFICIARY);
            mb.timestamp = System.currentTimeMillis();
            client.propose(mb);

            awaitQuiet(client);
            check("no credits were minted",
                    client.state().wallets().getBalance(BENEFICIARY), 0);
            check("and no items were",
                    client.state().itemBalances().getBalance(BENEFICIARY, IRON), 0);

            client.disconnect();
        } finally {
            host.stop();
        }
    }

    /**
     * A server that insists on knowing where goods came from has to insist on both
     * doors, or it has only asked the people who were going to walk through the polite
     * one anyway.
     */
    private static void attestationIsRequiredHereToo() throws Exception {
        System.out.println("  [M5: a migration with nothing to say is refused"
                + " when attestation is required]");

        ServerConfig cfg = cappedConfig();
        cfg.maxDepositUnitsPerWindow = 0;         // so only attestation can refuse it
        cfg.requireAttestation = true;
        HostServer host = start(cfg, "mig-attest-host.jsonl");
        try {
            List<String> branch = foreignBranchHolding(60, "mig-attest-foreign.jsonl");
            Message.MigrateResult result = MarketClient.requestMigration(
                    "127.0.0.1", cfg.port, MIGRANT, branch, null);

            check("refused", result != null && !result.accepted ? 1 : 0, 1);
            check("for saying nothing about the world",
                    result != null && result.reason != null
                            && result.reason.contains("world") ? 1 : 0, 1);
        } finally {
            host.stop();
        }
    }

    /** The world the attack is actually launched from. */
    private static void aCreativeWorldIsRefused() throws Exception {
        System.out.println("  [M6: a migration out of a creative world is refused]");

        ServerConfig cfg = cappedConfig();
        cfg.maxDepositUnitsPerWindow = 0;
        cfg.refuseCreativeWorlds = true;
        HostServer host = start(cfg, "mig-creative-host.jsonl");
        try {
            WorldAttestation creative = new WorldAttestation();
            creative.gameMode = "creative";
            creative.worldAgeTicks = WorldAttestation.TICKS_PER_HOUR * 40;

            List<String> branch = foreignBranchHolding(60, "mig-creative-foreign.jsonl");
            Message.MigrateResult result = MarketClient.requestMigration(
                    "127.0.0.1", cfg.port, MIGRANT, branch, creative);

            check("refused", result != null && !result.accepted ? 1 : 0, 1);
            check("for being creative",
                    result != null && result.reason != null
                            && result.reason.contains("creative") ? 1 : 0, 1);
        } finally {
            host.stop();
        }
    }

    /**
     * The check that costs a liar something even in survival.
     *
     * A market fabricated to be migrated has goods behind it that were never mined,
     * crafted or picked up, and Minecraft's own statistics say so — /give increments
     * none of them. Claiming otherwise means editing a record the mod does not own.
     */
    private static void statisticsContradictAFabricatedMarket() throws Exception {
        System.out.println("  [M7: a migration beyond what the migrant has ever"
                + " handled is refused]");

        ServerConfig cfg = cappedConfig();
        cfg.maxDepositUnitsPerWindow = 0;
        cfg.maxDepositMultipleOfHandled = 3;
        HostServer host = start(cfg, "mig-stats-host.jsonl");
        try {
            WorldAttestation honest = new WorldAttestation();
            honest.gameMode = "survival";
            honest.worldAgeTicks = WorldAttestation.TICKS_PER_HOUR * 40;
            honest.handledByItem = new java.util.TreeMap<>();
            honest.handledByItem.put(IRON, 12L);   // 12 ever handled, 5000 in the market

            List<String> branch = foreignBranchHolding(5000, "mig-stats-foreign.jsonl");
            Message.MigrateResult result = MarketClient.requestMigration(
                    "127.0.0.1", cfg.port, MIGRANT, branch, honest);

            check("refused", result != null && !result.accepted ? 1 : 0, 1);
            check("against the migrant's own statistics",
                    result != null && result.reason != null
                            && result.reason.contains("statistics") ? 1 : 0, 1);

            // And the honest case still passes, or this is a wall rather than a rule.
            WorldAttestation plenty = new WorldAttestation();
            plenty.gameMode = "survival";
            plenty.worldAgeTicks = WorldAttestation.TICKS_PER_HOUR * 40;
            plenty.handledByItem = new java.util.TreeMap<>();
            plenty.handledByItem.put(IRON, 40L);   // 40 x 3 leaves room for 60

            List<String> small = foreignBranchHolding(60, "mig-stats-ok-foreign.jsonl");
            Message.MigrateResult ok = MarketClient.requestMigration(
                    "127.0.0.1", cfg.port, MIGRANT, small, plenty);

            check("a plausible one still lands", ok != null && ok.accepted ? 1 : 0, 1);
        } finally {
            host.stop();
        }
    }

    /**
     * A rule that only ever sees one arrival at a time is answered by making two.
     *
     * The statistics multiple is the check that survives everything else — it is the
     * one figure the depositor did not write. But it compares against a running total,
     * and that total is only kept when the host decided it needed counting. With the
     * multiple configured on its own, nothing was counted, so every migration was
     * weighed alone and a player could bring in their allowance again and again by
     * splitting it across markets they made for the purpose.
     */
    private static void theStatisticsRuleAccumulates() throws Exception {
        System.out.println("  [M8: repeated migrations are weighed together]");

        ServerConfig cfg = cappedConfig();
        cfg.maxDepositUnitsPerWindow = 0;         // the multiple is the only rule set
        cfg.maxDepositMultipleOfHandled = 3;
        HostServer host = start(cfg, "mig-accum-host.jsonl");
        try {
            WorldAttestation claim = new WorldAttestation();
            claim.gameMode = "survival";
            claim.worldAgeTicks = WorldAttestation.TICKS_PER_HOUR * 40;
            claim.handledByItem = new java.util.TreeMap<>();
            claim.handledByItem.put(IRON, 40L);   // 40 x 3 = 120 allowed in total

            Message.MigrateResult first = MarketClient.requestMigration(
                    "127.0.0.1", cfg.port, MIGRANT,
                    foreignBranchHolding(100, "mig-accum-a.jsonl"), claim);
            check("the first 100 lands", first != null && first.accepted ? 1 : 0, 1);

            // A different market, so the replay guard has no opinion — 100 + 60 is 160
            // against an allowance of 120, and only a running total can see that.
            Message.MigrateResult second = MarketClient.requestMigration(
                    "127.0.0.1", cfg.port, MIGRANT,
                    foreignBranchHolding(60, "mig-accum-b.jsonl"), claim);
            check("a second one taking it past the allowance is refused",
                    second != null && !second.accepted ? 1 : 0, 1);
        } finally {
            host.stop();
        }
    }

    // ─────────── scaffolding ───────────

    private static ServerConfig cappedConfig() throws IOException {
        ServerConfig cfg = ServerConfig.friendGroup(freePort());
        cfg.hostName = "capped";
        cfg.hostUserId = HOST.toString();
        cfg.maxDepositUnitsPerWindow = CAP;
        cfg.depositWindowMinutes = 60;
        return cfg;
    }

    private static HostServer start(ServerConfig cfg, String logName) throws Exception {
        Path hostLog = dir.resolve(logName);
        Files.deleteIfExists(hostLog);
        PlayerKeys hostKeys = PlayerKeys.generate();
        EventLog log = new EventLog(hostLog);
        MarketBootstrap.createMarket(log, HOST, "migration cap market", hostKeys);

        HostServer host = new HostServer(cfg, hostLog, hostKeys,
                new PeerCache(dir.resolve(logName + ".peers.json")));
        Thread t = new Thread(() -> {
            try { host.start(); } catch (IOException e) { /* stopped */ }
        }, "migration-cap-host");
        t.setDaemon(true);
        t.start();

        IOException bindError = host.awaitBound(5000);
        if (bindError != null) throw bindError;
        return host;
    }

    /**
     * A genuine, fully signed market in which MIGRANT holds the given quantity — which
     * is exactly what an attacker can build, unaided, in a world of their choosing.
     * Verification passes; that is the point. Nothing about a valid branch says the
     * items in it were come by honestly.
     */
    private static List<String> foreignBranchHolding(long qty, String logName)
            throws Exception {
        Path foreignLog = dir.resolve(logName);
        Files.deleteIfExists(foreignLog);

        PlayerKeys migrantKeys = PlayerKeys.generate();
        PlayerKeys founderKeys = PlayerKeys.generate();

        EventLog foreign = new EventLog(foreignLog);
        MarketBootstrap.createMarket(foreign, HOST, "a market of my own", founderKeys);
        UUID foreignId = foreign.marketId();

        Event.KeyRegistered kr = new Event.KeyRegistered();
        kr.userId = MIGRANT;
        kr.marketId = foreignId;
        kr.publicKey = migrantKeys.publicKeyString();
        kr.timestamp = System.currentTimeMillis();
        foreign.append(kr, migrantKeys.sign(EventCanonical.canonicalPayload(kr)));

        Event.Deposit dep = new Event.Deposit();
        dep.userId = MIGRANT;
        dep.marketId = foreignId;
        dep.itemId = IRON;
        dep.quantity = qty;
        dep.timestamp = System.currentTimeMillis();
        foreign.append(dep, migrantKeys.sign(EventCanonical.canonicalPayload(dep)));

        return Files.readAllLines(foreignLog);
    }

    /** Gives a write that should not happen time to happen, then reports the head. */
    private static long awaitQuiet(MarketClient client) throws InterruptedException {
        Thread.sleep(500);
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
