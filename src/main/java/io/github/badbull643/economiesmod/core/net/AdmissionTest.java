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
import java.util.UUID;

/**
 * A server's admission policy has to hold on every door, not just the front one.
 *
 * The handshake is the obvious place to check, and checking only there would look
 * correct in any test that connects normally. But two exchanges happen *before* a
 * handshake and both write to the log: MigrateRequest credits the sender a balance
 * carried from another market, and CatchUp appends events to this server's copy. An
 * allowlist enforced only at Hello would be bypassable by the one path that hands out
 * money.
 *
 * What this does not test, because it is not true: that admission stops cheating. A
 * player's world is their own, and goods from a creative world deposit through the
 * completely honest client path. Admission decides who may talk to this server, which
 * is a smaller and more defensible claim.
 *
 * Not part of MarketTests: that suite is pure-core and instant, and this binds sockets
 * and signs RSA events.
 */
public class AdmissionTest {

    private static int failures = 0;
    private static int checksRun = 0;

    private static void check(String label, long actual, long expected) {
        checksRun++;
        boolean ok = actual == expected;
        if (!ok) failures++;
        System.out.println((ok ? "    ok   " : "    FAIL ") + label
                + " — expected " + expected + ", got " + actual);
    }

    private static final UUID HOST    = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID INVITED = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OUTSIDER= UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static Path dir;
    private static PlayerKeys hostKeys;

