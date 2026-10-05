package io.github.badbull643.economiesmod.core.net;

import io.github.badbull643.economiesmod.core.EventLog;
import io.github.badbull643.economiesmod.core.GameVersions;
import io.github.badbull643.economiesmod.core.MarketBootstrap;
import io.github.badbull643.economiesmod.core.PeerCache;
import io.github.badbull643.economiesmod.core.PlayerKeys;
import io.github.badbull643.economiesmod.core.ServerConfig;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

/**
 * A market belongs to one Minecraft version, and every door has to say so.
 *
 * The mod is built once per Minecraft version from a shared engine, so nothing in the
 * protocol would otherwise tell a 1.16.5 player from a 1.21.11 one — they would join each
 * other's markets and trade items that do not mean the same thing on both sides. This
 * covers the three doors that could let that happen (the handshake, the probe's listing
 * and migration), the two kinds of host (a rotating one and a dedicated one are the same
 * HostServer, which is what is being relied on), and the client's own check, which exists
 * for hosts too old to have refused anybody.
 *
 * Over real sockets, for the reason the other network suites are: a check that exists and
 * is never consulted would pass every unit test.
 */
public class GameVersionTest {

    private static int failures = 0;
    private static int checksRun = 0;

    private static void check(String label, long actual, long expected) {
        checksRun++;
        boolean ok = actual == expected;
        if (!ok) failures++;
        System.out.println((ok ? "    ok   " : "    FAIL ") + label
                + " — expected " + expected + ", got " + actual);
    }

    private static void check(String label, boolean condition) {
        check(label, condition ? 1 : 0, 1);
    }

    private static final UUID HOST   = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID JOINER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static Path dir;
    private static int fileCounter = 0;

