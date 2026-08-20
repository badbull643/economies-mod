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

        ServerConfig cfg = ServerConfig.friendGroup(freePort());
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

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL " + checksRun + " CHECKS PASSED");
        } else {
            System.out.println(failures + " of " + checksRun + " checks FAILED");
        }
        System.exit(failures == 0 ? 0 : 1);
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
                Files.readAllLines(foreignLog));

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

            ServerConfig cfg = ServerConfig.friendGroup(freePort());
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

    private static MarketClient client(UUID who, String logName) throws Exception {
        Path p = dir.resolve(logName);
        Files.deleteIfExists(p);
        return new MarketClient(who, who.toString().substring(0, 8), PlayerKeys.generate(),
                new EventLog(p), true,
                new PeerCache(dir.resolve("adm-client-peers.json")), 0);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