    public static void main(String[] args) throws Exception {
        dir = Paths.get("build", "test-scratch");
        Files.createDirectories(dir);
        for (String f : new String[]{"adm-host.jsonl", "adm-invited.jsonl",
                "adm-outsider.jsonl", "adm-foreign.jsonl", "adm-host-peers.json",
                "adm-client-peers.json"}) {
            Files.deleteIfExists(dir.resolve(f));
        }

        hostKeys = PlayerKeys.generate();
        Path hostLog = dir.resolve("adm-host.jsonl");
        EventLog log = new EventLog(hostLog);
        MarketBootstrap.createMarket(log, HOST, "admission test market", hostKeys);

        ServerConfig cfg = ServerConfig.friendGroup(TestPorts.free());
        cfg.hostName = "gatekeeper";
        cfg.hostUserId = HOST.toString();
        cfg.admission = ServerConfig.ALLOWLIST;
        cfg.allow.add(INVITED.toString());

        HostServer host = new HostServer(cfg, hostLog, hostKeys,
                new PeerCache(dir.resolve("adm-host-peers.json")));
        Thread t = new Thread(() -> {
            try { host.start(); } catch (IOException e) { /* stopped */ }
        }, "admission-test-host");
        t.setDaemon(true);
        t.start();

        IOException bindError = host.awaitBound(5000);
        if (bindError != null) throw bindError;

        try {
            invitedGetsIn(cfg.port);
            outsiderIsTurnedAway(cfg.port);
            migrationIsGatedToo(cfg.port);
            catchUpIsGatedToo(cfg.port);
        } finally {
            host.stop();
        }

        peerSharingRespectsDedicated();
        aFailedStartWritesNothing();
        migrationIsWeighedAgainstStatistics();
        aDedicatedServerDeclinesMigration();
        aHostCapsTheWelcomeGrant();
        aHostTakesUpTheGroupsPublishedRules();
        onlyArchivistsKeepADedicatedMarketsHistory();
        archivingOnFetchesTheHistoryItPromised();

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL " + checksRun + " CHECKS PASSED");
        } else {
            System.out.println(failures + " of " + checksRun + " checks FAILED");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * A8: a dedicated server declines migrations, and says what to do instead.
     *
     * Migration solves bootstrapping among people who know each other. The balance it
     * carries was set by a welcome grant the migrant chose, in a world they control, up
     * to MAX_WELCOME_GRANT — fine between friends, and "name your opening balance" on a
     * public box. So a dedicated host declines by default.
     *
     * Over the wire rather than against ServerConfig, because the default resolving
     * correctly is only half of it: this is the half that proves the refusal is wired to
     * the path that hands out money, and that it comes back before the sender has
     * uploaded a whole history.
     */
    private static void aDedicatedServerDeclinesMigration() throws Exception {
        System.out.println("  [A8: a dedicated server declines migrations by default]");

        Path hostLog = dir.resolve("adm-dedicated.jsonl");
        Path foreignLog = dir.resolve("adm-dedicated-foreign.jsonl");
        Files.deleteIfExists(hostLog);
        Files.deleteIfExists(foreignLog);

        PlayerKeys boxKeys = PlayerKeys.generate();
        EventLog log = new EventLog(hostLog);
        MarketBootstrap.createMarket(log, HOST, "public market", boxKeys);

        ServerConfig cfg = ServerConfig.friendGroup(TestPorts.free());
        cfg.hostName = "the box";
        cfg.hostUserId = HOST.toString();
        cfg.dedicated = true;          // the only thing that differs
        check("which is enough to decline them", cfg.acceptsMigration() ? 1 : 0, 0);

        HostServer host = new HostServer(cfg, hostLog, boxKeys,
                new PeerCache(dir.resolve("adm-host-peers.json")));
        Thread t = new Thread(() -> {
            try { host.start(); } catch (IOException e) { /* stopped */ }
        }, "dedicated-migration-test-host");
        t.setDaemon(true);
        t.start();
        IOException bindError = host.awaitBound(5000);
        if (bindError != null) throw bindError;

        try {
            // A market the migrant genuinely holds a position in, so nothing else could
            // be the reason for the refusal.
            PlayerKeys mine = PlayerKeys.generate();
            EventLog foreign = new EventLog(foreignLog);
            MarketBootstrap.createMarket(foreign, INVITED, "my own market", mine);

            Event.Deposit dep = new Event.Deposit();
            dep.userId = INVITED;
            dep.marketId = foreign.marketId();
            dep.itemId = "minecraft:iron_ingot";
            dep.quantity = 10;
            dep.timestamp = System.currentTimeMillis();
            foreign.append(dep, mine.sign(EventCanonical.canonicalPayload(dep)));

            Message.MigrateResult result = MarketClient.requestMigration(
                    "127.0.0.1", cfg.port, INVITED,
                    Files.readAllLines(foreignLog), null);

            check("migration refused", result != null && !result.accepted ? 1 : 0, 1);
            // The refusal has to name the way in, or somebody reads "no" as "you cannot
            // play here" and goes away.
            check("and points at adding a market slot instead",
                    result != null && result.reason != null
                            && result.reason.contains("add another market") ? 1 : 0, 1);
            check("saying their own market is untouched",
                    result != null && result.reason != null
                            && result.reason.contains("stays exactly as it is") ? 1 : 0, 1);

            // And an operator who wants them can have them, or the default is a rule.
            cfg.acceptsMigration = Boolean.TRUE;
            Message.MigrateResult allowed = MarketClient.requestMigration(
                    "127.0.0.1", cfg.port, INVITED,
                    Files.readAllLines(foreignLog), null);
            check("turning it on lets one through",
                    allowed != null && allowed.accepted ? 1 : 0, 1);
        } finally {
            host.stop();
        }
    }

    /**
     * A9: a host refuses to sequence a welcome grant above its own ceiling.
     *
     * Over the wire, because the whole design of this rests on it being a *host* rule
     * rather than a replicated one, and a config field nobody consults is not a rule at
     * all. R1b covers the figures; this covers the refusal actually reaching the path
     * that writes policy into the log.
     */
    private static void aHostCapsTheWelcomeGrant() throws Exception {
        System.out.println("  [A9: a host caps the welcome grant it will sequence]");

        Path hostLog = dir.resolve("adm-grant-cap.jsonl");
        Files.deleteIfExists(hostLog);
        Files.deleteIfExists(dir.resolve("adm-grant-client.jsonl"));

        PlayerKeys keys = PlayerKeys.generate();
        EventLog log = new EventLog(hostLog);
        // INVITED creates it, so INVITED is the creator and may set policy at all.
        MarketBootstrap.createMarket(log, INVITED, "capped market", keys);

        ServerConfig cfg = ServerConfig.friendGroup(TestPorts.free());
        cfg.hostName = "somebody's game";
        cfg.hostUserId = HOST.toString();
        cfg.maxWelcomeGrant = 500L;
        // Under the cap, or the host refuses to start — which is problem() catching a
        // server that would decline to sequence the grant it had just bootstrapped with,
        // and it caught this fixture the first time it ran.
        cfg.welcomeGrant = 100L;

        HostServer host = new HostServer(cfg, hostLog, keys,
                new PeerCache(dir.resolve("adm-host-peers.json")));
        Thread t = new Thread(() -> {
            try { host.start(); } catch (IOException e) { /* stopped */ }
        }, "grant-cap-test-host");
        t.setDaemon(true);
        t.start();
        IOException bindError = host.awaitBound(5000);
        if (bindError != null) throw bindError;

        try {
            MarketClient client = new MarketClient(INVITED, "Creator", keys,
                    new EventLog(dir.resolve("adm-grant-client.jsonl")), true,
                    new PeerCache(dir.resolve("adm-client-peers.json")), 0);
            final String[] refusal = new String[1];
            client.setOnRejected(why -> refusal[0] = why);
            client.connect("127.0.0.1", cfg.port);

            // From the file, never from this EventLog instance. lastSeq is cached when
            // the object is built and the host appends through its own — so asking the
            // test's copy measures nothing at all, and passes by never changing. Same
            // staleness MarketClient had, found here by the check that would not move.
            long before = eventsIn(hostLog);
            client.propose(policyGranting(600));
            Thread.sleep(1200);

            check("a grant over the ceiling is refused", eventsIn(hostLog), before);
            check("and says what this host allows",
                    String.valueOf(refusal[0]).contains("500") ? 1 : 0, 1);

            // Under it goes through, or the cap would be a wall rather than a ceiling.
            refusal[0] = null;
            client.propose(policyGranting(400));
            Thread.sleep(1200);
            check("one under it is sequenced", eventsIn(hostLog), before + 1);
            check("with nothing refused", refusal[0] == null ? 1 : 0, 1);

            client.disconnect();
        } finally {
            host.stop();
        }
    }

    /** Events actually on disk, read fresh every call. */
    private static long eventsIn(Path log) throws IOException {
        long n = 0;
        for (String line : Files.readAllLines(log)) {
            if (line != null && !line.trim().isEmpty()) n++;
        }
        return n;
    }

    /** A whole policy, since a MarketPolicy that omits a field sets it to zero. */
    private static Event.MarketPolicy policyGranting(long grant) {
        Event.MarketPolicy mp = new Event.MarketPolicy();
        mp.userId = INVITED;
        mp.grantAmount = grant;
        mp.timestamp = System.currentTimeMillis();
        return mp;
    }

    /** The policy has to let the right people through, or it is just a closed door. */
    private static void invitedGetsIn(int port) throws Exception {
        System.out.println("  [A1: an invited identity connects normally]");

        MarketClient client = client(INVITED, "adm-invited.jsonl");
        client.connect("127.0.0.1", port);
        check("connected", client.isConnected() ? 1 : 0, 1);
        client.disconnect();
    }

    private static void outsiderIsTurnedAway(int port) throws Exception {
        System.out.println("  [A2: an unlisted identity is refused at the handshake]");

        MarketClient client = client(OUTSIDER, "adm-outsider.jsonl");
        String code = null;
        String reason = null;
        try {
            client.connect("127.0.0.1", port);
        } catch (MarketClient.Refused e) {
            code = e.code;
            reason = e.getMessage();
        }
        check("refused", code != null ? 1 : 0, 1);
        check("with a code the client can recognise",
                HostServer.Refusal.NOT_ADMITTED.equals(code) ? 1 : 0, 1);
        check("and a reason that says why",
                reason != null && reason.contains("invited") ? 1 : 0, 1);
        check("and is not connected", client.isConnected() ? 1 : 0, 0);
    }

    /**
     * A2 passing does not imply this. Migration is answered before any handshake, so a
     * check placed only at Hello leaves it open — and this is the path that credits a
     * balance.
     */
    private static void migrationIsGatedToo(int port) throws Exception {
        System.out.println("  [A3: migration is refused for an unlisted identity]");

        // A real foreign market the outsider genuinely holds a position in, so the only
        // thing that can refuse this is the admission policy.
        Path foreignLog = dir.resolve("adm-foreign.jsonl");
        Files.deleteIfExists(foreignLog);
        PlayerKeys outsiderKeys = PlayerKeys.generate();
        PlayerKeys otherHostKeys = PlayerKeys.generate();

        EventLog foreign = new EventLog(foreignLog);
        MarketBootstrap.createMarket(foreign, HOST, "somewhere else", otherHostKeys);
        UUID foreignId = foreign.marketId();

        Event.KeyRegistered kr = new Event.KeyRegistered();
        kr.userId = OUTSIDER;
        kr.marketId = foreignId;
        kr.publicKey = outsiderKeys.publicKeyString();
        kr.timestamp = System.currentTimeMillis();
        foreign.append(kr, outsiderKeys.sign(EventCanonical.canonicalPayload(kr)));

        Event.Deposit dep = new Event.Deposit();
        dep.userId = OUTSIDER;
        dep.marketId = foreignId;
        dep.itemId = "minecraft:iron_ingot";
        dep.quantity = 10;
        dep.timestamp = System.currentTimeMillis();
        foreign.append(dep, outsiderKeys.sign(EventCanonical.canonicalPayload(dep)));

        Message.MigrateResult result = MarketClient.requestMigration(
                "127.0.0.1", port, OUTSIDER,
                Files.readAllLines(foreignLog), null);

        check("migration refused", result != null && !result.accepted ? 1 : 0, 1);
        check("because of admission, not the archive",
                result != null && result.reason != null
                        && result.reason.contains("invited") ? 1 : 0, 1);
    }

    /** The other pre-handshake write path. Same reasoning as A3. */
    private static void catchUpIsGatedToo(int port) throws Exception {
        System.out.println("  [A4: catch-up is refused for an unlisted identity]");

        Message.CatchUpResult result = MarketClient.offerCatchUp(
                "127.0.0.1", port, OUTSIDER,
                java.util.Collections.<String>emptyList());

        check("catch-up refused", result != null && !result.accepted ? 1 : 0, 1);

        // "nothing offered" would mean the offer was accepted as legitimate and then
        // found empty — i.e. the gate was never reached.
        check("refused at the gate, not for being empty",
                result != null && result.reason != null
                        && result.reason.contains("invited") ? 1 : 0, 1);
    }

    /**
     * Migrated goods are weighed the same way deposited ones are.
     *
     * This was the way in that no deposit rule reached. All three hang off
     * depositUnitsOf in processProposal, and a migration never goes near it — it is
     * queued as host work and appended directly, so items arrived in any quantity from
     * any world with nothing consulted but the admission list. The grant guards stopped
     * the credits; nothing stopped the goods.
     *
     * Both directions are checked, because refusing everything would pass the first half
     * on its own and would break migration for the people using it honestly.
     */
    private static void migrationIsWeighedAgainstStatistics() throws Exception {
        System.out.println("  [A7: a migration is weighed against the sender's statistics]");

        final String IRON = "minecraft:iron_ingot";

        for (boolean honest : new boolean[]{true, false}) {
            String tag = honest ? "honest" : "spawned";
            Path hostLog = dir.resolve("adm-mig-" + tag + ".jsonl");
            Path foreignLog = dir.resolve("adm-mig-" + tag + "-foreign.jsonl");
            Path peers = dir.resolve("adm-mig-" + tag + "-peers.json");
            for (Path p : new Path[]{hostLog, foreignLog, peers}) Files.deleteIfExists(p);

            PlayerKeys keys = PlayerKeys.generate();
            EventLog log = new EventLog(hostLog);
            MarketBootstrap.createMarket(log, HOST, "migration test market", keys);

            ServerConfig cfg = ServerConfig.friendGroup(TestPorts.free());
            cfg.hostName = "weigher";
            cfg.hostUserId = HOST.toString();
            cfg.maxDepositMultipleOfHandled = 3;

            HostServer host = new HostServer(cfg, hostLog, keys, new PeerCache(peers));
            Thread t = new Thread(() -> {
                try { host.start(); } catch (IOException e) { /* stopped */ }
            }, "migration-test-host-" + tag);
            t.setDaemon(true);
            t.start();
            IOException bindError = host.awaitBound(5000);
            if (bindError != null) throw bindError;

            try {
                // A foreign market the sender really holds 300 iron in. Whether that is
                // legitimate is the only thing in question.
                PlayerKeys mine = PlayerKeys.generate();
                PlayerKeys otherHost = PlayerKeys.generate();
                EventLog foreign = new EventLog(foreignLog);
                MarketBootstrap.createMarket(foreign, HOST, "elsewhere", otherHost);
                UUID foreignId = foreign.marketId();

                Event.KeyRegistered kr = new Event.KeyRegistered();
                kr.userId = INVITED;
                kr.marketId = foreignId;
                kr.publicKey = mine.publicKeyString();
                kr.timestamp = System.currentTimeMillis();
                foreign.append(kr, mine.sign(EventCanonical.canonicalPayload(kr)));

                Event.Deposit dep = new Event.Deposit();
                dep.userId = INVITED;
                dep.marketId = foreignId;
                dep.itemId = IRON;
                dep.quantity = 300;
                dep.timestamp = System.currentTimeMillis();
                foreign.append(dep, mine.sign(EventCanonical.canonicalPayload(dep)));

                // 300 needs 100 handled at a multiple of three. The honest world counted
                // them; the creative one never touched a pickaxe.
                io.github.badbull643.economiesmod.core.WorldAttestation claim =
                        new io.github.badbull643.economiesmod.core.WorldAttestation();
                claim.gameMode = "survival";
                claim.handledByItem = new java.util.HashMap<>();
                claim.handledByItem.put(IRON, honest ? 100L : 0L);

                Message.MigrateResult result = MarketClient.requestMigration(
                        "127.0.0.1", cfg.port, INVITED,
                        Files.readAllLines(foreignLog), claim);

                if (honest) {
                    check("statistics that cover it are let through",
                            result != null && result.accepted ? 1 : 0, 1);
                } else {
                    check("statistics that do not are refused",
                            result != null && !result.accepted ? 1 : 0, 1);
                    check("and the refusal says what they show",
                            result != null && result.reason != null
                                    && result.reason.contains("statistics") ? 1 : 0, 1);
                }
            } finally {
                host.stop();
            }
        }
    }

    /**
     * A server that cannot listen does not change the market.
     *
     * start() registers the host and issues its grant, because MarketBootstrap writes
     * genesis straight to the log and the usual KeyRegistered path never runs for
     * whoever is hosting. Those writes used to happen before the bind, so starting on a
     * busy port — the single most common startup failure there is — appended two events
     * and then failed. Idempotent on the next attempt, so it did not accumulate, but a
     * market fact had been created by a server that never served.
     */
    private static void aFailedStartWritesNothing() throws Exception {
        System.out.println("  [A6: a start that cannot bind leaves the log alone]");

        Path hostLog = dir.resolve("adm-busy.jsonl");
        Files.deleteIfExists(hostLog);
        Files.deleteIfExists(dir.resolve("adm-busy-peers.json"));

        PlayerKeys keys = PlayerKeys.generate();
        EventLog log = new EventLog(hostLog);
        MarketBootstrap.createMarket(log, HOST, "busy port market", keys);
        long before = log.lastSeq();

        // Hold the port so the bind cannot succeed, exactly as another host would.
        try (ServerSocket squatter = new ServerSocket(0)) {
            ServerConfig cfg = ServerConfig.friendGroup(squatter.getLocalPort());
            cfg.hostName = "unlucky";
            cfg.hostUserId = HOST.toString();

            HostServer host = new HostServer(cfg, hostLog, keys,
                    new PeerCache(dir.resolve("adm-busy-peers.json")));

            int refused = 0;
            try {
                host.start();
            } catch (IOException expected) {
                refused = 1;
            } finally {
                host.stop();
            }

            check("the start fails", refused, 1);
            check("and the market is untouched", new EventLog(hostLog).lastSeq(), before);
        }
    }

    /**
     * A dedicated server does not hand its clients each other's addresses.
     *
     * The roster exists for a market where hosting rotates and the next host is one of
     * the people already here. A dedicated server never hands over, its clients sit
     * behind NAT with nothing forwarded, and a residential address is wrong within
     * days — so the broadcast buys nobody a connection they could make while giving
     * every joiner the addresses of everyone before them, written to their disk.
     *
     * Both halves are checked, because "shares nothing" passes trivially if the roster
     * was empty to begin with. The seeded peer is on a routable address so the existing
     * loopback filter cannot be what removes it.
     */
    private static void peerSharingRespectsDedicated() throws Exception {
        System.out.println("  [A5: only a rotating host passes on who else is here]");

        UUID stranger = UUID.fromString("00000000-0000-0000-0000-00000000000c");

        for (boolean dedicated : new boolean[]{false, true}) {
            String tag = dedicated ? "ded" : "rotating";
            Path hostLog = dir.resolve("adm-share-" + tag + ".jsonl");
            Path hostPeers = dir.resolve("adm-share-" + tag + "-host-peers.json");
            Path clientPeers = dir.resolve("adm-share-" + tag + "-client-peers.json");
            Path clientLog = dir.resolve("adm-share-" + tag + "-client.jsonl");
            for (Path p : new Path[]{hostLog, hostPeers, clientPeers, clientLog}) {
                Files.deleteIfExists(p);
            }

            PlayerKeys keys = PlayerKeys.generate();
            EventLog log = new EventLog(hostLog);
            MarketBootstrap.createMarket(log, HOST, "roster test market", keys);

            PeerCache hostCache = new PeerCache(hostPeers);
            hostCache.record(stranger.toString(), "Stranger", "203.0.113.7", 25565, null);

            ServerConfig cfg = ServerConfig.friendGroup(TestPorts.free());
            cfg.hostName = "roster";
            cfg.hostUserId = HOST.toString();
            cfg.dedicated = dedicated;

            HostServer host = new HostServer(cfg, hostLog, keys, hostCache);
            Thread t = new Thread(() -> {
                try { host.start(); } catch (IOException e) { /* stopped */ }
            }, "roster-test-host-" + tag);
            t.setDaemon(true);
            t.start();

            IOException bindError = host.awaitBound(5000);
            if (bindError != null) throw bindError;

            try {
                PeerCache clientCache = new PeerCache(clientPeers);
                MarketClient joiner = new MarketClient(INVITED, "joiner",
                        PlayerKeys.generate(), new EventLog(clientLog), true,
                        clientCache, 0);
                joiner.connect("127.0.0.1", cfg.port);

                boolean learned = false;
                for (PeerCache.Peer p : clientCache.all()) {
                    if (stranger.toString().equals(p.userId)) learned = true;
                }

                check(dedicated
                                ? "a dedicated host keeps the roster to itself"
                                : "a rotating host still shares it",
                        learned ? 1 : 0, dedicated ? 0 : 1);

                joiner.disconnect();
            } finally {
                host.stop();
            }
        }
    }

    /**
     * Step 4 of the compaction note: who writes down a dedicated market's history.
     *
     * The rule is three-way and every leg of it matters, so all three are run against a
     * real host rather than asserted about a boolean. A client of a dedicated market
     * keeps a snapshot and no history; one that has opted in keeps everything; and a
     * client of a rotating host is untouched, because there the replica is how hosting
     * rotates at all — getting that leg wrong would quietly stop friend groups being
     * able to take turns.
     */
    private static void onlyArchivistsKeepADedicatedMarketsHistory() throws Exception {
        // (dedicated?, archives?, expected to write history?)
        boolean[][] cases = {
                { true,  false, false },
                { true,  true,  true  },
                { false, false, true  },
        };
        for (boolean[] c : cases) {
            boolean dedicated = c[0], archives = c[1], expectHistory = c[2];
            String tag = (dedicated ? "ded" : "rot") + (archives ? "-arch" : "-plain");

            Path hostLog = dir.resolve("persist-host-" + tag + ".jsonl");
            Path clientLog = dir.resolve("persist-client-" + tag + ".jsonl");
            Files.deleteIfExists(hostLog);
            Files.deleteIfExists(clientLog);
            Files.deleteIfExists(clientLog.resolveSibling(
                    clientLog.getFileName() + ".snapshot.json"));

            PlayerKeys keys = PlayerKeys.generate();
            EventLog log = new EventLog(hostLog);
            MarketBootstrap.createMarket(log, HOST, "persist test market", keys);

            ServerConfig cfg = ServerConfig.friendGroup(TestPorts.free());
            cfg.hostName = "persist";
            cfg.hostUserId = HOST.toString();
            cfg.dedicated = dedicated;

            HostServer host = new HostServer(cfg, hostLog, keys,
                    new PeerCache(dir.resolve("persist-host-peers-" + tag + ".json")));
            Thread t = new Thread(() -> {
                try { host.start(); } catch (IOException e) { /* stopped */ }
            }, "persist-test-host-" + tag);
            t.setDaemon(true);
            t.start();
            IOException bindError = host.awaitBound(5000);
            if (bindError != null) throw bindError;

            try {
                MarketClient joiner = new MarketClient(INVITED, "joiner",
                        PlayerKeys.generate(), new EventLog(clientLog), true,
                        new PeerCache(dir.resolve("persist-client-peers-" + tag + ".json")),
                        0, id -> archives);
                joiner.connect("127.0.0.1", cfg.port);

                // The client is registered and granted on joining, so there is real
                // history to either keep or not — a market with nothing in it would let
                // both answers look the same.
                check(tag + ": the client actually synced something",
                        joiner.lastSeq() > 0 ? 1 : 0, 1);

                long written = new EventLog(clientLog).lastSeq();
                check(tag + ": " + (expectHistory ? "history is kept" : "no history is kept"),
                        written > 0 ? 1 : 0, expectHistory ? 1 : 0);
                if (!expectHistory) {
                    check(tag + ": but the state is still there",
                            joiner.state().marketId() != null ? 1 : 0, 1);
                }

                joiner.disconnect();

                // A replica that keeps no history has to keep a snapshot, or it would
                // re-download the whole market next session — which is worse than the
                // thing this replaced.
                boolean snap = Files.exists(clientLog.resolveSibling(
                        clientLog.getFileName() + ".snapshot.json"));
                check(tag + ": " + (expectHistory
                                ? "no snapshot is needed while the log is kept"
                                : "a snapshot is left in the log's place"),
                        snap ? 1 : 0, expectHistory ? 0 : 1);
            } finally {
                host.stop();
            }
        }
    }

    /**
     * Turning archiving on for a market this machine only has a snapshot of.
     *
     * The command says the history arrives on the next connect. That is the promise the
     * whole opt-in rests on — somebody turns it on precisely so they can host — so it is
     * worth a check of its own rather than being assumed from the rule above.
     */
    private static void archivingOnFetchesTheHistoryItPromised() throws Exception {
        Path hostLog = dir.resolve("refetch-host.jsonl");
        Path clientLog = dir.resolve("refetch-client.jsonl");
        Path clientSnap = clientLog.resolveSibling(clientLog.getFileName() + ".snapshot.json");
        Files.deleteIfExists(hostLog);
        Files.deleteIfExists(clientLog);
        Files.deleteIfExists(clientSnap);

        PlayerKeys keys = PlayerKeys.generate();
        EventLog log = new EventLog(hostLog);
        MarketBootstrap.createMarket(log, HOST, "refetch market", keys);

        ServerConfig cfg = ServerConfig.friendGroup(TestPorts.free());
        cfg.hostName = "refetch";
        cfg.hostUserId = HOST.toString();
        cfg.dedicated = true;

        HostServer host = new HostServer(cfg, hostLog, keys,
                new PeerCache(dir.resolve("refetch-host-peers.json")));
        Thread t = new Thread(() -> {
            try { host.start(); } catch (IOException e) { /* stopped */ }
        }, "refetch-test-host");
        t.setDaemon(true);
        t.start();
        IOException bindError = host.awaitBound(5000);
        if (bindError != null) throw bindError;

        try {
            PeerCache cache = new PeerCache(dir.resolve("refetch-client-peers.json"));

            // One identity across both visits: the market registers a key the first time
            // and a second keypair would be refused as an impostor, which is a different
            // test entirely.
            PlayerKeys mine = PlayerKeys.generate();

            // First visit: not archiving. Snapshot, no history.
            MarketClient first = new MarketClient(INVITED, "joiner", mine,
                    new EventLog(clientLog), true, cache, 0, id -> false);
            first.connect("127.0.0.1", cfg.port);
            long reached = first.lastSeq();
            first.disconnect();

            check("the first visit kept no history", new EventLog(clientLog).lastSeq(), 0);
            check("but did leave a snapshot", Files.exists(clientSnap) ? 1 : 0, 1);
            check("and got somewhere", reached > 0 ? 1 : 0, 1);

            // Second visit: still not archiving. The ordinary session-after-session case
            // for a snapshot-only replica, and the one that has to work every day.
            // Its Hello carries a position restored from the snapshot, so the market it
            // names has to come from the snapshot too — reading it off the log instead
            // says "I am at event 3 of no market", which a host refuses as a log that
            // predates market identity. That locked every snapshot-only client out from
            // its second session onwards, and nothing noticed because every other test
            // here connects exactly once.
            MarketClient again = new MarketClient(INVITED, "joiner", mine,
                    new EventLog(clientLog), true, cache, 0, id -> false);
            again.connect("127.0.0.1", cfg.port);
            check("a snapshot-only replica can come back tomorrow",
                    again.lastSeq() >= reached ? 1 : 0, 1);
            check("and still holds the market it had",
                    again.state().marketId() != null ? 1 : 0, 1);
            again.disconnect();
            check("and still keeps no history", new EventLog(clientLog).lastSeq(), 0);

            // Third visit: archiving on. The command promises the history turns up.
            MarketClient second = new MarketClient(INVITED, "joiner", mine,
                    new EventLog(clientLog), true, cache, 0, id -> true);
            second.connect("127.0.0.1", cfg.port);
            long after = new EventLog(clientLog).lastSeq();
            second.disconnect();

            check("turning archiving on fetches the history", after >= reached ? 1 : 0, 1);
            check("and the fetched log is a whole chain",
                    new EventLog(clientLog).verifyChain(), -1);

            // A host's self-connect is also told not to persist, and means something
            // entirely different by it: the log is right there, being written by the
            // HostServer on the other end of the socket. Writing a snapshot for it
            // marked a full history as one that stands without its log — which is the
            // one kind that survives the log being deleted, so deleting that log would
            // have handed the market back. Found in a real session, in a line that
            // failed twice over: two shutdown paths racing on one temporary file.
            Path selfLog = dir.resolve("refetch-selfconnect.jsonl");
            Path selfSnap = selfLog.resolveSibling(selfLog.getFileName() + ".snapshot.json");
            Files.deleteIfExists(selfLog);
            Files.deleteIfExists(selfSnap);
            MarketClient selfish = new MarketClient(INVITED, "selfconnect", mine,
                    new EventLog(selfLog), false, cache, 0, id -> true);
            selfish.connect("127.0.0.1", cfg.port);
            selfish.disconnect();
            check("a caller that never wanted a log gets no snapshot either",
                    Files.exists(selfSnap) ? 1 : 0, 0);

            // An empty slot is refused when this machine already holds the host's market
            // somewhere else. Over the wire rather than against the predicate, because
            // the half that matters is where it happens: before a single synced line is
            // read, so a refusal leaves the empty slot exactly as empty as it was.
            Path dupLog = dir.resolve("refetch-duplicate.jsonl");
            Files.deleteIfExists(dupLog);
            Files.deleteIfExists(dupLog.resolveSibling(dupLog.getFileName() + ".snapshot.json"));
            MarketClient dup = new MarketClient(INVITED, "joiner", mine,
                    new EventLog(dupLog), true, cache, 0, id -> true);
            dup.setHeldElsewhere(id -> true);
            int refused = 0;
            String why = "";
            try {
                dup.connect("127.0.0.1", cfg.port);
            } catch (IOException e) {
                refused = 1;
                why = e.getMessage() == null ? "" : e.getMessage();
            }
            check("a second slot for a market we already hold is refused", refused, 1);
            check("and says which way out there is",
                    why.contains("another market slot") ? 1 : 0, 1);
            check("and the empty slot is left empty",
                    new EventLog(dupLog).lastSeq(), 0);

            // The ordinary case must not be caught by it. Deliberately with the guard
            // answering yes — this slot already holds the market, and holding it is what
            // exempts it, not the predicate saying no. Testing this with the default
            // predicate proved nothing: dropping the "we hold nothing" condition left
            // the check green, because a client that never sets the guard can never
            // trip it.
            MarketClient normal = new MarketClient(INVITED, "joiner", mine,
                    new EventLog(clientLog), true, cache, 0, id -> true);
            normal.setHeldElsewhere(id -> true);
            normal.connect("127.0.0.1", cfg.port);
            check("but a slot reconnecting to its own market still gets in",
                    normal.lastSeq() > 0 ? 1 : 0, 1);
            normal.disconnect();
        } finally {
            host.stop();
        }
    }

    /**
     * Backlog item 8's payoff, checked where it actually lands.
     *
     * The unit tests cover publishing and cover ServerConfig.adopt. What neither can see
     * is whether a host that starts on this market ever asks — the failure this whole
     * feature exists to prevent is a friend rotating in and hosting with no caps, and a
     * rule adopted in a test but never consulted by HostServer would look identical to a
     * working one from every angle except the only one that matters.
     */
    private static void aHostTakesUpTheGroupsPublishedRules() throws Exception {
        System.out.println("  [A10: a host starts from the rules the group published]");

        Path hostLog = dir.resolve("adopt-host.jsonl");
        Files.deleteIfExists(hostLog);
        Files.deleteIfExists(hostLog.resolveSibling(hostLog.getFileName() + ".snapshot.json"));

        PlayerKeys keys = PlayerKeys.generate();
        EventLog log = new EventLog(hostLog);
        MarketBootstrap.createMarket(log, HOST, "adopt test market", keys);

        // The creator publishes what the group agreed.
        Event.HostDefaults rules = new Event.HostDefaults();
        rules.userId = HOST;
        rules.marketId = log.marketId();
        rules.clientEventId = UUID.randomUUID().toString();
        rules.timestamp = System.currentTimeMillis();
        rules.maxDepositUnitsPerWindow = 640L;
        rules.maxMigratedCredits = 5000L;
        log.append(rules, keys.sign(EventCanonical.canonicalPayload(rules)));

        // A host that has set one of the two for itself and left the other alone.
        ServerConfig cfg = ServerConfig.friendGroup(TestPorts.free());
        cfg.hostName = "adopter";
        cfg.hostUserId = HOST.toString();
        cfg.maxMigratedCredits = 99;          // this host has an opinion about this one

        HostServer host = new HostServer(cfg, hostLog, keys,
                new PeerCache(dir.resolve("adopt-host-peers.json")));

        check("the host took up the cap it had not set", cfg.maxDepositUnitsPerWindow, 640);
        check("and kept the one it had", cfg.maxMigratedCredits, 99);

        // And a market that publishes nothing leaves a host exactly as it was, which is
        // the behaviour every existing market has and must keep.
        Path plainLog = dir.resolve("adopt-plain.jsonl");
        Files.deleteIfExists(plainLog);
        Files.deleteIfExists(plainLog.resolveSibling(plainLog.getFileName() + ".snapshot.json"));
        PlayerKeys plainKeys = PlayerKeys.generate();
        EventLog plain = new EventLog(plainLog);
        MarketBootstrap.createMarket(plain, HOST, "plain market", plainKeys);

        ServerConfig untouched = ServerConfig.friendGroup(TestPorts.free());
        untouched.hostUserId = HOST.toString();
        new HostServer(untouched, plainLog, plainKeys,
                new PeerCache(dir.resolve("adopt-plain-peers.json")));
        check("a market that publishes nothing changes nothing",
                untouched.maxDepositUnitsPerWindow, 0);
    }

    private static MarketClient client(UUID who, String logName) throws Exception {
        Path p = dir.resolve(logName);
        Files.deleteIfExists(p);
        return new MarketClient(who, who.toString().substring(0, 8), PlayerKeys.generate(),
                new EventLog(p), true,
                new PeerCache(dir.resolve("adm-client-peers.json")), 0);
    }
}
