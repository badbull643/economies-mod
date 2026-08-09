package io.github.badbull643.economiesmod.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public class MarketTests {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB   = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID DAVE  = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID THIEF = UUID.fromString("00000000-0000-0000-0000-000000000099");

    private static final String IRON    = "minecraft:iron_ingot";
    private static final String DIAMOND = "minecraft:diamond";
    private static final String WOOD    = "minecraft:oak_log";

    public static void main(String[] args) throws Exception {

        section("A3: sell rejected when items insufficient");
        {
            MarketState m = new MarketState();
            m.deposit(ALICE, IRON, 50);
            MarketState.SubmitResult f = m.submitOrder(new Order(1, 10, IRON, 60, false, ALICE));
            check("oversell rejected", f.accepted ? 1 : 0, 0);
            check("balance untouched by rejection", m.itemBalances().getBalance(ALICE, IRON), 50);
        }

        section("A4: buy rejected when currency insufficient");
        {
            MarketState m = new MarketState();
            m.wallets().setBalance(BOB, 500L);
            MarketState.SubmitResult f = m.submitOrder(new Order(1, 10, IRON, 60, true, BOB));
            check("overbuy rejected", f.accepted ? 1 : 0, 0);
            check("wallet untouched by rejection", m.wallets().getBalance(BOB), 500);
        }

        section("A6: zero-balance user cannot list anything");
        {
            MarketState m = new MarketState();
            check("sell with no deposit",
                    m.submitOrder(new Order(1, 10, IRON, 1, false, CAROL)).accepted ? 1 : 0, 0);
            check("buy with no currency",
                    m.submitOrder(new Order(2, 10, IRON, 1, true, CAROL)).accepted ? 1 : 0, 0);
            check("no phantom item balance", m.itemBalances().getBalance(CAROL, IRON), 0);
            check("no phantom wallet", m.wallets().getBalance(CAROL), 0);
        }

        section("B1: aggressive buyer gets refund for unspent reservation");
        {
            MarketState m = new MarketState();
            m.deposit(ALICE, IRON, 100);
            m.wallets().setBalance(BOB, 1000L);
            m.submitOrder(new Order(1, 8, IRON, 60, false, ALICE));
            MarketState.SubmitResult f = m.submitOrder(new Order(2, 10, IRON, 60, true, BOB));
            check("fill at resting price", f.fills.get(0).price(), 8);
            check("seller received 480", m.wallets().getBalance(ALICE), 480);
            check("buyer paid 480 not 600", m.wallets().getBalance(BOB), 520);
        }

        section("B2: buyer walking levels, refund accumulates correctly");
        {
            MarketState m = new MarketState();
            m.deposit(ALICE, IRON, 100);
            m.deposit(CAROL, IRON, 100);
            m.wallets().setBalance(BOB, 2000L);
            m.submitOrder(new Order(1, 8, IRON, 30, false, ALICE));
            m.submitOrder(new Order(2, 9, IRON, 30, false, CAROL));
            MarketState.SubmitResult f = m.submitOrder(new Order(3, 10, IRON, 60, true, BOB));
            check("two fills", f.fills.size(), 2);
            check("buyer spent 510", m.wallets().getBalance(BOB), 1490);
            check("seller alice got 240", m.wallets().getBalance(ALICE), 240);
            check("seller carol got 270", m.wallets().getBalance(CAROL), 270);
        }

        section("D2: cannot sell item A backed by item B's balance");
        {
            MarketState m = new MarketState();
            m.deposit(ALICE, IRON, 100);
            MarketState.SubmitResult f = m.submitOrder(new Order(1, 10, DIAMOND, 60, false, ALICE));
            check("sell diamond with iron balance rejected", f.accepted ? 1 : 0, 0);
            check("iron untouched", m.itemBalances().getBalance(ALICE, IRON), 100);
        }

        section("D3: orders for different items never cross");
        {
            MarketState m = new MarketState();
            m.deposit(ALICE, DIAMOND, 100);
            m.wallets().setBalance(BOB, 10000L);
            m.submitOrder(new Order(1, 10, DIAMOND, 60, false, ALICE));
            MarketState.SubmitResult f = m.submitOrder(new Order(2, 10, IRON, 60, true, BOB));
            check("no cross-item match", f.fills.size(), 0);
            check("diamond seller still reserved", m.itemBalances().getBalance(ALICE, DIAMOND), 40);
            check("iron buyer got nothing", m.itemBalances().getBalance(BOB, IRON), 0);
        }

        section("G1: zero-volume order");
        {
            MarketState m = new MarketState();
            m.deposit(ALICE, IRON, 100);
            MarketState.SubmitResult f = m.submitOrder(new Order(1, 10, IRON, 0, false, ALICE));
            check("zero volume rejected", f.accepted ? 1 : 0, 0);
            check("zero volume reserved nothing", m.itemBalances().getBalance(ALICE, IRON), 100);
        }

        section("G8: negative price rejected");
        {
            MarketState m = new MarketState();
            m.deposit(ALICE, IRON, 100);
            MarketState.SubmitResult f = m.submitOrder(new Order(1, -10, IRON, 60, false, ALICE));
            check("negative price rejected", f.accepted ? 1 : 0, 0);
            check("balance untouched", m.itemBalances().getBalance(ALICE, IRON), 100);
        }


        System.out.println("\nGROUP I — signing and identity");

        section("I1: signature round-trips");
        {
            PlayerKeys keys = PlayerKeys.generate();
            Event.Deposit d = new Event.Deposit();
            d.userId = ALICE;
            d.clientEventId = "abc-123";
            d.timestamp = 1000L;
            d.itemId = IRON;
            d.quantity = 50;

            String payload = EventCanonical.canonicalPayload(d);
            String sig = keys.sign(payload);
            check("valid signature verifies",
                    PlayerKeys.verify(payload, sig, keys.publicKey()) ? 1 : 0, 1);
        }

        section("I2: tampering with any signed field breaks the signature");
        {
            PlayerKeys keys = PlayerKeys.generate();
            Event.PlaceOrder p = new Event.PlaceOrder();
            p.userId = ALICE;
            p.clientEventId = "abc-123";
            p.timestamp = 1000L;
            p.itemId = IRON;
            p.price = 10;
            p.volume = 60;
            p.isBid = false;

            String sig = keys.sign(EventCanonical.canonicalPayload(p));

            p.volume = 600;   // attacker inflates the order
            check("tampered volume rejected",
                    PlayerKeys.verify(EventCanonical.canonicalPayload(p), sig, keys.publicKey()) ? 1 : 0, 0);

            p.volume = 60;
            p.price = 1;      // attacker changes the price
            check("tampered price rejected",
                    PlayerKeys.verify(EventCanonical.canonicalPayload(p), sig, keys.publicKey()) ? 1 : 0, 0);

            p.price = 10;
            p.userId = BOB;   // attacker reassigns ownership
            check("tampered userId rejected",
                    PlayerKeys.verify(EventCanonical.canonicalPayload(p), sig, keys.publicKey()) ? 1 : 0, 0);

            p.userId = ALICE;
            p.clientEventId = "different";   // signature lifted onto another proposal
            check("tampered clientEventId rejected",
                    PlayerKeys.verify(EventCanonical.canonicalPayload(p), sig, keys.publicKey()) ? 1 : 0, 0);
        }

        section("I3: a different key cannot forge");
        {
            PlayerKeys alice = PlayerKeys.generate();
            PlayerKeys mallory = PlayerKeys.generate();

            Event.Withdraw w = new Event.Withdraw();
            w.userId = ALICE;
            w.clientEventId = "x";
            w.timestamp = 1L;
            w.itemId = IRON;
            w.quantity = 100;

            String payload = EventCanonical.canonicalPayload(w);
            String mallorySig = mallory.sign(payload);

            check("mallory's signature fails against alice's key",
                    PlayerKeys.verify(payload, mallorySig, alice.publicKey()) ? 1 : 0, 0);
        }

        section("I4: key registry rejects a changed key");
        {
            Path regFile = Paths.get("./test-keys.json");
            Files.deleteIfExists(regFile);

            KeyRegistry reg = new KeyRegistry(regFile, true);
            PlayerKeys real = PlayerKeys.generate();
            PlayerKeys impostor = PlayerKeys.generate();

            check("first key accepted (TOFU)",
                    reg.register(ALICE, real.publicKeyString()) ? 1 : 0, 1);
            check("same key accepted again",
                    reg.register(ALICE, real.publicKeyString()) ? 1 : 0, 1);
            check("different key refused",
                    reg.register(ALICE, impostor.publicKeyString()) ? 1 : 0, 0);
            check("lookup returns the real key",
                    PlayerKeys.encodePublic(reg.lookup(ALICE)).equals(real.publicKeyString()) ? 1 : 0, 1);
        }

        section("I5: registry persists across reload");
        {
            Path regFile = Paths.get("./test-keys2.json");
            Files.deleteIfExists(regFile);

            PlayerKeys keys = PlayerKeys.generate();
            new KeyRegistry(regFile, true).register(ALICE, keys.publicKeyString());

            KeyRegistry reloaded = new KeyRegistry(regFile, true);
            check("known after reload", reloaded.isKnown(ALICE) ? 1 : 0, 1);
            check("impostor still refused after reload",
                    reloaded.register(ALICE, PlayerKeys.generate().publicKeyString()) ? 1 : 0, 0);
        }

        section("I6: trust-on-first-use off rejects unknown identities");
        {
            Path regFile = Paths.get("./test-keys3.json");
            Files.deleteIfExists(regFile);

            KeyRegistry strict = new KeyRegistry(regFile, false);
            check("unknown identity refused when TOFU is off",
                    strict.register(ALICE, PlayerKeys.generate().publicKeyString()) ? 1 : 0, 0);
        }
    }

    // ── helpers ──

    private static int failures = 0;
    private static int checksRun = 0;

    private static void section(String name) {
        System.out.println("  [" + name + "]");
    }

    private static void check(String label, long actual, long expected) {
        checksRun++;
        boolean ok = actual == expected;
        if (!ok) failures++;
        System.out.println((ok ? "    ok   " : "    FAIL ") + label
                + " — expected " + expected + ", got " + actual);
    }

    private static long restingSellVolume(MarketState m, String itemId) {
        long total = 0;
        for (Deque<Order> q : m.bookFor(itemId).asks().values()) {
            for (Order o : q) total += o.volume();
        }
        return total;
    }

    private static long restingBidReservation(MarketState m, String itemId) {
        long total = 0;
        for (Deque<Order> q : m.bookFor(itemId).bids().values()) {
            for (Order o : q) total += o.volume() * o.value();
        }
        return total;
    }

    /** Appends to the log and applies to state, mirroring what MarketStateHolder.submit does. */
    private static EventApplier.Result apply(EventLog log, MarketState state, Event e)
            throws IOException {
        SequencedEvent se = log.append(e);
        return EventApplier.apply(state, se);
    }

    private static Event.Deposit deposit(UUID user, String item, long qty) {
        Event.Deposit e = new Event.Deposit();
        e.userId = user; e.itemId = item; e.quantity = qty;
        return e;
    }

    private static Event.Withdraw withdraw(UUID user, String item, long qty) {
        Event.Withdraw e = new Event.Withdraw();
        e.userId = user; e.itemId = item; e.quantity = qty;
        return e;
    }

    private static Event.PlaceOrder placeOrder(UUID user, String item, long price,
                                               long volume, boolean isBid) {
        Event.PlaceOrder e = new Event.PlaceOrder();
        e.userId = user; e.itemId = item; e.price = price; e.volume = volume; e.isBid = isBid;
        return e;
    }

    private static Event.CancelOrder cancelOrder(UUID user, String item, long orderId, boolean isBid) {
        Event.CancelOrder e = new Event.CancelOrder();
        e.userId = user; e.itemId = item; e.orderId = orderId; e.isBid = isBid;
        return e;
    }

    private static Event.InjectCredits injectCredits(UUID user, long amount) {
        Event.InjectCredits e = new Event.InjectCredits();
        e.userId = user; e.targetUserId = user; e.amount = amount;
        return e;
    }

}