    public static void main(String[] args) throws Exception {
        dir = Paths.get("build", "test-scratch");
        Files.createDirectories(dir);

        System.out.println("  [V1: what a version is, and what is said when it is 1.16.5]");
        check("nothing means 1.16.5", GameVersions.normalize(null).equals("1.16.5"));
        check("so does blank", GameVersions.normalize("  ").equals("1.16.5"));
        check("1.16.5 is written as nothing", GameVersions.toWire("1.16.5") == null);
        check("and so is its absence", GameVersions.toWire(null) == null);
        check("anything else is written", "1.21.11".equals(GameVersions.toWire(" 1.21.11 ")));
        check("blank is not 'set'", !GameVersions.isSet(" "));

        System.out.println("  [V2: a host on 1.21.11 turns away a 1.16.5 client]");
        {
            Running host = startHost("1.21.11");
            try {
                MarketClient.Refused refused = connectExpectingRefusal(host, "1.16.5");
                check("the legacy client is refused", refused != null);
                check("with a code a client can act on",
                        refused != null && HostServer.Refusal.GAME_VERSION.equals(refused.code));
                check("that names both versions",
                        refused != null && refused.getMessage().contains("1.21.11")
                                && refused.getMessage().contains("1.16.5"));

                // A client that says nothing at all is a 1.16.5 client — an older build.
                MarketClient silent = newClient();
                try {
                    silent.connect("127.0.0.1", host.port);
                    check("a client that says nothing is refused too", false);
                } catch (MarketClient.Refused e) {
                    check("a client that says nothing is refused too",
                            HostServer.Refusal.GAME_VERSION.equals(e.code));
                }
            } finally {
                host.stop();
            }
        }

        System.out.println("  [V3: and lets its own version in]");
        {
            Running host = startHost("1.21.11");
            try {
                MarketClient client = newClient();
                client.setGameVersion("1.21.11");
                client.connect("127.0.0.1", host.port);
                check("a client on the same version connects", client.isConnected());
                client.disconnect();
            } finally {
                host.stop();
            }
        }

        System.out.println("  [V4: a 1.16.5 host — the default — turns away anybody else]");
        {
            Running host = startHost(null);
            try {
                MarketClient.Refused refused = connectExpectingRefusal(host, "1.21.11");
                check("a 1.21.11 client is refused", refused != null
                        && HostServer.Refusal.GAME_VERSION.equals(refused.code));

                MarketClient legacy = newClient();
                legacy.connect("127.0.0.1", host.port);
                check("a 1.16.5 client still connects, as it always did", legacy.isConnected());
                legacy.disconnect();
            } finally {
                host.stop();
            }
        }

        System.out.println("  [V5: the probe says which version a host serves, and is signed]");
        {
            Running modern = startHost("1.21.11");
            Running legacy = startHost(null);
            try {
                Probe.Result m = Probe.probe("127.0.0.1", modern.port, 4000);
                check("a 1.21.11 host's reply verifies", m.reachable);
                check("and carries its version", m.reachable && "1.21.11".equals(m.reply.gameVersion));

                Probe.Result l = Probe.probe("127.0.0.1", legacy.port, 4000);
                check("a 1.16.5 host's reply verifies", l.reachable);
                check("and carries no version, as it never did",
                        l.reachable && l.reply.gameVersion == null);
                check("so its signed payload is the one older clients compute",
                        l.reachable && Probe.queryPayload(l.reply, "n")
                                .endsWith("|" + l.reply.dedicated));

                // What the host list does with that: only hosts it could actually join.
                PeerPoll.HostInfo modernRow = new PeerPoll.HostInfo(null, m.reply);
                PeerPoll.HostInfo legacyRow = new PeerPoll.HostInfo(null, l.reply);
                check("a 1.21.11 player is shown the 1.21.11 host",
                        modernRow.servesVersion("1.21.11"));
                check("and not the 1.16.5 one", !legacyRow.servesVersion("1.21.11"));
                check("a 1.16.5 player is shown the 1.16.5 host",
                        legacyRow.servesVersion("1.16.5"));
                check("and not the 1.21.11 one", !modernRow.servesVersion("1.16.5"));

                // The version is inside what is signed: take it out and the signature fails.
                Message.QueryReply tampered = new Message.QueryReply();
                tampered.userId = "u"; tampered.hostName = "h"; tampered.marketId = "m";
                tampered.gameVersion = "1.21.11";
                String with = Probe.queryPayload(tampered, "n");
                tampered.gameVersion = null;
                check("and changing it changes what was signed",
                        !with.equals(Probe.queryPayload(tampered, "n")));
            } finally {
                modern.stop();
                legacy.stop();
            }
        }

        System.out.println("  [V6: migration is turned away at the same door]");
        {
            Running host = startHost("1.21.11");
            try {
                Message.MigrateResult result = MarketClient.requestMigration(
                        "127.0.0.1", host.port, JOINER, Arrays.asList("{}"), null, "1.16.5");
                check("a 1.16.5 migrant is refused", result != null && !result.accepted);
                check("for the version, not for some other reason",
                        result != null && result.reason != null
                                && result.reason.contains("Minecraft 1.21.11"));
            } finally {
                host.stop();
            }
        }

        System.out.println("  [V7: a client checks the host too, for hosts too old to have checked]");
        {
            // A host from before this setting: it accepts anyone and its Sync says nothing
            // about a version, which reads as 1.16.5. Standing in for it with a bare socket,
            // since the real HostServer now refuses first.
            final ServerSocket old = new ServerSocket(TestPorts.free());
            Thread t = new Thread(() -> {
                try (Socket s = old.accept(); MessageChannel ch = new MessageChannel(s)) {
                    ch.receive();                                   // the Hello
                    Message.Sync sync = new Message.Sync();
                    sync.logLines = new ArrayList<>();
                    sync.hostName = "an older host";
                    ch.send(sync);
                    Thread.sleep(500);
                } catch (Exception ignored) { }
            }, "old-host");
            t.setDaemon(true);
            t.start();
            try {
                MarketClient client = newClient();
                client.setGameVersion("1.21.11");
                try {
                    client.connect("127.0.0.1", old.getLocalPort());
                    check("a 1.21.11 client refuses an older host", false);
                } catch (MarketClient.Refused e) {
                    check("a 1.21.11 client refuses an older host",
                            HostServer.Refusal.GAME_VERSION.equals(e.code));
                    check("and says which versions", e.getMessage().contains("1.16.5")
                            && e.getMessage().contains("1.21.11"));
                }
                check("and is not left connected", !client.isConnected());
            } finally {
                old.close();
            }
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL " + checksRun + " CHECKS PASSED");
        } else {
            System.out.println(failures + " of " + checksRun + " checks FAILED");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    /** A started host and the address it is on. */
    private static final class Running {
        final HostServer server;
        final int port;
        Running(HostServer server, int port) { this.server = server; this.port = port; }
        void stop() { server.stop(); }
    }

    /** A host serving a fresh market on the given Minecraft version (null = 1.16.5). */
    private static Running startHost(String gameVersion) throws Exception {
        int n = ++fileCounter;
        PlayerKeys hostKeys = PlayerKeys.generate();
        Path log = dir.resolve("gv-host-" + n + ".jsonl");
        Path peers = dir.resolve("gv-host-peers-" + n + ".json");
        Files.deleteIfExists(log);
        Files.deleteIfExists(peers);
        EventLog hostLog = new EventLog(log);
        MarketBootstrap.createMarket(hostLog, HOST, "version test market", hostKeys);

        ServerConfig cfg = ServerConfig.friendGroup(TestPorts.free());
        cfg.hostName = "versioned";
        cfg.hostUserId = HOST.toString();
        cfg.gameVersion = gameVersion;
        cfg.acceptsMigration = Boolean.TRUE;

        HostServer host = new HostServer(cfg, log, hostKeys, new PeerCache(peers));
        Thread t = new Thread(() -> {
            try { host.start(); } catch (IOException e) { /* stopped */ }
        }, "gv-test-host");
        t.setDaemon(true);
        t.start();

        IOException bindError = host.awaitBound(5000);
        if (bindError != null) throw bindError;
        return new Running(host, cfg.port);
    }

    /** A client with its own empty log, saying nothing about its version until told to. */
    private static MarketClient newClient() throws Exception {
        int n = ++fileCounter;
        Path log = dir.resolve("gv-client-" + n + ".jsonl");
        Path peers = dir.resolve("gv-client-peers-" + n + ".json");
        Files.deleteIfExists(log);
        Files.deleteIfExists(peers);
        return new MarketClient(JOINER, "Joiner", PlayerKeys.generate(),
                new EventLog(log), true, new PeerCache(peers), 0);
    }

    private static MarketClient.Refused connectExpectingRefusal(Running host, String version)
            throws Exception {
        MarketClient client = newClient();
        client.setGameVersion(version);
        try {
            client.connect("127.0.0.1", host.port);
        } catch (MarketClient.Refused e) {
            return e;
        }
        client.disconnect();
        return null;
    }
}
