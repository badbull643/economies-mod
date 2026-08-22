package io.github.badbull643.economiesmod.core.net;

import io.github.badbull643.economiesmod.core.Event;
import io.github.badbull643.economiesmod.core.EventCanonical;
import io.github.badbull643.economiesmod.core.EventLog;
import io.github.badbull643.economiesmod.core.MarketBootstrap;
import io.github.badbull643.economiesmod.core.PeerCache;
import io.github.badbull643.economiesmod.core.PlayerKeys;
import io.github.badbull643.economiesmod.core.ServerConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

/**
 * A host is trusted to order events, and for nothing else.
 *
 * The signature check already here proves who wrote an event. It says nothing about
 * whether the event was allowed, and everything that decides that — a grant must be the
 * market's published figure and only once per identity, a stipend must be the market's
 * and its interval must have elapsed, policy belongs to the creator alone — lives in
 * EventApplier.validate, because validate is what a host asks before it appends.
 *
 * The client asked neither. It verified the signature and called apply, and apply
 * enforces none of it. So a modified host could sequence itself a grant for any sum,
 * sign it correctly with its own key, and every connected replica would apply it,
 * persist it, and re-serve it the next time that player hosted. This is the same hole
 * MarketArchive had on the migration path, on the path everybody uses instead.
 *
 * H1 is the attack. H2 is the thing that makes H1's fix safe to ship: an honest history
 * of exactly the same shape must still sync, because the cost of over-refusing is a
 * client that disconnects mid-session for no reason.
 *
 * Not part of MarketTests: that suite is pure-core and instant, and this binds sockets
 * and signs RSA events.
 */
public class HostTrustTest {

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

    /** What a market this size would never legitimately hand anyone. */
    private static final long MINTED = 999_999_999L;

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get("build", "test-scratch");
        Files.createDirectories(dir);
        for (String f : new String[]{"trust-host.jsonl", "trust-joiner.jsonl",
                "trust-honest-host.jsonl", "trust-honest-joiner.jsonl",
                "trust-host-peers.json", "trust-joiner-peers.json", "known-keys.json"}) {
            Files.deleteIfExists(dir.resolve(f));
        }

        PlayerKeys hostKeys = PlayerKeys.generate();
        PlayerKeys joinerKeys = PlayerKeys.generate();

