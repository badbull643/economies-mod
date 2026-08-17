package io.github.badbull643.economiesmod.core.net;

import io.github.badbull643.economiesmod.core.Event;
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
import java.util.UUID;

/**
 * The attestation has to reach the host and be acted on.
 *
 * MarketTests covers what counts as an objection. This covers the wiring, which is
 * where a rule that decides correctly and is never consulted would still pass
 * everything — the same reason depositCapTest exists alongside the limiter's own
 * checks.
 *
 * What it deliberately does not test is whether any of this catches a determined
 * cheat, because it does not. Every field is client-supplied. These checks establish
 * that an honest client's description is received and that a self-contradicting one is
 * refused; both are claims about plumbing, not about security.
 *
 * Not part of MarketTests: that suite is pure-core and instant, and this binds sockets
 * and signs RSA events.
 */
public class AttestationTest {

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

    private static Path dir;
    private static PlayerKeys hostKeys;

    /**
     * One key for the joiner across the whole run.
     *
     * A fresh keypair per connection meant the second time this identity appeared the
     * market already held a different key for it, and the handshake refused it as a
     * moved-computer case long before any attestation was read.
     */
    private static PlayerKeys joinerKeys;

    public static void main(String[] args) throws Exception {
        dir = Paths.get("build", "test-scratch");
        Files.createDirectories(dir);
        for (String f : new String[]{"att-host.jsonl", "att-client.jsonl",
                "att-host-peers.json", "att-client-peers.json"}) {
            Files.deleteIfExists(dir.resolve(f));
        }

        hostKeys = PlayerKeys.generate();
        joinerKeys = PlayerKeys.generate();
        Path hostLog = dir.resolve("att-host.jsonl");
        EventLog log = new EventLog(hostLog);
        MarketBootstrap.createMarket(log, HOST, "attestation test market", hostKeys);

        creativeWorldRefusedAtHandshake(hostLog);
        contradictionRefusedAtDeposit(hostLog);
        cheatsEnabledAfterConnectingAreCaught(hostLog);

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL " + checksRun + " CHECKS PASSED");
        } else {
            System.out.println(failures + " of " + checksRun + " checks FAILED");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * A1 — a world that says it is in creative mode is turned away before syncing.
     *
     * The honest-client case. It works because the client told the truth, which is the
     * whole of what this layer catches.
     */
    private static void creativeWorldRefusedAtHandshake(Path hostLog) throws Exception {
        System.out.println("  [A1: a creative world is refused, when the host asks]");

        ServerConfig cfg = ServerConfig.friendGroup(freePort());
        cfg.hostUserId = HOST.toString();
        cfg.refuseCreativeWorlds = true;

        HostServer host = serve(cfg, hostLog);
        try {
            WorldAttestation creative = new WorldAttestation();
            creative.gameMode = "creative";
            creative.worldAgeTicks = WorldAttestation.TICKS_PER_HOUR * 10;

            String reason = null;
            try {
                connected(cfg.port, creative);
            } catch (IOException e) {
                reason = e.getMessage();
            }
            check("refused", reason != null ? 1 : 0, 1);
            check("and says why",
                    reason != null && reason.contains("creative") ? 1 : 0, 1);

            // The same host must still admit an ordinary world, or this would prove
            // only that it refuses everyone.
            WorldAttestation survival = new WorldAttestation();
            survival.gameMode = "survival";
            survival.worldAgeTicks = WorldAttestation.TICKS_PER_HOUR * 10;
            MarketClient ok = connected(cfg.port, survival);
            check("a survival world still gets in", ok.isConnected() ? 1 : 0, 1);
            ok.disconnect();
        } finally {
            host.stop();
        }
    }

    /**
     * A2 — the check that costs a liar something.
     *
     * Nothing here verifies the claimed play time. What it establishes is that the
     * claim and the deposits are compared, so that a client cannot both claim a short
     * history and hand over more than one could produce.
     */
    private static void contradictionRefusedAtDeposit(Path hostLog) throws Exception {
        System.out.println("  [A2: claimed play time is checked against what arrives]");

        ServerConfig cfg = ServerConfig.friendGroup(freePort());
        cfg.hostUserId = HOST.toString();
        cfg.maxDepositUnitsPerPlayHour = 100;

        HostServer host = serve(cfg, hostLog);
        final String[] refusal = new String[1];

        try {
            WorldAttestation young = new WorldAttestation();
            young.gameMode = "survival";
            young.worldAgeTicks = WorldAttestation.TICKS_PER_HOUR;   // one hour, so 100

            MarketClient client = client();
            client.setOnRejected(r -> refusal[0] = r);
            client.setAttestation(young);
            client.connect("127.0.0.1", cfg.port);

            long before = client.lastSeq();
            deposit(client, 80);
            check("a haul that hour could yield is accepted",
                    awaitSeqAbove(client, before) > before ? 1 : 0, 1);

            // 80 + 40 is more than one claimed hour affords.
            deposit(client, 40);
            awaitRefusal(refusal);
            check("one that it could not is refused", refusal[0] != null ? 1 : 0, 1);
            check("and names the contradiction",
                    refusal[0] != null && refusal[0].contains("hours of play") ? 1 : 0, 1);

            client.disconnect();
        } finally {
            host.stop();
        }
    }

    /**
     * A3 — the handshake is a photograph, and the world can change after it.
     *
     * Connect from a world with no cheats, then open it to LAN with cheats enabled. The
     * description the host is holding stopped being true, and checking only at the
     * handshake would leave that as a more comfortable way in than the one the
     * attestation was built to close.
     */
    private static void cheatsEnabledAfterConnectingAreCaught(Path hostLog)
            throws Exception {
        System.out.println("  [A3: cheats switched on after connecting are caught]");

        ServerConfig cfg = ServerConfig.friendGroup(freePort());
        cfg.hostUserId = HOST.toString();
        cfg.refuseCheatWorlds = true;

        HostServer host = serve(cfg, hostLog);
        try {
            WorldAttestation clean = new WorldAttestation();
            clean.gameMode = "survival";
            clean.worldAgeTicks = WorldAttestation.TICKS_PER_HOUR * 5;

            MarketClient client = client();
            client.setAttestation(clean);
            client.connect("127.0.0.1", cfg.port);
            check("a clean world gets in", client.isConnected() ? 1 : 0, 1);

            // Exactly what Open to LAN does: the saved settings still say no cheats,
            // the running server says otherwise.
            WorldAttestation opened = new WorldAttestation();
            opened.gameMode = "survival";
            opened.worldAgeTicks = WorldAttestation.TICKS_PER_HOUR * 5;
            opened.commandsAllowed = false;
            opened.cheatsLive = true;
            client.reattest(opened);

            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && client.isConnected()) {
                Thread.sleep(25);
            }
            check("and is dropped once cheats appear",
                    client.isConnected() ? 1 : 0, 0);
        } finally {
            host.stop();
        }
    }

    // ─────────── helpers ───────────

    private static HostServer serve(ServerConfig cfg, Path hostLog) throws Exception {
        HostServer host = new HostServer(cfg, hostLog, hostKeys,
                new PeerCache(dir.resolve("att-host-peers.json")));
        Thread t = new Thread(() -> {
            try { host.start(); } catch (IOException e) { /* stopped */ }
        }, "attestation-test-host");
        t.setDaemon(true);
        t.start();

        IOException bindError = host.awaitBound(5000);
        if (bindError != null) throw bindError;
        return host;
    }

    private static MarketClient client() throws Exception {
        Path p = dir.resolve("att-client.jsonl");
        Files.deleteIfExists(p);
        return new MarketClient(JOINER, "Joiner", joinerKeys,
                new EventLog(p), true,
                new PeerCache(dir.resolve("att-client-peers.json")), 0);
    }

    private static MarketClient connected(int port, WorldAttestation a) throws Exception {
        MarketClient c = client();
        c.setAttestation(a);
        c.connect("127.0.0.1", port);
        return c;
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