        mintingHostIsRefused(dir, hostKeys, joinerKeys);
        honestHostStillSyncs(dir, hostKeys, joinerKeys);

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL " + checksRun + " CHECKS PASSED");
        } else {
            System.out.println(failures + " of " + checksRun + " checks FAILED");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * H1: a host that has written itself money is refused, and nothing of it is kept.
     *
     * The grant below is exactly what a modified host produces: correctly signed with
     * the host's own key, correctly chained, and for a sum this market's own policy does
     * not offer. Every check the client used to run passes it.
     */
    private static void mintingHostIsRefused(Path dir, PlayerKeys hostKeys,
                                             PlayerKeys joinerKeys) throws Exception {
        System.out.println("  [H1: a host that mints is refused, and nothing is kept]");

        Path hostLog = dir.resolve("trust-host.jsonl");
        Path joinerLog = dir.resolve("trust-joiner.jsonl");

        EventLog log = new EventLog(hostLog);
        MarketBootstrap.createMarket(log, HOST, "minting market", hostKeys);
        UUID marketId = log.marketId();
        long honestEvents = log.lastSeq();

        // Appended straight to the log, which is what "a modified host" means: the
        // check that would have stopped this is in the host's own append path, and a
        // host that has been changed simply does not run it.
        Event.WelcomeGrant wg = new Event.WelcomeGrant();
        wg.userId = HOST;
        wg.marketId = marketId;
        wg.targetUserId = HOST;
        wg.amount = MINTED;
        wg.clientEventId = UUID.randomUUID().toString();
        wg.timestamp = System.currentTimeMillis();
        log.append(wg, hostKeys.sign(EventCanonical.canonicalPayload(wg)));

        check("the doctored history is one event longer", log.lastSeq() - honestEvents, 1);

        int port = freePort();
        HostServer host = new HostServer(port, hostLog, "minter", HOST.toString(),
                hostKeys, new PeerCache(dir.resolve("trust-host-peers.json")), 0L);
        Thread hostThread = new Thread(() -> {
            try { host.start(); } catch (IOException e) { /* stopped */ }
        }, "trust-test-host");
        hostThread.setDaemon(true);
        hostThread.start();

        IOException bindError = host.awaitBound(5000);
        if (bindError != null) throw bindError;

        final String[] refusal = new String[1];
        try {
            EventLog fresh = new EventLog(joinerLog);
            MarketClient client = new MarketClient(JOINER, "Joiner", joinerKeys,
                    fresh, true, new PeerCache(dir.resolve("trust-joiner-peers.json")), 0);
            client.setOnRejected(why -> refusal[0] = why);

            int refused = 0;
            try {
                client.connect("127.0.0.1", port);
            } catch (IOException e) {
                refused = 1;
            }
            check("joining is refused", refused, 1);
            check("and says the rules refused it, not the signature",
                    String.valueOf(refusal[0]).contains("rules refuse") ? 1 : 0, 1);
            check("naming the figure the market actually publishes",
                    String.valueOf(refusal[0]).contains("grant must be exactly") ? 1 : 0, 1);

            // The two that matter. A client that refuses but keeps the money, or writes
            // it down and re-serves it when it hosts next, has refused nothing.
            check("the minted credits are not in our ledger",
                    client.state().wallets().getBalance(HOST), 0);
            check("and the event is not in our log",
                    Files.exists(joinerLog)
                            ? countLines(joinerLog) - honestEvents : 0, 0);

            client.disconnect();
        } finally {
            host.stop();
        }
    }

    /**
     * H2: the same shape, honestly produced, still syncs end to end.
     *
     * This is the check that says the fix is safe to run. Asking validate on every
     * broadcast is only correct because a host asks the identical question against the
     * identical state before it appends — if that were ever untrue, the client would
     * disconnect mid-session over an event that was perfectly fine.
     */
    private static void honestHostStillSyncs(Path dir, PlayerKeys hostKeys,
                                             PlayerKeys joinerKeys) throws Exception {
        System.out.println("  [H2: an honest history of the same shape still syncs]");

        Path hostLog = dir.resolve("trust-honest-host.jsonl");
        Path joinerLog = dir.resolve("trust-honest-joiner.jsonl");

        EventLog log = new EventLog(hostLog);
        MarketBootstrap.createMarket(log, HOST, "honest market", hostKeys);
        UUID marketId = log.marketId();

        Event.KeyRegistered kr = new Event.KeyRegistered();
        kr.userId = JOINER;
        kr.marketId = marketId;
        kr.publicKey = joinerKeys.publicKeyString();
        kr.timestamp = System.currentTimeMillis();
        log.append(kr, joinerKeys.sign(EventCanonical.canonicalPayload(kr)));

        // A real grant: the market's published figure, once, to a registered identity.
        // The event class H1 refuses, in the shape that must go through.
        Event.WelcomeGrant wg = new Event.WelcomeGrant();
        wg.userId = HOST;
        wg.marketId = marketId;
        wg.targetUserId = JOINER;
        wg.amount = ServerConfig.DEFAULT_WELCOME_GRANT;
        wg.clientEventId = UUID.randomUUID().toString();
        wg.timestamp = System.currentTimeMillis();
        log.append(wg, hostKeys.sign(EventCanonical.canonicalPayload(wg)));

        Event.Deposit dep = new Event.Deposit();
        dep.userId = JOINER;
        dep.marketId = marketId;
        dep.itemId = IRON;
        dep.quantity = 10;
        dep.timestamp = System.currentTimeMillis();
        log.append(dep, joinerKeys.sign(EventCanonical.canonicalPayload(dep)));

        Event.PlaceOrder ask = new Event.PlaceOrder();
        ask.userId = JOINER;
        ask.marketId = marketId;
        ask.itemId = IRON;
        ask.price = 5;
        ask.volume = 4;
        ask.isBid = false;
        ask.timestamp = System.currentTimeMillis();
        log.append(ask, joinerKeys.sign(EventCanonical.canonicalPayload(ask)));

        long expected = log.lastSeq();

        int port = freePort();
        HostServer host = new HostServer(port, hostLog, "honest", HOST.toString(),
                hostKeys, new PeerCache(dir.resolve("trust-host-peers.json")), 0L);
        Thread hostThread = new Thread(() -> {
            try { host.start(); } catch (IOException e) { /* stopped */ }
        }, "trust-test-honest-host");
        hostThread.setDaemon(true);
        hostThread.start();

        IOException bindError = host.awaitBound(5000);
        if (bindError != null) throw bindError;

        try {
            EventLog fresh = new EventLog(joinerLog);
            MarketClient client = new MarketClient(JOINER, "Joiner", joinerKeys,
                    fresh, true, new PeerCache(dir.resolve("trust-joiner-peers.json")), 0);
            final String[] refusal = new String[1];
            client.setOnRejected(why -> refusal[0] = why);

            int joined = 1;
            try {
                client.connect("127.0.0.1", port);
            } catch (IOException e) {
                joined = 0;
                System.out.println("    (connect threw: " + e.getMessage() + ")");
            }
            check("joining succeeds", joined, 1);
            check("nothing was refused", refusal[0] == null ? 1 : 0, 1);
            check("the whole history arrived", countLines(joinerLog), expected);

            // The grant is the event the fix scrutinises, so prove it actually landed
            // rather than merely that nothing threw. Four of the ten iron are reserved
            // against the resting ask; the credits are the grant, untouched.
            check("the legitimate grant was applied",
                    client.state().wallets().getBalance(JOINER),
                    ServerConfig.DEFAULT_WELCOME_GRANT);
            check("and so was everything after it",
                    client.state().itemBalances().getBalance(JOINER, IRON), 6);

            client.disconnect();
        } finally {
            host.stop();
        }
    }

    private static long countLines(Path p) throws IOException {
        List<String> lines = Files.readAllLines(p);
        long n = 0;
        for (String l : lines) if (l != null && !l.trim().isEmpty()) n++;
        return n;
    }

    private static int freePort() throws IOException {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
