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

        section("F9: cancel via event replays correctly");
        {
            Path file = scratch("test-log-f9.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(log, live);

            apply(log, live, deposit(ALICE, IRON, 100));
            Event.PlaceOrder po = placeOrder(ALICE, IRON, 10, 60, false);
            po.marketId = live.marketId();
            SequencedEvent orderSe = log.append(po,
                    testKeys().sign(EventCanonical.canonicalPayload(po)));
            EventApplier.apply(live, orderSe);
            check("reserved", live.itemBalances().getBalance(ALICE, IRON), 40);

            apply(log, live, cancelOrder(ALICE, IRON, orderSe.seq, false));
            check("refunded via event", live.itemBalances().getBalance(ALICE, IRON), 100);

            MarketState replayed = EventApplier.replay(log);
            check("replay agrees", replayed.itemBalances().getBalance(ALICE, IRON), 100);
            check("no resting order after replay",
                    replayed.bookFor(IRON).restingAsks().size(), 0);
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
            Path regFile = scratch("test-keys.json");
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
            Path regFile = scratch("test-keys2.json");
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
            Path regFile = scratch("test-keys3.json");
            Files.deleteIfExists(regFile);

            KeyRegistry strict = new KeyRegistry(regFile, false);
            check("unknown identity refused when TOFU is off",
                    strict.register(ALICE, PlayerKeys.generate().publicKeyString()) ? 1 : 0, 0);
        }

        section("J1: the log stores signatures, so authorship survives a round trip");
        {
            Path file = scratch("test-log-j1.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(log, live);

            Event.Deposit d = deposit(ALICE, IRON, 100);
            String sig = testKeys().sign(EventCanonical.canonicalPayload(d));
            log.append(d, sig);

            // Re-read from disk — this is the path an import or an audit would take.
            EventLog reopened = new EventLog(file);
            SequencedEvent read = reopened.readFrom(2).get(0);
            check("signature persisted", read.signature != null ? 1 : 0, 1);
            check("signature still verifies after reload",
                    PlayerKeys.verify(EventCanonical.canonicalPayload(read.event),
                            read.signature, testKeys().publicKey()) ? 1 : 0, 1);
            check("chain still intact", reopened.verifyChain(), -1);
        }

        section("J2: the hash chain covers the signature");
        {
            Path file = scratch("test-log-j2.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(log, live);
            apply(log, live, deposit(ALICE, IRON, 100));

            // Swap in a valid signature from a different event. Without the signature
            // in the hash, this would go undetected.
            List<String> lines = Files.readAllLines(file);
            int at = lineOf(lines, "Deposit");
            String forged = lines.get(at).replaceAll("\"signature\":\"[^\"]*\"",
                    "\"signature\":\"" + testKeys().sign("something else") + "\"");
            lines.set(at, forged);
            Files.write(file, lines);

            EventLog tampered = new EventLog(file);
            // Derived from where the forgery went rather than written out: the seq the
            // deposit lands on is genesis plus whatever else genesis writes, and hard-
            // coding it made this assert a layout that was free to change.
            check("swapped signature breaks the chain", tampered.verifyChain(), at + 1);
        }

        section("J3: genesis rules — a market has exactly one birth certificate");
        {
            MarketState m = new MarketState();

            // seq 1 must be MarketCreated
            SequencedEvent notGenesis = new SequencedEvent();
            notGenesis.seq = 1;
            notGenesis.event = deposit(ALICE, IRON, 10);
            check("non-genesis rejected at seq 1",
                    EventApplier.validate(m, notGenesis).accepted ? 1 : 0, 0);

            // MarketCreated must be seq 1
            Event.MarketCreated mc = new Event.MarketCreated();
            mc.userId = ALICE;
            mc.marketId = UUID.randomUUID();
            mc.marketName = "late";
            SequencedEvent lateGenesis = new SequencedEvent();
            lateGenesis.seq = 5;
            lateGenesis.event = mc;
            check("genesis rejected after seq 1",
                    EventApplier.validate(m, lateGenesis).accepted ? 1 : 0, 0);

            // a nameless market is refused
            Event.MarketCreated nameless = new Event.MarketCreated();
            nameless.userId = ALICE;
            nameless.marketId = UUID.randomUUID();
            nameless.marketName = "  ";
            SequencedEvent namelessSe = new SequencedEvent();
            namelessSe.seq = 1;
            namelessSe.event = nameless;
            check("nameless market rejected",
                    EventApplier.validate(m, namelessSe).accepted ? 1 : 0, 0);
        }

        section("J4: market identity survives replay");
        {
            Path file = scratch("test-log-j4.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            Event.MarketCreated mc = seedMarket(log, live);
            apply(log, live, deposit(ALICE, IRON, 100));

            MarketState replayed = EventApplier.replay(log);
            check("replayed market id matches",
                    replayed.marketId().equals(mc.marketId) ? 1 : 0, 1);
            check("replayed state matches",
                    replayed.itemBalances().getBalance(ALICE, IRON), 100);
            check("log reports its own market id without replaying",
                    log.marketId().equals(mc.marketId) ? 1 : 0, 1);
        }

        section("J6: a log with no genesis replays to nothing at all");
        {
            // Exactly the shape of a pre-market-identity log: no MarketCreated, just
            // events. Rejecting only the first one would rebuild almost all the state.
            Path file = scratch("test-log-j6.jsonl");
            Files.deleteIfExists(file);
            EventLog legacy = new EventLog(file);

            Event.Deposit d1 = deposit(ALICE, IRON, 100);
            legacy.append(d1, testKeys().sign(EventCanonical.canonicalPayload(d1)));
            Event.Deposit d2 = deposit(BOB, IRON, 250);
            legacy.append(d2, testKeys().sign(EventCanonical.canonicalPayload(d2)));
            Event.WelcomeGrant wg = new Event.WelcomeGrant();
            wg.userId = ALICE; wg.targetUserId = ALICE; wg.amount = 5000;
            legacy.append(wg, testKeys().sign(EventCanonical.canonicalPayload(wg)));

            check("legacy log really has events", legacy.lastSeq(), 3);

            MarketState replayed = EventApplier.replay(legacy);
            check("no market identity", replayed.marketId() == null ? 1 : 0, 1);
            check("first event not applied",
                    replayed.itemBalances().getBalance(ALICE, IRON), 0);
            check("later events not applied either",
                    replayed.itemBalances().getBalance(BOB, IRON), 0);
            check("credits not restored", replayed.wallets().getBalance(ALICE), 0);
        }

        section("J7: two EventLog instances cannot both write to one file");
        {
            // The exact shape of the corruption seen in testing: a HostServer and a
            // client log both open on one file, each with its own idea of the end.
            Path file = scratch("test-log-j7.jsonl");
            Files.deleteIfExists(file);
            EventLog first = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(first, live);

            EventLog second = new EventLog(file);   // same file, own in-memory position

            Event.Deposit d = deposit(ALICE, IRON, 10);
            second.append(d, testKeys().sign(EventCanonical.canonicalPayload(d)));

            int refused = 0;
            try {
                Event.Deposit other = deposit(BOB, IRON, 20);
                first.append(other, testKeys().sign(EventCanonical.canonicalPayload(other)));
            } catch (IOException expected) {
                refused = 1;
            }
            check("stale writer refused", refused, 1);
            check("chain intact — no duplicate seq", new EventLog(file).verifyChain(), -1);
        }

        section("K1: identity registration is self-certifying and once-only");
        {
            Path file = scratch("test-log-k1.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(log, live);

            check("creator registered by genesis", live.isRegistered(ALICE) ? 1 : 0, 1);
            check("bob not registered yet", live.isRegistered(BOB) ? 1 : 0, 0);

            register(log, live, BOB);
            check("bob registered", live.isRegistered(BOB) ? 1 : 0, 1);

            // Second registration for the same identity is a key swap by another name.
            Event.KeyRegistered again = new Event.KeyRegistered();
            again.userId = BOB;
            again.marketId = live.marketId();
            again.publicKey = PlayerKeys.generate().publicKeyString();
            SequencedEvent se = new SequencedEvent();
            se.seq = log.lastSeq() + 1;
            se.event = again;
            check("re-registration rejected",
                    EventApplier.validate(live, se).accepted ? 1 : 0, 0);
        }

        section("K2: an unregistered author cannot write anything");
        {
            Path file = scratch("test-log-k2.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(log, live);

            SequencedEvent se = new SequencedEvent();
            se.seq = log.lastSeq() + 1;
            se.event = deposit(CAROL, IRON, 10);
            check("deposit by unregistered user rejected",
                    EventApplier.validate(live, se).accepted ? 1 : 0, 0);
        }

        section("K3: welcome grant is once per identity per market");
        {
            Path file = scratch("test-log-k3.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(log, live);
            register(log, live, BOB);

            Event.WelcomeGrant wg = new Event.WelcomeGrant();
            wg.userId = ALICE; wg.targetUserId = BOB; wg.amount = 1000;
            wg.marketId = live.marketId();
            SequencedEvent se = log.append(wg,
                    testKeys().sign(EventCanonical.canonicalPayload(wg)));
            EventApplier.apply(live, se);
            check("granted", live.wallets().getBalance(BOB), 1000);

            SequencedEvent second = new SequencedEvent();
            second.seq = log.lastSeq() + 1;
            second.event = wg;
            check("second grant rejected",
                    EventApplier.validate(live, second).accepted ? 1 : 0, 0);

            Event.WelcomeGrant toStranger = new Event.WelcomeGrant();
            toStranger.userId = ALICE; toStranger.targetUserId = DAVE; toStranger.amount = 1000;
            toStranger.marketId = live.marketId();
            SequencedEvent third = new SequencedEvent();
            third.seq = log.lastSeq() + 1;
            third.event = toStranger;
            check("grant to unregistered identity rejected",
                    EventApplier.validate(live, third).accepted ? 1 : 0, 0);
        }

        section("L1: a signed event cannot be replayed into a different market");
        {
            // Lift a genuine, correctly-signed event out of one market and offer it to
            // another. Before marketId was signed this verified perfectly, which made
            // hand-forging a migration trivial.
            Path fileX = scratch("test-log-l1x.jsonl");
            Path fileY = scratch("test-log-l1y.jsonl");
            Files.deleteIfExists(fileX);
            Files.deleteIfExists(fileY);

            EventLog x = new EventLog(fileX);
            MarketState marketX = new MarketState();
            seedMarket(x, marketX);
            Event.Deposit d = deposit(ALICE, IRON, 500);
            apply(x, marketX, d);
            check("deposit valid in its own market",
                    marketX.itemBalances().getBalance(ALICE, IRON), 500);

            EventLog y = new EventLog(fileY);
            MarketState marketY = new MarketState();
            seedMarket(y, marketY);

            // The very same event object — same signature, same author, different market.
            check("signature still verifies (it is genuine)",
                    PlayerKeys.verify(EventCanonical.canonicalPayload(d),
                            testKeys().sign(EventCanonical.canonicalPayload(d)),
                            testKeys().publicKey()) ? 1 : 0, 1);

            SequencedEvent replayed = new SequencedEvent();
            replayed.seq = y.lastSeq() + 1;
            replayed.event = d;
            check("but it is refused by the other market",
                    EventApplier.validate(marketY, replayed).accepted ? 1 : 0, 0);
        }

        section("L3: an unreadable log degrades instead of throwing");
        {
            // A log holding an event type this build doesn't know — exactly what a log
            // from an older version looks like. This used to throw out of the EventLog
            // constructor, through loadLocal, and crash the world on load, which left
            // no way to reach the Reset that would have fixed it.
            Path file = scratch("test-log-l3.jsonl");
            Files.deleteIfExists(file);
            Files.write(file, java.util.Arrays.asList(
                    "{\"seq\":1,\"prevHash\":\"0\",\"hash\":\"abc\",\"eventType\":\"InjectCredits\","
                            + "\"event\":{\"amount\":1000},\"signature\":null}"));

            int threw = 0;
            EventLog log = null;
            try {
                log = new EventLog(file);
            } catch (Exception e) {
                threw = 1;
            }
            check("constructing over an unreadable log does not throw", threw, 0);
            check("log reports itself unreadable", log.isUnreadable() ? 1 : 0, 1);
            check("verifyChain flags it", log.verifyChain() != -1 ? 1 : 0, 1);
            check("damage is explained", log.damageReason() != null ? 1 : 0, 1);

            // Replay must also survive it, and produce nothing usable.
            MarketState replayed = EventApplier.replay(log);
            check("replays to no market", replayed.marketId() == null ? 1 : 0, 1);
        }

        section("L4: import refuses to overwrite an unreadable log");
        {
            // lastSeq() reads 0 for a log we can't parse, so the "already holds a
            // market" guard alone would let the copy destroy history that a matching
            // build could still read.
            Path src = scratch("test-archive-l4.jsonl");
            Path dest = scratch("test-import-l4.jsonl");
            Files.deleteIfExists(src);
            Files.deleteIfExists(dest);

            EventLog a = new EventLog(src);
            MarketState live = new MarketState();
            seedMarket(a, live);

            Files.write(dest, java.util.Arrays.asList(
                    "{\"seq\":1,\"prevHash\":\"0\",\"hash\":\"abc\",\"eventType\":\"InjectCredits\","
                            + "\"event\":{\"amount\":1000},\"signature\":null}"));
            check("unreadable destination still reports lastSeq 0",
                    new EventLog(dest).lastSeq(), 0);

            int refused = 0;
            try {
                MarketArchive.importInto(src, dest);
            } catch (MarketArchive.InvalidArchive e) {
                refused = 1;
            }
            check("import refused", refused, 1);
            check("destination untouched", Files.readAllLines(dest).size(), 1);
        }

        section("L5: a host cannot forge an event in someone else's name");
        {
            // What a malicious host would actually do: author an event as another
            // player and chain it correctly. Nothing about the hash chain objects —
            // the host computes it — so only the signature check stands in the way.
            // This is the same verdict a client now reaches on a broadcast line.
            Path file = scratch("test-log-l5.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(log, live);          // ALICE is the creator, registered
            register(log, live, BOB);

            PlayerKeys maliciousHost = PlayerKeys.generate();

            // "Bob deposits 10,000 iron", signed by the host rather than by Bob.
            Event.Deposit forged = deposit(BOB, IRON, 10000);
            forged.marketId = live.marketId();
            String hostSig = maliciousHost.sign(EventCanonical.canonicalPayload(forged));

            check("forged event is refused",
                    EventVerifier.verify(live, forged, hostSig) != null ? 1 : 0, 1);
            check("refused for the signature, not something incidental",
                    "bad signature".equals(EventVerifier.verify(live, forged, hostSig))
                            ? 1 : 0, 1);

            // And the honest version of the same event still passes.
            String bobSig = testKeys().sign(EventCanonical.canonicalPayload(forged));
            check("genuinely signed event accepted",
                    EventVerifier.verify(live, forged, bobSig) == null ? 1 : 0, 1);

            // An unsigned line is refused too — the state before signatures were stored.
            check("unsigned event refused",
                    EventVerifier.verify(live, forged, null) != null ? 1 : 0, 1);
        }

        section("L6: high-water mark remembers how far a market has reached");
        {
            Path file = scratch("test-highwater-l6.json");
            Files.deleteIfExists(file);
            UUID marketA = UUID.randomUUID();
            UUID marketB = UUID.randomUUID();

            MarketHighWater hw = new MarketHighWater(file);
            check("nothing seen yet", hw.seenFor(marketA), 0);

            hw.observe(marketA, 40);
            check("records what it saw", hw.seenFor(marketA), 40);

            hw.observe(marketA, 12);
            check("never moves backwards", hw.seenFor(marketA), 40);

            // Survives a restart — the whole point, since the peer that knew the market
            // was further along is usually offline by the time someone hosts stale.
            check("survives reload", new MarketHighWater(file).seenFor(marketA), 40);

            check("says nothing about a market it hasn't seen", hw.seenFor(marketB), 0);

            // A different market resets it, so a fresh market isn't judged against an
            // old one's height.
            hw.observe(marketB, 3);
            check("switching market resets", hw.seenFor(marketB), 3);
            check("old market forgotten", hw.seenFor(marketA), 0);

            hw.clear();
            check("cleared with the market it described",
                    new MarketHighWater(file).seenFor(marketB), 0);
        }

        section("M1: net position counts what's locked in resting orders");
        {
            Path file = scratch("test-log-m1.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(log, live);

            live.wallets().setBalance(ALICE, 500);
            apply(log, live, deposit(ALICE, IRON, 100));
            apply(log, live, placeOrder(ALICE, IRON, 10, 60, false));   // ask: locks 60 iron
            apply(log, live, placeOrder(ALICE, DIAMOND, 20, 5, true));  // bid: locks 100 credits

            NetPosition pos = NetPosition.of(live, ALICE);
            check("credits include the bid reservation", pos.credits, 500);
            check("items include the resting ask", pos.items.get(IRON), 100);
            check("no phantom items", pos.items.containsKey(DIAMOND) ? 1 : 0, 0);
        }

        section("M2: migration credits a beneficiary and can't be replayed");
        {
            Path file = scratch("test-log-m2.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(log, live);
            register(log, live, BOB);

            UUID oldMarket = UUID.randomUUID();
            Event.MigrateBalance mb = new Event.MigrateBalance();
            mb.userId = ALICE;                 // the host authors it
            mb.marketId = live.marketId();
            mb.fromMarketId = oldMarket;
            mb.fromMarketName = "abandoned";
            mb.fromHeadSeq = 12;
            mb.fromHeadHash = "deadbeef";
            mb.beneficiary = BOB;
            mb.credits = 1400;
            mb.items = new java.util.TreeMap<>();
            mb.items.put(IRON, 50L);
            mb.foreignParticipants = java.util.Arrays.asList(BOB, CAROL);

            SequencedEvent se = log.append(mb,
                    testKeys().sign(EventCanonical.canonicalPayload(mb)));
            check("migration applies", EventApplier.apply(live, se).accepted ? 1 : 0, 1);
            check("credits arrived", live.wallets().getBalance(BOB), 1400);
            check("items arrived", live.itemBalances().getBalance(BOB, IRON), 50);

            SequencedEvent again = new SequencedEvent();
            again.seq = log.lastSeq() + 1;
            again.event = mb;
            check("same branch cannot be cashed twice",
                    EventApplier.validate(live, again).accepted ? 1 : 0, 0);

            // A market cannot be migrated into itself.
            Event.MigrateBalance self = new Event.MigrateBalance();
            self.userId = ALICE; self.marketId = live.marketId();
            self.fromMarketId = live.marketId(); self.beneficiary = BOB;
            SequencedEvent selfSe = new SequencedEvent();
            selfSe.seq = log.lastSeq() + 1;
            selfSe.event = self;
            check("self-migration rejected",
                    EventApplier.validate(live, selfSe).accepted ? 1 : 0, 0);
        }

        section("M3: migrating closes the abandon-and-rejoin grant loophole");
        {
            // C and D concentrate their grants into C, C migrates, D then tries to join
            // fresh and collect a second grant. The participant list is what stops it.
            Path file = scratch("test-log-m3.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(log, live);
            register(log, live, CAROL);
            register(log, live, DAVE);

            Event.MigrateBalance mb = new Event.MigrateBalance();
            mb.userId = ALICE; mb.marketId = live.marketId();
            mb.fromMarketId = UUID.randomUUID();
            mb.fromMarketName = "the fork";
            mb.beneficiary = CAROL;
            mb.credits = 1980;
            mb.items = new java.util.TreeMap<>();
            mb.foreignParticipants = java.util.Arrays.asList(CAROL, DAVE);
            SequencedEvent se = log.append(mb,
                    testKeys().sign(EventCanonical.canonicalPayload(mb)));
            EventApplier.apply(live, se);

            check("carol got her migrated balance", live.wallets().getBalance(CAROL), 1980);

            Event.WelcomeGrant wg = new Event.WelcomeGrant();
            wg.userId = ALICE; wg.targetUserId = DAVE; wg.amount = 1000;
            wg.marketId = live.marketId();
            SequencedEvent grant = new SequencedEvent();
            grant.seq = log.lastSeq() + 1;
            grant.event = wg;
            check("dave cannot collect a fresh grant",
                    EventApplier.validate(live, grant).accepted ? 1 : 0, 0);
            check("dave has nothing until he migrates his own branch",
                    live.wallets().getBalance(DAVE), 0);
        }

        section("M4: a migration signature survives an unordered map");
        {
            // items and participants are collections; if the canonical payload used
            // iteration order the signature would verify only by luck.
            Event.MigrateBalance a = new Event.MigrateBalance();
            a.userId = ALICE; a.marketId = UUID.randomUUID();
            a.fromMarketId = UUID.randomUUID(); a.beneficiary = BOB; a.credits = 5;
            a.items = new java.util.LinkedHashMap<>();
            a.items.put(IRON, 1L); a.items.put(DIAMOND, 2L); a.items.put(WOOD, 3L);
            a.foreignParticipants = java.util.Arrays.asList(CAROL, BOB, DAVE);

            Event.MigrateBalance b = new Event.MigrateBalance();
            b.userId = a.userId; b.marketId = a.marketId;
            b.fromMarketId = a.fromMarketId; b.beneficiary = a.beneficiary;
            b.credits = a.credits; b.clientEventId = a.clientEventId;
            b.timestamp = a.timestamp;
            b.items = new java.util.LinkedHashMap<>();
            b.items.put(WOOD, 3L); b.items.put(IRON, 1L); b.items.put(DIAMOND, 2L);
            b.foreignParticipants = java.util.Arrays.asList(DAVE, CAROL, BOB);

            check("same content, same payload regardless of order",
                    EventCanonical.canonicalPayload(a)
                            .equals(EventCanonical.canonicalPayload(b)) ? 1 : 0, 1);
        }

        section("M6: you cannot migrate into a market you already hold a position in");
        {
            // The mint: join and take the grant, reset, create your own market and take
            // that grant too, then migrate it back. Each new market has a fresh id, so
            // the per-branch replay guard never fires — only "are you already here" does.
            Path file = scratch("test-log-m6.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(log, live);
            register(log, live, BOB);

            Event.WelcomeGrant wg = new Event.WelcomeGrant();
            wg.userId = ALICE; wg.targetUserId = BOB; wg.amount = 1000;
            wg.marketId = live.marketId();
            SequencedEvent g = log.append(wg,
                    testKeys().sign(EventCanonical.canonicalPayload(wg)));
            EventApplier.apply(live, g);
            check("bob has his grant", live.wallets().getBalance(BOB), 1000);

            Event.MigrateBalance mb = new Event.MigrateBalance();
            mb.userId = ALICE; mb.marketId = live.marketId();
            mb.fromMarketId = UUID.randomUUID();   // a market bob made for himself
            mb.fromMarketName = "bob's own";
            mb.beneficiary = BOB;
            mb.credits = 1000;
            mb.items = new java.util.TreeMap<>();
            mb.foreignParticipants = java.util.Arrays.asList(BOB);

            SequencedEvent se = new SequencedEvent();
            se.seq = log.lastSeq() + 1;
            se.event = mb;
            check("migrating a second grant back in is refused",
                    EventApplier.validate(live, se).accepted ? 1 : 0, 0);
            check("balance unchanged", live.wallets().getBalance(BOB), 1000);

            // A newcomer with no position here is still fine.
            mb.beneficiary = CAROL;
            mb.foreignParticipants = java.util.Arrays.asList(CAROL);
            SequencedEvent ok = new SequencedEvent();
            ok.seq = log.lastSeq() + 1;
            ok.event = mb;
            check("a genuine outsider can still migrate",
                    EventApplier.validate(live, ok).accepted ? 1 : 0, 1);
        }

        section("M6b: and migrating is itself holding a position, which it was not");
        {
            // M6 above proves the mint is refused for somebody *registered* here. That
            // is the case that never mattered, because a migrant does not have to be.
            //
            // A MigrateBalance registers nobody and grants nobody, so neither of the two
            // tests M6 relies on is ever true of an identity that has only migrated. And
            // the per-branch guard is keyed to the source market, which is a fresh
            // random id every time somebody creates one. So the same identity could
            // create a market at the grant ceiling, take it, migrate in, reset, and
            // repeat — measured at four million credits in four passes, against a market
            // whose founder held fifty, without ever registering.
            //
            // isAccountedElsewhere is the test that is true of them: recordMigration
            // files every participant of the market they came from, and they are one of
            // those. It was already refusing them a second welcome grant; it just was
            // not being asked here.
            Path file = scratch("test-log-m6b.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(log, live);

            long seqAt = log.lastSeq();
            long carried = 0;
            int accepted = 0;

            // Four markets, each one Carol's own, each with a fresh id.
            for (int i = 0; i < 4; i++) {
                Event.MigrateBalance mb = new Event.MigrateBalance();
                mb.userId = ALICE;                      // the host authors it
                mb.marketId = live.marketId();
                mb.fromMarketId = UUID.randomUUID();    // a market carol made this time
                mb.fromMarketName = "carol's market " + i;
                mb.beneficiary = CAROL;
                mb.credits = MarketState.MAX_WELCOME_GRANT;
                mb.items = new java.util.TreeMap<>();
                mb.foreignParticipants = java.util.Arrays.asList(CAROL);

                SequencedEvent se = new SequencedEvent();
                se.seq = ++seqAt;
                se.event = mb;

                if (EventApplier.validate(live, se).accepted) {
                    accepted++;
                    carried += mb.credits;
                    EventApplier.apply(live, se);
                }
            }

            check("the first migration lands", accepted, 1);
            check("and every one after it is refused", accepted, 1);
            check("so what walked in is one market's worth, not four",
                    carried, MarketState.MAX_WELCOME_GRANT);
            check("which is what carol is actually holding",
                    live.wallets().getBalance(CAROL), MarketState.MAX_WELCOME_GRANT);
            check("and she never registered here at all",
                    live.isRegistered(CAROL) ? 1 : 0, 0);

            // Somebody who has genuinely never been here is still let in, or the rule
            // would have closed migration rather than bounded it.
            Event.MigrateBalance fresh = new Event.MigrateBalance();
            fresh.userId = ALICE;
            fresh.marketId = live.marketId();
            fresh.fromMarketId = UUID.randomUUID();
            fresh.beneficiary = DAVE;
            fresh.credits = 100;
            fresh.items = new java.util.TreeMap<>();
            fresh.foreignParticipants = java.util.Arrays.asList(DAVE);
            SequencedEvent se = new SequencedEvent();
            se.seq = ++seqAt;
            se.event = fresh;
            check("a genuine outsider is unaffected",
                    EventApplier.validate(live, se).accepted ? 1 : 0, 1);
        }

        section("M6e: two people leaving one market together both get in");
        {
            // The ordinary case, and the one M6b could not see. M6b migrates the same
            // identity repeatedly from markets it keeps creating, which is the abuse —
            // so a guard that refused *everyone from a market somebody had migrated out
            // of* passed M6b perfectly while breaking the case anybody would actually
            // hit: a pair of friends moving their market into a bigger one.
            //
            // The first migration files every participant of the market it came from, to
            // stop them collecting a welcome grant on top of a balance they already have.
            // Reading that set as "has migrated" turned the second friend away.
            Path file = scratch("test-log-m6e.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(log, live);

            UUID theirMarket = UUID.randomUUID();          // one market, two people
            List<UUID> both = java.util.Arrays.asList(CAROL, DAVE);
            long seqAt = log.lastSeq();
            int landed = 0;

            for (UUID who : both) {
                Event.MigrateBalance mb = new Event.MigrateBalance();
                mb.userId = ALICE;
                mb.marketId = live.marketId();
                mb.fromMarketId = theirMarket;             // the SAME source for both
                mb.fromMarketName = "their shared market";
                mb.beneficiary = who;
                mb.credits = 1000;
                mb.items = new java.util.TreeMap<>();
                mb.foreignParticipants = both;             // everyone registered there

                SequencedEvent se = new SequencedEvent();
                se.seq = ++seqAt;
                se.event = mb;
                if (EventApplier.validate(live, se).accepted) {
                    landed++;
                    EventApplier.apply(live, se);
                }
            }

            check("both of them land", landed, 2);
            check("carol brought hers", live.wallets().getBalance(CAROL), 1000);
            check("and dave brought his", live.wallets().getBalance(DAVE), 1000);

            // Still no welcome grant on top — that is what filing the participants is
            // actually for, and it has to keep working now something else does the
            // refusing.
            register(log, live, CAROL);
            check("but neither collects a grant as well",
                    grantRejection(live, ALICE, CAROL, live.welcomeGrant()) != null ? 1 : 0, 1);

            // And the mint is still shut: a second arrival by the same identity, from a
            // market they have just made, is refused however new that market's id is.
            Event.MigrateBalance again = new Event.MigrateBalance();
            again.userId = ALICE;
            again.marketId = live.marketId();
            again.fromMarketId = UUID.randomUUID();
            again.beneficiary = DAVE;
            again.credits = 1000;
            again.items = new java.util.TreeMap<>();
            again.foreignParticipants = java.util.Arrays.asList(DAVE);
            SequencedEvent se = new SequencedEvent();
            se.seq = ++seqAt;
            se.event = again;
            check("and a second helping is still refused",
                    EventApplier.validate(live, se).accepted ? 1 : 0, 0);
        }

        section("M6c: a host may cap how much money one migration carries in");
        {
            // The honest half of the same problem, which no rule above touches. Two
            // people arriving from a market that grants 1000, into one that grants 50,
            // multiply its supply by twenty-one — and the people already there go from
            // holding all of the money to holding five per cent of it. Nobody is robbed;
            // everybody is outbid.
            //
            // Host-local, because the receiving market is the only party that can say
            // what it will absorb, and because a group merging honestly produces exactly
            // the same arithmetic as somebody doing it deliberately.
            ServerConfig cfg = new ServerConfig();
            cfg.maxMigratedCredits = 0;
            check("zero accepts anything, so existing servers are unchanged",
                    cfg.problem() == null ? 1 : 0, 1);

            cfg.maxMigratedCredits = 500;
            check("a figure is allowed", cfg.problem() == null ? 1 : 0, 1);

            cfg.maxMigratedCredits = -1;
            check("negative is refused", cfg.problem() != null ? 1 : 0, 1);
            check("and says to use zero instead",
                    String.valueOf(cfg.problem()).contains("use 0") ? 1 : 0, 1);
        }

        section("M6d: a dedicated server does not take migrations unless told to");
        {
            // Migration solves bootstrapping among people who know each other. The
            // balance it carries was set by a welcome grant the migrant chose, in a
            // world they control, up to MAX_WELCOME_GRANT — which is fine between
            // friends and is "name your opening balance" on a public box.
            //
            // So the default follows the kind of host rather than a flag nobody sets.
            // Boxed so that unset and explicitly-false are different answers, which is
            // the whole mechanism and the part that would fail silently if it were a
            // plain boolean defaulting to false.
            ServerConfig inGame = new ServerConfig();
            check("somebody's own game takes them", inGame.acceptsMigration() ? 1 : 0, 1);

            ServerConfig box = new ServerConfig();
            box.dedicated = true;
            check("a dedicated server does not", box.acceptsMigration() ? 1 : 0, 0);

            // Both overrides have to work, or the default is a rule rather than a default.
            box.acceptsMigration = Boolean.TRUE;
            check("an operator can turn them on", box.acceptsMigration() ? 1 : 0, 1);

            inGame.acceptsMigration = Boolean.FALSE;
            check("and off", inGame.acceptsMigration() ? 1 : 0, 0);

            // Unset is not false. If this ever reads as false, every in-game host stops
            // accepting migrations and the failure looks like a network fault.
            box.acceptsMigration = null;
            check("clearing it goes back to the host's own default",
                    box.acceptsMigration() ? 1 : 0, 0);
            inGame.acceptsMigration = null;
            check("in both directions", inGame.acceptsMigration() ? 1 : 0, 1);

            // Survives the round trip, since --write-config rewrites the whole file and
            // a dropped field would silently re-enable migrations on a box that had
            // turned them off.
            check("an explicit false is still valid config",
                    new ServerConfig() {{ dedicated = true; acceptsMigration = false; }}
                            .problem() == null ? 1 : 0, 1);
        }

        section("M5: a fast-forward is distinguishable from a fork");
        {
            // The test the host cannot perform for itself: given only "you are ahead of
            // me", is the client a strict extension or a divergent branch? The client
            // answers it by checking its own hash at the host's head.
            Path shared = scratch("test-log-m5-shared.jsonl");
            Path forked = scratch("test-log-m5-fork.jsonl");
            Files.deleteIfExists(shared);
            Files.deleteIfExists(forked);

            EventLog log = new EventLog(shared);
            MarketState live = new MarketState();
            seedMarket(log, live);
            apply(log, live, deposit(ALICE, IRON, 100));

            long sharedHead = log.lastSeq();
            String sharedHash = log.lastHash();

            // Branch one: keep extending the same log.
            apply(log, live, deposit(ALICE, DIAMOND, 5));
            check("extension is ahead", log.lastSeq() > sharedHead ? 1 : 0, 1);
            check("extension still contains the host's head",
                    sharedHash.equals(log.hashAt(sharedHead)) ? 1 : 0, 1);

            // Branch two: a different continuation from the same point.
            Files.copy(shared, forked);
            List<String> trimmed = Files.readAllLines(forked)
                    .subList(0, (int) sharedHead);
            Files.write(forked, trimmed);
            EventLog other = new EventLog(forked);
            MarketState otherLive = EventApplier.replay(other);
            Event.Deposit d = deposit(ALICE, WOOD, 7);
            d.marketId = otherLive.marketId();
            other.append(d, testKeys().sign(EventCanonical.canonicalPayload(d)));

            check("divergent branch is also 'ahead' of that head",
                    other.lastSeq() > sharedHead ? 1 : 0, 1);
            check("but its head is still the shared one at that seq",
                    sharedHash.equals(other.hashAt(sharedHead)) ? 1 : 0, 1);
            check("and the two heads differ",
                    other.lastHash().equals(log.lastHash()) ? 1 : 0, 0);
        }

        section("L2: the founder is granted like everyone else");
        {
            Path file = scratch("test-log-l2.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(log, live);

            check("creator recorded", live.creator().equals(ALICE) ? 1 : 0, 1);
            check("creator registered", live.isRegistered(ALICE) ? 1 : 0, 1);
            // The grant itself is issued by HostServer.start(); what matters here is
            // that nothing in the event rules prevents it.
            check("creator not yet granted", live.hasBeenGranted(ALICE) ? 1 : 0, 0);

            Event.WelcomeGrant wg = new Event.WelcomeGrant();
            wg.userId = ALICE; wg.targetUserId = ALICE; wg.amount = 1000;
            wg.marketId = live.marketId();
            SequencedEvent se = new SequencedEvent();
            se.seq = log.lastSeq() + 1;
            se.event = wg;
            check("founder's grant is valid",
                    EventApplier.validate(live, se).accepted ? 1 : 0, 1);
        }

        section("K4: a sound archive verifies and imports");
        {
            Path src = scratch("test-archive-k4.jsonl");
            Path dest = scratch("test-import-k4.jsonl");
            Files.deleteIfExists(src);
            Files.deleteIfExists(dest);

            EventLog log = new EventLog(src);
            MarketState live = new MarketState();
            Event.MarketCreated mc = seedMarket(log, live);
            register(log, live, BOB);
            apply(log, live, deposit(ALICE, IRON, 100));

            MarketArchive.Summary s = MarketArchive.verify(src);
            check("verified event count", s.events, log.lastSeq());
            check("verified market id", s.marketId.equals(mc.marketId) ? 1 : 0, 1);
            check("verified participants", s.participants, 2);

            MarketArchive.importInto(src, dest);
            check("imported log replays", EventApplier.replay(new EventLog(dest))
                    .itemBalances().getBalance(ALICE, IRON), 100);
        }

        section("K5: a forged archive is refused — the whole point of import");
        {
            // A doctored log with the chain recomputed. This passes every hash check;
            // only the signature catches it. If this test ever goes green-by-accident,
            // import is worthless.
            Path src = scratch("test-archive-k5.jsonl");
            Files.deleteIfExists(src);
            EventLog log = new EventLog(src);
            MarketState live = new MarketState();
            seedMarket(log, live);
            apply(log, live, deposit(ALICE, IRON, 100));

            List<String> lines = Files.readAllLines(src);
            int at = lineOf(lines, "Deposit");
            String tampered = lines.get(at).replace("\"quantity\":100", "\"quantity\":999999");
            SequencedEvent se = EventLog.parseLine(tampered);
            // Re-chain it so nothing but the signature is wrong.
            String rehashed = tampered.replace("\"hash\":\"" + se.hash + "\"",
                    "\"hash\":\"" + EventLog.recomputeHash(se) + "\"");
            lines.set(at, rehashed);
            Files.write(src, lines);

            check("chain looks intact to a hash-only check",
                    new EventLog(src).verifyChain(), -1);

            int refused = 0;
            String why = "";
            try {
                MarketArchive.verify(src);
            } catch (MarketArchive.InvalidArchive e) {
                refused = 1;
                why = e.getMessage();
            }
            check("forged archive refused", refused, 1);
            check("refused for the right reason",
                    why.contains("signature") ? 1 : 0, 1);
        }

        section("K5b: an archive whose events break its own market's rules is refused");
        {
            // K5 catches a forged event — one somebody else's key signed. This catches
            // an event the author really did sign, in a market they really do own, that
            // no honest host would ever have sequenced.
            //
            // verifyLines called EventApplier.apply and nothing else, and apply enforces
            // none of the money rules: they live in validate, because validate is where
            // the host asks them before it appends. So a history built by hand could
            // hold a welcome grant for any sum, repeated as often as you like, and every
            // one of them applied. That balance is what a migration carries in, and
            // migrationObjection weighs the items a migrant brings against their own
            // statistics but never their credits — so nothing downstream caught it
            // either. This is the only gate on that path.
            Path src = scratch("test-archive-k5b.jsonl");
            Files.deleteIfExists(src);
            EventLog log = new EventLog(src);
            MarketState live = new MarketState();
            seedMarket(log, live);

            check("the market publishes its own figure", live.welcomeGrant(),
                    ServerConfig.DEFAULT_WELCOME_GRANT);

            // Signed by the market's own creator, chained correctly, and for a sum the
            // market's published policy does not offer.
            Event.WelcomeGrant wg = new Event.WelcomeGrant();
            wg.userId = ALICE;
            wg.targetUserId = ALICE;
            wg.amount = 999_999_999L;
            check("apply on its own takes it", apply(log, live, wg).accepted ? 1 : 0, 1);
            check("which is the balance a migration would have carried",
                    live.wallets().getBalance(ALICE), 999_999_999L);

            int refused = 0;
            String why = "";
            try {
                MarketArchive.verify(src);
            } catch (MarketArchive.InvalidArchive e) {
                refused = 1;
                why = e.getMessage();
            }
            check("the archive is refused", refused, 1);
            check("and says which rule it broke",
                    why.contains("grant must be exactly") ? 1 : 0, 1);
        }

        section("K5c: and an honest archive still verifies");
        {
            // The risk in K5b's fix is refusing too much: every event in an honest log
            // was validated by whoever sequenced it, so re-asking must be silent. A log
            // with a grant, a deposit, an order and a fill in it, taken end to end.
            Path src = scratch("test-archive-k5c.jsonl");
            Files.deleteIfExists(src);
            EventLog log = new EventLog(src);
            MarketState live = new MarketState();
            seedMarket(log, live);
            register(log, live, BOB);
            grant(log, live, BOB, live.welcomeGrant());
            apply(log, live, deposit(ALICE, IRON, 100));
            apply(log, live, placeOrder(ALICE, IRON, 5, 10, false));
            apply(log, live, placeOrder(BOB, IRON, 5, 10, true));

            check("the trade really happened", live.fillsEver(), 1);

            MarketArchive.Summary s = MarketArchive.verify(src);
            check("and the archive verifies", s.events, log.lastSeq());
        }

        section("K6: import refuses to overwrite existing history");
        {
            Path src = scratch("test-archive-k6.jsonl");
            Path dest = scratch("test-import-k6.jsonl");
            Files.deleteIfExists(src);
            Files.deleteIfExists(dest);

            EventLog a = new EventLog(src);
            MarketState liveA = new MarketState();
            seedMarket(a, liveA);

            EventLog b = new EventLog(dest);
            MarketState liveB = new MarketState();
            seedMarket(b, liveB);

            int refused = 0;
            try {
                MarketArchive.importInto(src, dest);
            } catch (MarketArchive.InvalidArchive e) {
                refused = 1;
            }
            check("import into a non-empty log refused", refused, 1);
        }

        section("J5: a second market cannot be created in an existing log");
        {
            Path file = scratch("test-log-j5.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(log, live);

            int refused = 0;
            try {
                MarketBootstrap.createMarket(log, BOB, "hostile takeover", testKeys());
            } catch (IOException expected) {
                refused = 1;
            }
            check("second createMarket refused", refused, 1);
        }

        System.out.println("\nGROUP N — trade history");

        section("N1: a crossing order records a trade, a resting one does not");
        {
            Path file = scratch("test-log-n1.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(log, live);
            register(log, live, BOB);

            apply(log, live, deposit(ALICE, IRON, 100));
            grant(log, live, BOB, 1000);

            // Rests — nothing has traded yet.
            apply(log, live, placeOrder(ALICE, IRON, 10, 60, false));
            check("no trade from a resting order", live.trades().countFor(IRON), 0);
            check("no last price yet", live.trades().lastPrice(IRON), -1);

            // Crosses.
            apply(log, live, placeOrder(BOB, IRON, 10, 60, true));
            check("one trade recorded", live.trades().countFor(IRON), 1);
            check("recorded at the resting price", live.trades().lastPrice(IRON), 10);

            Trade t = live.trades().recentFor(IRON).get(0);
            check("trade quantity", t.quantity, 60);
            check("trade amount", t.amount(), 600);
            check("buyer is the aggressor", t.buyerId.equals(BOB) ? 1 : 0, 1);
            check("seller is the rester", t.sellerId.equals(ALICE) ? 1 : 0, 1);
            check("trade carries the event's seq", t.seq, log.lastSeq());
        }

        section("N2: history survives replay identically");
        {
            Path file = scratch("test-log-n2.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(log, live);
            register(log, live, BOB);

            apply(log, live, deposit(ALICE, IRON, 100));
            grant(log, live, BOB, 10_000);
            apply(log, live, placeOrder(ALICE, IRON, 8, 30, false));
            apply(log, live, placeOrder(ALICE, IRON, 9, 30, false));
            apply(log, live, placeOrder(BOB, IRON, 10, 60, true));

            check("two fills recorded live", live.trades().countFor(IRON), 2);

            // The whole point of deriving it rather than storing it: a replica that
            // only ever saw the log must arrive at the same history.
            MarketState replayed = EventApplier.replay(log);
            check("replay agrees on count", replayed.trades().countFor(IRON), 2);
            check("replay agrees on last price",
                    replayed.trades().lastPrice(IRON), live.trades().lastPrice(IRON));
            check("replay agrees on first price",
                    replayed.trades().recentFor(IRON).get(0).price,
                    live.trades().recentFor(IRON).get(0).price);
            check("walking the book fills cheapest first",
                    live.trades().recentFor(IRON).get(0).price, 8);
        }

        section("N3: history is bounded and per-item");
        {
            Path file = scratch("test-log-n3.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(log, live);
            register(log, live, BOB);

            apply(log, live, deposit(ALICE, IRON, 5));
            apply(log, live, deposit(ALICE, WOOD, 5));
            grant(log, live, BOB, 10_000);

            apply(log, live, placeOrder(ALICE, IRON, 3, 5, false));
            apply(log, live, placeOrder(BOB, IRON, 3, 5, true));

            check("iron traded", live.trades().countFor(IRON), 1);
            check("wood did not", live.trades().countFor(WOOD), 0);
            check("unknown item is empty, not null",
                    live.trades().recentFor(DIAMOND).size(), 0);
            check("only traded items listed", live.trades().tradedItems().size(), 1);

            // The cap is what stops a long-lived market growing this forever.
            TradeHistory bounded = new TradeHistory();
            Fill f = new Fill(BOB, ALICE, 1, 1, IRON);
            for (int i = 0; i < TradeHistory.MAX_PER_ITEM + 50; i++) {
                bounded.record(new Trade(i, i, f));
            }
            check("history capped", bounded.countFor(IRON), TradeHistory.MAX_PER_ITEM);
            check("the cap drops the oldest, not the newest",
                    bounded.recentFor(IRON).get(TradeHistory.MAX_PER_ITEM - 1).seq,
                    TradeHistory.MAX_PER_ITEM + 49);
        }

        System.out.println("\nGROUP O — pending inventory operations");

        section("O1: entries survive being written and re-read");
        {
            Path file = scratch("test-pending-o1.json");
            Files.deleteIfExists(file);

            PendingOps ops = new PendingOps(file);
            check("starts empty", ops.isEmpty() ? 1 : 0, 1);

            ops.recordDeposit(ALICE, "evt-1", IRON, 10);
            ops.recordWithdraw(ALICE, 42, DIAMOND, 3);
            check("two recorded", ops.size(), 2);

            // The whole point is surviving a crash, so re-open rather than reuse.
            PendingOps reloaded = new PendingOps(file);
            check("both survived a reload", reloaded.size(), 2);

            PendingOps.Op dep = null, wd = null;
            for (PendingOps.Op op : reloaded.all()) {
                if (op.isDeposit()) dep = op;
                if (op.isWithdraw()) wd = op;
            }
            check("deposit survived", dep == null ? 0 : 1, 1);
            check("withdraw survived", wd == null ? 0 : 1, 1);
            check("deposit kept its event id",
                    dep != null && "evt-1".equals(dep.clientEventId) ? 1 : 0, 1);
            check("deposit kept its quantity", dep == null ? -1 : dep.quantity, 10);
            check("withdraw kept its seq", wd == null ? -1 : wd.seq, 42);
            check("withdraw kept its item",
                    wd != null && DIAMOND.equals(wd.itemId) ? 1 : 0, 1);
        }

        section("O2: clearing removes only the entry named");
        {
            Path file = scratch("test-pending-o2.json");
            Files.deleteIfExists(file);

            PendingOps ops = new PendingOps(file);
            ops.recordDeposit(ALICE, "evt-a", IRON, 1);
            ops.recordDeposit(BOB, "evt-b", WOOD, 2);
            ops.recordWithdraw(ALICE, 7, DIAMOND, 3);
            ops.recordWithdraw(BOB, 8, IRON, 4);

            ops.clearDeposit("evt-a");
            check("one deposit gone", ops.size(), 3);

            ops.clearWithdraw(7);
            check("one withdraw gone", ops.size(), 2);

            ops.clearDeposit("never-existed");
            ops.clearWithdraw(999);
            check("clearing an absent entry is harmless", ops.size(), 2);

            check("survivors persisted", new PendingOps(file).size(), 2);

            // A deposit id must not be matched by the withdraw clear, or vice versa.
            ops.clearWithdraw(8);
            check("the remaining deposit is untouched", ops.size(), 1);
            check("and it is the right one",
                    "evt-b".equals(ops.all().get(0).clientEventId) ? 1 : 0, 1);
        }

        section("O3: a damaged journal doesn't stop the world loading");
        {
            Path file = scratch("test-pending-o3.json");
            Files.deleteIfExists(file);
            Files.write(file, "{ this is not the json you are looking for".getBytes());

            // Losing the record leaves us where we were before it existed; refusing to
            // load the world would be a far worse trade.
            PendingOps ops = new PendingOps(file);
            check("damaged journal reads as empty", ops.isEmpty() ? 1 : 0, 1);

            ops.recordDeposit(ALICE, "evt-x", IRON, 5);
            check("and is still usable afterwards", new PendingOps(file).size(), 1);
        }

        section("N4: reading a book doesn't create one");
        {
            MarketState m = new MarketState();
            m.deposit(ALICE, IRON, 10);

            // A player can hold an item they have never listed. Listing every such item
            // is exactly what the overview does, and it must not leave a trail of empty
            // books behind it.
            check("no book before anything is listed", m.hasBook(IRON) ? 1 : 0, 0);
            check("peek returns nothing", m.peekBook(IRON) == null ? 1 : 0, 1);
            check("peeking created nothing", m.activeItems().size(), 0);

            m.submitOrder(new Order(1, 5, IRON, 10, false, ALICE));
            check("listing creates the book", m.hasBook(IRON) ? 1 : 0, 1);
            check("peek now finds it", m.peekBook(IRON) == null ? 0 : 1, 1);
            check("and it is the same book",
                    m.peekBook(IRON) == m.bookFor(IRON) ? 1 : 0, 1);

            // The old accessor still creates, because placing an order needs it to.
            m.bookFor(DIAMOND);
            check("bookFor still creates on demand", m.activeItems().size(), 2);
        }

        System.out.println("\nGROUP Q — persisted settings");

        section("Q1: settings survive being written and re-read");
        {
            Path file = scratch("test-settings-q1.json");
            Files.deleteIfExists(file);

            Settings s = new Settings(file);
            check("default port", s.hostPort(), 25555);
            check("chat notifications default on", s.notifyChat() ? 1 : 0, 1);
            check("action bar defaults off", s.notifyActionBar() ? 1 : 0, 0);

            s.setHostPort(25600);
            s.setLastHostAddress("example.net:25600");
            s.setLastItem(DIAMOND);
            s.setNotifyActionBar(true);
            s.setNotifyMaxPerMinute(5);

            Settings reloaded = new Settings(file);
            check("port survived", reloaded.hostPort(), 25600);
            check("address survived",
                    "example.net:25600".equals(reloaded.lastHostAddress()) ? 1 : 0, 1);
            check("item survived", DIAMOND.equals(reloaded.lastItem()) ? 1 : 0, 1);
            check("action bar survived", reloaded.notifyActionBar() ? 1 : 0, 1);
            check("rate limit survived", reloaded.notifyMaxPerMinute(), 5);
        }

        section("Q2: nonsense values are refused, not stored");
        {
            Path file = scratch("test-settings-q2.json");
            Files.deleteIfExists(file);

            Settings s = new Settings(file);
            s.setHostPort(80);          // privileged
            check("privileged port refused", s.hostPort(), 25555);
            s.setHostPort(70000);       // out of range
            check("out-of-range port refused", s.hostPort(), 25555);
            s.setHostPort(25601);
            check("valid port accepted", s.hostPort(), 25601);

            s.setNotifyMaxPerMinute(-1);
            check("negative rate limit refused", s.notifyMaxPerMinute(), 20);
            s.setNotifyMaxPerMinute(0);
            check("zero is allowed — it means always batch", s.notifyMaxPerMinute(), 0);
        }

        section("Q3: a damaged or partial file still loads");
        {
            Path broken = scratch("test-settings-q3a.json");
            Files.deleteIfExists(broken);
            Files.write(broken, "not json at all".getBytes());
            Settings s = new Settings(broken);
            check("damaged file falls back to defaults", s.hostPort(), 25555);

            // A file written by an older build won't have every field. Gson leaves the
            // missing ones at their initialiser, which is why Record defaults them.
            Path partial = scratch("test-settings-q3b.json");
            Files.deleteIfExists(partial);
            Files.write(partial, "{\"hostPort\":25700}".getBytes());
            Settings p = new Settings(partial);
            check("known field read", p.hostPort(), 25700);
            check("absent field keeps its default", p.notifyMaxPerMinute(), 20);
            check("absent string keeps its default",
                    "minecraft:iron_ingot".equals(p.lastItem()) ? 1 : 0, 1);
        }

        section("R1: server config round-trips");
        {
            Path f = scratch("test-serverconfig-r1.json");
            Files.deleteIfExists(f);

            ServerConfig cfg = ServerConfig.friendGroup(25610);
            cfg.hostName = "dedicated-one";
            cfg.welcomeGrant = 250;
            cfg.maxConnections = 8;
            cfg.bindAddress = "127.0.0.1";
            cfg.creatorUserId = ALICE.toString();
            cfg.save(f);

            ServerConfig back = ServerConfig.load(f);
            check("port survives", back.port, 25610);
            check("grant survives", back.welcomeGrant, 250);
            check("connection cap survives", back.maxConnections, 8);
            check("bind address survives",
                    "127.0.0.1".equals(back.bindAddress) ? 1 : 0, 1);
            check("creator survives",
                    ALICE.toString().equals(back.creatorUserId) ? 1 : 0, 1);

            // A setting that is null in the object is omitted by Gson, so it never
            // reaches the file and an operator has no way to learn it exists. That is
            // how acceptsMigration shipped: in the code, in the checklist, and in
            // nobody's config. save() writes the resolved answer for exactly this.
            String written = new String(Files.readAllBytes(f), "UTF-8");
            check("an unset default still reaches the file",
                    written.contains("acceptsMigration") ? 1 : 0, 1);
            check("as the answer it resolves to, not as null",
                    written.contains("\"acceptsMigration\": true") ? 1 : 0, 1);
            check("and reads back as an explicit value",
                    Boolean.TRUE.equals(back.acceptsMigration) ? 1 : 0, 1);
            check("which still resolves the same way",
                    back.acceptsMigration() ? 1 : 0, 1);

            // The other side of it: a dedicated server's file has to say false, or the
            // operator reads the friend-group answer and believes it.
            Path g = scratch("test-serverconfig-r1-dedicated.json");
            Files.deleteIfExists(g);
            ServerConfig box = ServerConfig.friendGroup(25611);
            box.dedicated = true;
            box.save(g);
            check("a dedicated server writes the dedicated answer",
                    new String(Files.readAllBytes(g), "UTF-8")
                            .contains("\"acceptsMigration\": false") ? 1 : 0, 1);
            check("and it survives the round trip",
                    ServerConfig.load(g).acceptsMigration() ? 1 : 0, 0);
        }

        section("R2: a server nobody could use is refused, not clamped");
        {
            // Named rather than corrected: a port silently changed out from under an
            // operator is worse than a refusal that says which field is wrong.
            ServerConfig bad = ServerConfig.friendGroup(99999);
            check("out-of-range port refused", bad.problem() != null ? 1 : 0, 1);

            ServerConfig noRoom = ServerConfig.friendGroup(25555);
            noRoom.maxConnections = 0;
            check("zero connections refused", noRoom.problem() != null ? 1 : 0, 1);

            ServerConfig negative = ServerConfig.friendGroup(25555);
            negative.welcomeGrant = -1;
            check("negative grant refused", negative.problem() != null ? 1 : 0, 1);

            check("a sane config is accepted",
                    ServerConfig.friendGroup(25555).problem() == null ? 1 : 0, 1);
        }

        section("R3: no config is defaults, an unreadable one is a refusal");
        {
            // The two are deliberately not the same. No file means no policy has been
            // expressed. A file that exists and cannot be parsed means policy WAS
            // expressed and cannot be seen — and defaulting there would quietly restore
            // welcomeGrant to 1000, minting money into a log that is never rewritten.
            Path none = scratch("test-serverconfig-none.json");
            Files.deleteIfExists(none);
            ServerConfig absent = ServerConfig.load(none);
            check("absent file gives defaults", absent.port, 25555);
            check("and the default grant", absent.welcomeGrant,
                    ServerConfig.DEFAULT_WELCOME_GRANT);

            Path junk = scratch("test-serverconfig-r3.json");
            Files.deleteIfExists(junk);
            Files.write(junk, "not json at all".getBytes());
            boolean refused = false;
            try {
                ServerConfig.load(junk);
            } catch (IOException e) {
                refused = true;
            }
            check("damaged file is refused, not defaulted", refused ? 1 : 0, 1);

            Path empty = scratch("test-serverconfig-r3c.json");
            Files.deleteIfExists(empty);
            Files.write(empty, "   ".getBytes());
            boolean emptyRefused = false;
            try {
                ServerConfig.load(empty);
            } catch (IOException e) {
                emptyRefused = true;
            }
            check("an empty file is refused too", emptyRefused ? 1 : 0, 1);

            // A file written by an older build is still valid JSON and still policy —
            // only the fields it never had fall back.
            Path partial = scratch("test-serverconfig-r3b.json");
            Files.deleteIfExists(partial);
            Files.write(partial, "{\"port\":25611}".getBytes());
            ServerConfig p = ServerConfig.load(partial);
            check("known field read", p.port, 25611);
            check("absent field keeps its default", p.maxConnections, 64);
        }

        section("S1: an open server admits anyone");
        {
            ServerConfig open = ServerConfig.friendGroup(25555);
            check("a stranger is admitted", open.refuses(ALICE.toString()) == null ? 1 : 0, 1);
            check("but not a missing identity", open.refuses(null) != null ? 1 : 0, 1);
            check("nor an empty one", open.refuses("  ") != null ? 1 : 0, 1);
        }

        section("S2: an allowlist admits only who is on it");
        {
            ServerConfig cfg = ServerConfig.friendGroup(25555);
            cfg.admission = ServerConfig.ALLOWLIST;
            cfg.allow.add(ALICE.toString());

            check("a listed identity is admitted",
                    cfg.refuses(ALICE.toString()) == null ? 1 : 0, 1);
            check("an unlisted one is not",
                    cfg.refuses(BOB.toString()) != null ? 1 : 0, 1);

            // A UUID reaches the config by being typed or pasted, so case is not a
            // meaningful difference and must not decide who gets in.
            check("case does not decide admission",
                    cfg.refuses(ALICE.toString().toUpperCase()) == null ? 1 : 0, 1);
            check("nor does stray whitespace",
                    cfg.refuses("  " + ALICE.toString() + " ") == null ? 1 : 0, 1);
        }

        section("S3: deny beats allow");
        {
            // An identity on both lists is one somebody is arguing about. Refusing is
            // the reading that can be undone.
            ServerConfig cfg = ServerConfig.friendGroup(25555);
            cfg.admission = ServerConfig.ALLOWLIST;
            cfg.allow.add(ALICE.toString());
            cfg.deny.add(ALICE.toString());
            check("denied even though allowed",
                    cfg.refuses(ALICE.toString()) != null ? 1 : 0, 1);

            ServerConfig openCfg = ServerConfig.friendGroup(25555);
            openCfg.deny.add(BOB.toString());
            check("deny applies on an open server too",
                    openCfg.refuses(BOB.toString()) != null ? 1 : 0, 1);
            check("and leaves everyone else alone",
                    openCfg.refuses(ALICE.toString()) == null ? 1 : 0, 1);
        }

        section("S4: an admission policy that locks everyone out is refused");
        {
            // Including the operator. This is a config that cannot be what was meant,
            // and finding out by being unable to connect is a bad way to learn it.
            ServerConfig empty = ServerConfig.friendGroup(25555);
            empty.admission = ServerConfig.ALLOWLIST;
            check("allowlist with nobody on it is refused",
                    empty.problem() != null ? 1 : 0, 1);

            ServerConfig typo = ServerConfig.friendGroup(25555);
            typo.admission = "allow-list";
            check("a misspelt mode does not silently mean open",
                    typo.problem() != null ? 1 : 0, 1);

            ServerConfig fine = ServerConfig.friendGroup(25555);
            fine.admission = ServerConfig.ALLOWLIST;
            fine.allow.add(ALICE.toString());
            check("a usable allowlist is accepted", fine.problem() == null ? 1 : 0, 1);
        }

        section("T1: the rounding rule, pinned");
        {
            // Every replica computes this independently and must agree to the credit.
            check("1% of 1000", MarketState.taxOn(1000, 100), 10);
            check("2.5% of 1000", MarketState.taxOn(1000, 250), 25);
            check("50% of 1000", MarketState.taxOn(1000, MarketState.MAX_TAX_BPS), 500);
            check("no rate, no tax", MarketState.taxOn(1000, 0), 0);
            check("a negative rate cannot pay anyone", MarketState.taxOn(1000, -100), 0);

            // Rounds down, so small trades are taxed nothing rather than
            // disproportionately. 1% of 99 is 0.99.
            check("rounds down, not up", MarketState.taxOn(99, 100), 0);
            check("and at the boundary", MarketState.taxOn(100, 100), 1);
            check("199 at 1%", MarketState.taxOn(199, 100), 1);

            check("nothing is taxed on nothing", MarketState.taxOn(0, 500), 0);
            check("the tax never exceeds the trade",
                    MarketState.taxOn(1, MarketState.MAX_TAX_BPS), 0);

            // Large but realistic: 100k units at 100k each would overflow a naive
            // int multiply long before this.
            check("large fills do not overflow",
                    MarketState.taxOn(1_000_000_000L, 100), 10_000_000L);
        }

        section("T2b: filling your own order is not free once there is a fee");
        {
            // Both sides are the same wallet, so the price washes out — but the fee is
            // taken from the seller's proceeds and burned, and that side is you too.
            // The cost is exactly the fee, it is silent, and it repeats every time your
            // own ask undercuts your own bid, which is the ordinary shape of a book
            // somebody is making on both sides.
            MarketState m = new MarketState();
            m.deposit(ALICE, IRON, 10);
            m.wallets().setBalance(ALICE, 1000L);
            m.setTaxBps(1000);              // 10%

            long before = m.wallets().getBalance(ALICE);

            m.submitOrder(new Order(1, 50, IRON, 10, false, ALICE));   // ask 10 @ 50
            m.submitOrder(new Order(2, 50, IRON, 10, true, ALICE));    // and buy it back

            // 10 at 50 = 500, 10% of which is 50.
            check("a self-trade costs exactly the fee", before - m.wallets().getBalance(ALICE), 50);
            check("and the goods come straight back",
                    m.itemBalances().getBalance(ALICE, IRON), 10);

            // The premise the notifier relies on: with no fee there is genuinely
            // nothing to report, which is why the quiet case stays quiet.
            MarketState free = new MarketState();
            free.deposit(BOB, IRON, 10);
            free.wallets().setBalance(BOB, 1000L);
            long untaxed = free.wallets().getBalance(BOB);
            free.submitOrder(new Order(1, 50, IRON, 10, false, BOB));
            free.submitOrder(new Order(2, 50, IRON, 10, true, BOB));
            check("with no fee it really does net to nothing",
                    free.wallets().getBalance(BOB), untaxed);
        }

        section("T1e: the listing fee climbs with orders held, not with order value");
        {
            MarketState m = new MarketState();
            m.setMarketIdentity(UUID.randomUUID(), "escalating market", ALICE);
            m.registerKey(ALICE, "alice-key");
            m.wallets().setBalance(ALICE, 10_000L);
            m.deposit(ALICE, IRON, 100);
            m.setListingFee(2);
            m.setListingFreeOrders(3);

            // listingFeeFor prices the order about to be placed, counting it towards
            // the allowance — so read with n resting, it says what the (n+1)th costs.
            // This block used to read as though it said what the nth had cost, and the
            // two readings differ by exactly one order: an allowance of three was
            // letting four orders through at the base fee. The prose here said "the
            // fourth starts climbing" while the assertion below it pinned the fourth at
            // the base fee, which is how it survived being written down.
            check("the first order pays the base fee", m.listingFeeFor(ALICE), 2);

            // Two resting: the third is still inside an allowance of three.
            for (int i = 0; i < 2; i++) {
                m.submitOrder(new Order(100 + i, 50 + i, IRON, 1, false, ALICE));
            }
            check("the last order inside the allowance pays the base fee",
                    m.listingFeeFor(ALICE), 2);

            // Three resting. The fourth is the one that takes them past three.
            m.submitOrder(new Order(102, 52, IRON, 1, false, ALICE));
            check("the fourth costs double", m.listingFeeFor(ALICE), 4);

            m.submitOrder(new Order(200, 90, IRON, 1, false, ALICE));
            check("and the fifth triple", m.listingFeeFor(ALICE), 6);

            // What was actually charged, not only what was quoted — the quote is no use
            // if submitOrder takes something else.
            long before = m.wallets().getBalance(ALICE);
            m.submitOrder(new Order(201, 91, IRON, 1, false, ALICE));
            check("and the fifth is what the fifth is charged",
                    before - m.wallets().getBalance(ALICE), 6);
            check("with five resting, a sixth would cost four times the base",
                    m.listingFeeFor(ALICE), 8);

            // Cancelling gives the allowance back — the fee prices what you are holding
            // open, so releasing the book releases the cost.
            m.cancelOrder(201, IRON, false, ALICE);
            check("cancelling walks it back down", m.listingFeeFor(ALICE), 6);

            // Never free, which is what the stipend's safety rests on.
            check("somebody with nothing resting still pays", m.listingFeeFor(BOB), 2);

            // Off by default, so markets written before this keep the flat fee.
            MarketState flat = new MarketState();
            flat.setMarketIdentity(UUID.randomUUID(), "flat market", ALICE);
            flat.registerKey(ALICE, "alice-key");
            flat.wallets().setBalance(ALICE, 10_000L);
            flat.deposit(ALICE, IRON, 100);
            flat.setListingFee(2);
            flat.submitOrder(new Order(300, 50, IRON, 1, false, ALICE));
            flat.submitOrder(new Order(301, 51, IRON, 1, false, ALICE));
            check("no allowance set means no escalation", flat.listingFeeFor(ALICE), 2);
        }

        section("U4: a stipend pays per fill, and cannot be earned by self-dealing");
        {
            // Money otherwise enters only when new people do, while goods accrue for
            // every hour anybody plays — so prices fall until they reach the integer
            // floor of 1 and stop meaning anything. This is the counterweight.
            MarketState m = new MarketState();
            m.setMarketIdentity(UUID.randomUUID(), "stipend market", ALICE);
            m.registerKey(ALICE, "alice-key");
            // A fee large enough that five trades collect more than the stipend
            // pays out — see U5 for the rule this has to satisfy.
            m.setListingFee(10);
            m.setStipend(10, 5);

            check("nothing owed on arrival", stipendRejection(m, ALICE, 10) != null ? 1 : 0, 1);

            // Four fills is not yet five.
            m.recordTrades(1, 1L, fillsOf(4));
            check("still nothing at four fills",
                    stipendRejection(m, ALICE, 10) != null ? 1 : 0, 1);

            m.recordTrades(2, 2L, fillsOf(1));
            check("payable at five", stipendRejection(m, ALICE, 10) == null ? 1 : 0, 1);

            // The amount is the market's, for the same reason the grant's is: nothing
            // can check who was sequencing.
            check("but only for the market's figure",
                    stipendRejection(m, ALICE, 11) != null ? 1 : 0, 1);

            // Claiming resets the interval rather than the balance being a one-off.
            Event.Stipend claim = new Event.Stipend();
            claim.userId = ALICE;
            claim.marketId = m.marketId();
            claim.amount = 10;
            SequencedEvent se = new SequencedEvent();
            se.seq = 3; se.event = claim;
            EventApplier.apply(m, se);
            check("and pays out", m.wallets().getBalance(ALICE), 10);
            check("then the interval starts again",
                    stipendRejection(m, ALICE, 10) != null ? 1 : 0, 1);

            // An identity registering later does not inherit other people's trading.
            m.registerKey(BOB, "bob-key");
            check("a newcomer waits their own interval, not the market's history",
                    stipendRejection(m, BOB, 10) != null ? 1 : 0, 1);

            // Off unless configured.
            MarketState none = new MarketState();
            none.setMarketIdentity(UUID.randomUUID(), "no stipend", ALICE);
            none.registerKey(ALICE, "alice-key");
            check("no policy, nothing to claim",
                    stipendRejection(none, ALICE, 10) != null ? 1 : 0, 1);
        }

        section("U4b: a market starts paying nothing, and can go back to it");
        {
            // Off is the starting state and has to stay reachable. A market whose
            // creator can turn a payment on but never off has been given a decision it
            // cannot take back, and the fees it depends on cannot then be lowered
            // either — the interlock would refuse that while a stipend was still set.
            Path fresh = scratch("test-stipend-default.jsonl");
            Files.deleteIfExists(fresh);
            EventLog log = new EventLog(fresh);
            MarketBootstrap.createMarket(log, ALICE, "plain market", testKeys());
            check("a market created the ordinary way pays nothing",
                    EventApplier.replay(log).stipendAmount(), 0);

            MarketState m = new MarketState();
            m.setMarketIdentity(UUID.randomUUID(), "switchable market", ALICE);
            m.registerKey(ALICE, "alice-key");
            m.setListingFee(2);
            m.setStipend(50, 50);

            // Turning it off is a policy like any other, and must not be caught by the
            // interlock — which only asks about a stipend that pays something.
            check("setting it to nothing is allowed",
                    policyStipendRejection(m, ALICE, 2, 0, 50) == null ? 1 : 0, 1);
            check("and allowed even with no fee to cover it",
                    policyStipendRejection(m, ALICE, 0, 0, 50) == null ? 1 : 0, 1);

            m.setStipend(0, 0);
            m.recordTrades(1, 1L, fillsOf(100));
            check("and then nothing is owed however much trades",
                    stipendRejection(m, ALICE, 50) != null ? 1 : 0, 1);
            // Said as "pays no stipend", not as an argument about the figure — a client
            // holding the old amount is exactly who asks this.
            check("and says so plainly",
                    stipendRejection(m, ALICE, 50).contains("pays no stipend") ? 1 : 0, 1);
        }

        section("X1: a reader never sees an event half-settled");
        {
            // The only check in this suite that runs two threads, because the thing it
            // is about cannot happen on one.
            //
            // EventApplier is the only writer, but the render thread reads the same
            // state every frame. Giving each collection its own monitor stops any single
            // read catching a map mid-write; it cannot make a set of reads agree with
            // each other, and settling one event touches several. submitOrder takes the
            // buyer's credits and *then* puts the order in the book, so between those
            // two steps the money has left the wallet and is in no reservation — a
            // reader landing there sees a market with credits simply missing.
            //
            // So apply holds one write lock across the whole event, and this holds the
            // matching read lock and checks the books balance. With no tax and no
            // listing fee, credits are conserved exactly: what is in wallets plus what
            // is reserved in resting bids never changes, whatever is happening.
            MarketState m = new MarketState();
            m.setMarketIdentity(UUID.randomUUID(), "concurrent market", ALICE);
            m.registerKey(ALICE, "alice-key");
            m.registerKey(BOB, "bob-key");
            m.wallets().setBalance(ALICE, 100_000L);
            m.wallets().setBalance(BOB, 100_000L);

            final long TOTAL = 200_000L;
            final java.util.concurrent.atomic.AtomicReference<String> fault =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.atomic.AtomicBoolean stop =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicLong reads =
                    new java.util.concurrent.atomic.AtomicLong();

            Thread reader = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        long seen;
                        m.readLock().lock();
                        try {
                            seen = m.wallets().getBalance(ALICE)
                                    + m.wallets().getBalance(BOB);
                            // Walked the way the render thread walks it: every book,
                            // including ones being created underneath us.
                            for (String itemId : m.activeItems()) {
                                OrderBook b = m.peekBook(itemId);
                                if (b == null) continue;
                                for (Order o : b.restingBids()) {
                                    seen += o.volume() * o.value();
                                }
                            }
                        } finally {
                            m.readLock().unlock();
                        }
                        reads.incrementAndGet();
                        if (seen != TOTAL) {
                            fault.compareAndSet(null, "credits came to " + seen
                                    + ", not " + TOTAL);
                            return;
                        }
                    }
                } catch (Throwable t) {
                    // A ConcurrentModificationException out of the walk lands here, and
                    // is the other half of what this is for.
                    fault.compareAndSet(null, t.getClass().getSimpleName() + ": "
                            + t.getMessage());
                }
            }, "market-reader");
            reader.setDaemon(true);
            reader.start();

            // A fresh item every round, so books are being created the whole time the
            // reader is walking them.
            long seq = 10;
            int rounds = 0;
            long deadline = System.currentTimeMillis() + 1500;
            while (rounds < 600 && fault.get() == null
                    && System.currentTimeMillis() < deadline) {
                String item = "test:item_" + rounds;

                Event.Deposit d = new Event.Deposit();
                d.userId = ALICE; d.marketId = m.marketId();
                d.itemId = item; d.quantity = 10;
                applyAt(m, ++seq, d);

                Event.PlaceOrder ask = placeOrder(ALICE, item, 5, 2, false);
                ask.marketId = m.marketId();
                applyAt(m, ++seq, ask);

                // Crosses immediately: reserve, match, pay, refund — the whole window.
                Event.PlaceOrder bid = placeOrder(BOB, item, 5, 2, true);
                bid.marketId = m.marketId();
                applyAt(m, ++seq, bid);

                // And one that rests, so the reserved half of the sum is never zero and
                // the reader is actually obliged to count it.
                if (rounds % 8 == 0) {
                    Event.PlaceOrder resting = placeOrder(BOB, item, 1, 3, true);
                    resting.marketId = m.marketId();
                    applyAt(m, ++seq, resting);
                }
                rounds++;
            }

            stop.set(true);
            reader.join(5000);

            check("the reader saw a consistent market throughout",
                    fault.get() == null ? 1 : 0, 1);
            if (fault.get() != null) System.out.println("      " + fault.get());
            check("and it actually looked", reads.get() > 0 ? 1 : 0, 1);
            check("the writer got through its rounds", rounds > 0 ? 1 : 0, 1);

            // Conserved at rest too, which says the invariant itself was the right one.
            long ended = m.wallets().getBalance(ALICE) + m.wallets().getBalance(BOB);
            for (String itemId : m.activeItems()) {
                OrderBook b = m.peekBook(itemId);
                if (b == null) continue;
                for (Order o : b.restingBids()) ended += o.volume() * o.value();
            }
            check("credits are conserved at the end", ended, TOTAL);
        }

        section("X2: settling an order re-enters the state's own lock");
        {
            // apply() holds the write lock for a whole event. Settling a PlaceOrder goes
            // submitOrder -> canSubmit -> listingFeeFor -> openOrderCount, and that last
            // one takes the *read* lock. A thread holding the write lock re-entering for
            // read is allowed by ReentrantReadWriteLock — but "allowed" was reasoning,
            // and a deadlock on the sequencer thread is not a thing to reason about.
            //
            // Nothing reached it before this. listingFeeFor returns early unless BOTH a
            // fee and an allowance are set, and every test that set an allowance called
            // submitOrder directly, so no test had ever held the write lock while asking
            // for the read one. The allowance is also a control that shipped days ago,
            // which is the combination worth being careful about: brand new, and on the
            // one path that would hang the host rather than fail it.
            MarketState m = new MarketState();
            UUID id = UUID.randomUUID();
            m.setMarketIdentity(id, "lock market", ALICE);
            m.registerKey(ALICE, "alice-key");
            m.wallets().setBalance(ALICE, 10_000L);
            m.deposit(ALICE, IRON, 100);
            m.setListingFee(2);
            m.setListingFreeOrders(3);

            final java.util.concurrent.atomic.AtomicInteger placed =
                    new java.util.concurrent.atomic.AtomicInteger();
            Thread applier = new Thread(() -> {
                for (int i = 0; i < 6; i++) {
                    Event.PlaceOrder p = placeOrder(ALICE, IRON, 100 + i, 1, false);
                    p.marketId = id;
                    if (!applyAt(m, 10 + i, p).accepted) return;
                    placed.incrementAndGet();
                }
            }, "market-applier");
            applier.setDaemon(true);
            applier.start();
            applier.join(10_000);

            // The assertion is that it finished at all. A hung applier leaves this at
            // fewer than six and the thread still alive, which is what a deadlock looks
            // like from outside.
            check("six orders settled without hanging", placed.get(), 6);
            check("and the thread is done", applier.isAlive() ? 1 : 0, 0);

            // And the arithmetic held while the locks were being taken and retaken:
            // three at the base fee, then double, triple, quadruple.
            check("charging the escalating fee throughout",
                    m.wallets().getBalance(ALICE), 10_000L - (2 + 2 + 2 + 4 + 6 + 8));
        }

        section("T1f: a listing nobody can pay for is refused before anything is deposited");
        {
            // The two halves of DepositAndList used to be checked in two places that did
            // not agree. validate looked at quantity, price and itemId; apply deposited
            // the goods and then asked submitOrder, which refuses a seller who cannot
            // pay the listing fee. So the event passed validate, went into the log, and
            // was refused after the deposit had landed — leaving the goods in the ledger
            // on an event whose author had just been told it failed, while the client
            // answered that refusal by handing the physical items back.
            //
            // Both now ask MarketState.canDepositAndList, which is the only copy.
            MarketState m = new MarketState();
            m.setMarketIdentity(UUID.randomUUID(), "fee market", ALICE);
            m.registerKey(ALICE, "alice-key");
            m.setListingFee(5);
            m.wallets().setBalance(ALICE, 3L);          // less than the fee

            Event.DepositAndList d = new Event.DepositAndList();
            d.userId = ALICE;
            d.marketId = m.marketId();
            d.itemId = IRON;
            d.quantity = 10;
            d.price = 7;
            SequencedEvent se = new SequencedEvent();
            se.seq = 2;
            se.event = d;

            EventApplier.Result v = EventApplier.validate(m, se);
            check("validate refuses it", v.accepted ? 1 : 0, 0);
            // valueOf, not v.reason directly: when this regresses, reason is null and a
            // suite that dies here reports one failure instead of the five below it.
            check("and names the fee it cannot pay",
                    String.valueOf(v.reason).contains("listing costs 5") ? 1 : 0, 1);

            // The half that actually cost items: apply must leave nothing behind.
            EventApplier.Result a = EventApplier.apply(m, se);
            check("apply refuses it too", a.accepted ? 1 : 0, 0);
            check("and no goods were deposited on the way to refusing",
                    m.itemBalances().getBalance(ALICE, IRON), 0);
            check("and no credits moved", m.wallets().getBalance(ALICE), 3);

            // The same event once they can afford it, so the refusal is about the fee
            // and not about deposit-and-list being broken.
            m.wallets().adjust(ALICE, 2);               // now exactly 5
            check("affordable, it is accepted",
                    EventApplier.validate(m, se).accepted ? 1 : 0, 1);
            check("and applies", EventApplier.apply(m, se).accepted ? 1 : 0, 1);
            check("the goods are listed, not sitting in the ledger",
                    m.itemBalances().getBalance(ALICE, IRON), 0);
            check("and the fee was taken", m.wallets().getBalance(ALICE), 0);
            check("with the order actually resting",
                    m.peekBook(IRON).restingAsks().size(), 1);
        }

        section("U8: one order fills a whole book, which is what the interlock costs");
        {
            // The measurement the corrected rule is built on, kept because the old rule
            // was wrong precisely by assuming otherwise. A fill was taken to cost two
            // listing fees, on the reasoning that two orders have to cross. They do not:
            // one order crossing a stacked book produces a fill per resting order it
            // consumes, and the fees for those were paid once, when they were placed.
            //
            // That halved the real cost, and the policy check was permitting stipends at
            // twice what the market could collect. One person could rest a book against
            // themselves, sweep it with a single order, and claim more than the fees had
            // cost — no confederate needed.
            MarketState m = new MarketState();
            m.setMarketIdentity(UUID.randomUUID(), "sweep market", ALICE);
            m.registerKey(ALICE, "alice-key");
            m.wallets().setBalance(ALICE, 100_000L);
            m.deposit(ALICE, IRON, 1000);
            m.setListingFee(2);

            long before = m.wallets().getBalance(ALICE);
            for (int i = 0; i < 20; i++) {
                m.submitOrder(new Order(i + 1, 10, IRON, 1, false, ALICE));
            }
            MarketState.SubmitResult swept =
                    m.submitOrder(new Order(999, 10, IRON, 20, true, ALICE));
            long spent = before - m.wallets().getBalance(ALICE);

            check("one order fills the whole book", swept.fills.size(), 20);
            // 21 orders at 2 credits. The trade itself nets to nothing — same wallet on
            // both sides, no tax set — so every credit lost is a listing fee.
            check("and 21 fees paid for 20 fills", spent, 42);
            check("so a fill costs about one fee, not two",
                    spent < 20 * 2 * 2 ? 1 : 0, 1);
        }

        section("U5: a stipend that outpays its own cost is refused as policy");
        {
            // The interlock. Producing a fill means two orders crossing, so at least two
            // listing fees at the base rate. A stipend worth more than that per fill is
            // a mint anybody can work by trading with themselves — and a market whose
            // policy is only safe when set carefully mints the first time somebody is
            // careless.
            MarketState m = new MarketState();
            m.setMarketIdentity(UUID.randomUUID(), "interlock market", ALICE);
            m.registerKey(ALICE, "alice-key");

            // A fill costs one listing fee, not two. One order crossing a stacked book
            // produces a fill per resting order it consumes, and it is the resting side
            // that already paid — so five fills collect five fees, not ten.
            check("a stipend its fees can cover is allowed",
                    policyStipendRejection(m, ALICE, 10, 10, 5) == null ? 1 : 0, 1);

            // 5 fills at a fee of 10 collect 50. Paying 50 is break-even, and
            // break-even is not safe: self-dealing at no loss is still an unbounded
            // supply of claims.
            check("break-even is refused",
                    policyStipendRejection(m, ALICE, 10, 50, 5) != null ? 1 : 0, 1);

            check("and paying more than it collects certainly is",
                    policyStipendRejection(m, ALICE, 10, 100, 5) != null ? 1 : 0, 1);

            // The old rule assumed two fees per fill and would have allowed this.
            check("what the doubled estimate used to permit is now refused",
                    policyStipendRejection(m, ALICE, 10, 60, 5) != null ? 1 : 0, 1);

            // A fee of zero makes fills free, so no stipend is safe at all.
            check("no listing fee means no stipend",
                    policyStipendRejection(m, ALICE, 0, 1, 1000) != null ? 1 : 0, 1);

            check("the refusal says how to fix it",
                    policyStipendRejection(m, ALICE, 10, 100, 5)
                            .contains("Raise the listing fee") ? 1 : 0, 1);

            // Everyone registered claims once per interval, so the payout multiplies by
            // however many people are here while the fees do not. This is what two
            // colluders were doing, and what ten would do without colluding at all.
            MarketState crowd = new MarketState();
            crowd.setMarketIdentity(UUID.randomUUID(), "crowded market", ALICE);
            crowd.registerKey(ALICE, "alice-key");
            check("affordable for one",
                    policyStipendRejection(crowd, ALICE, 10, 40, 5) == null ? 1 : 0, 1);
            crowd.registerKey(BOB, "bob-key");
            check("and not for two", 
                    policyStipendRejection(crowd, ALICE, 10, 40, 5) != null ? 1 : 0, 1);
            check("the refusal counts the heads it is paying",
                    policyStipendRejection(crowd, ALICE, 10, 40, 5)
                            .contains("2 registered") ? 1 : 0, 1);
        }

        section("U6: a policy event is the whole policy, and drops what it omits");
        {
            // Not a rule so much as a shape worth pinning, because it has now caught
            // two people. A MarketPolicy carries every field; anything the author does
            // not restate is set to zero by the event they write. It nearly wiped the
            // welcome grant once, and it silently wiped the stipend the moment those
            // fields were added and the client's fee controls were not updated.
            MarketState m = new MarketState();
            m.setMarketIdentity(UUID.randomUUID(), "whole policy market", ALICE);
            m.registerKey(ALICE, "alice-key");
            m.setListingFee(2);
            m.setStipend(10, 5);
            m.setListingFreeOrders(3);

            // A policy restating only the fee. Everything else goes to zero — this is
            // the event doing exactly what it is defined to do.
            Event.MarketPolicy partial = new Event.MarketPolicy();
            partial.userId = ALICE;
            partial.marketId = m.marketId();
            partial.listingFee = 5;
            partial.grantAmount = m.welcomeGrant();
            partial.timestamp = 1L;
            SequencedEvent se = new SequencedEvent();
            se.seq = 2; se.event = partial;
            EventApplier.apply(m, se);

            check("the field it set is set", m.listingFee(), 5);
            check("and the ones it omitted are gone", m.stipendAmount(), 0);
            check("all of them", m.listingFreeOrders(), 0);
        }

        section("U7: a server can open a market with a stipend, or be told why not");
        {
            // A dedicated server bootstrapping the ordinary way is its own creator, and
            // has no screen to set policy from afterwards — so whatever it cannot write
            // at genesis, that market can never have. That is why these live in the
            // config at all.
            ServerConfig ok = ServerConfig.friendGroup(25555);
            ok.listingFee = 2;
            ok.stipendAmount = 50;
            check("a stipend its fees can cover starts fine", ok.problem() == null ? 1 : 0, 1);

            // 50 fills at 2 a side pays 200; a stipend of 200 breaks even, which is not
            // safe — self-dealing at no loss is still an unbounded supply of claims.
            ServerConfig breakEven = ServerConfig.friendGroup(25555);
            breakEven.listingFee = 2;
            breakEven.stipendAmount = 200;
            check("break-even is refused", breakEven.problem() != null ? 1 : 0, 1);

            ServerConfig noFee = ServerConfig.friendGroup(25555);
            noFee.stipendAmount = 10;
            check("a stipend with no listing fee is refused",
                    noFee.problem() != null ? 1 : 0, 1);
            check("and the refusal says a listing fee is what is missing",
                    noFee.problem().contains("listing fee") ? 1 : 0, 1);

            // Refused at startup rather than written into genesis, because a policy every
            // replica rejects would leave the market unusable from its second event.
            Path file = scratch("test-genesis-refused.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            int threw = 0;
            try {
                MarketBootstrap.createMarket(log, ALICE, "doomed market", testKeys(),
                        1000, 0, 500);
            } catch (IOException expected) {
                threw = 1;
            }
            check("genesis refuses a policy it knows would be rejected", threw, 1);

            // And the good case really does land in the log rather than just validating.
            Path good = scratch("test-genesis-stipend.jsonl");
            Files.deleteIfExists(good);
            EventLog goodLog = new EventLog(good);
            MarketBootstrap.createMarket(goodLog, ALICE, "opening market", testKeys(),
                    1000, 2, 50);
            MarketState opened = EventApplier.replay(goodLog);
            check("the opening stipend is what the market publishes",
                    opened.stipendAmount(), 50);
            check("at the default interval", opened.stipendEveryFills(),
                    MarketState.DEFAULT_STIPEND_EVERY_FILLS);
            check("with the fee that pays for it", opened.listingFee(), 2);
        }

        section("T1b: where a rate stops being worth anything");
        {
            // Reported from a live market: a 2.5% fee appeared to take nothing. It was
            // working — the sales were worth 10 to 20 credits, and 2.5% of 20 floors to
            // zero. The arithmetic was right and the screen said nothing about it.
            check("2.5% needs a 40-credit sale",
                    MarketState.smallestTaxableSale(250), 40);
            check("10% needs only 10", MarketState.smallestTaxableSale(1000), 10);
            check("3.7% rounds up to 28", MarketState.smallestTaxableSale(370), 28);
            check("50% bites at 2", MarketState.smallestTaxableSale(5000), 2);
            check("no rate, no threshold", MarketState.smallestTaxableSale(0), 0);

            // The threshold must agree with the tax itself, or the screen would promise
            // something the settlement does not do.
            for (int bps : new int[]{100, 250, 370, 1000, 5000}) {
                long floor = MarketState.smallestTaxableSale(bps);
                check("at " + bps + " bps, one under the threshold is free",
                        MarketState.taxOn(floor - 1, bps), 0);
                check("at " + bps + " bps, the threshold itself is not",
                        MarketState.taxOn(floor, bps) > 0 ? 1 : 0, 1);
            }
        }

        section("T1c: a listing fee prices orders, not order value");
        {
            MarketState m = new MarketState();
            m.deposit(ALICE, IRON, 100);
            m.wallets().setBalance(ALICE, 1000L);
            m.setListingFee(25);

            long before = m.wallets().getBalance(ALICE);
            m.submitOrder(new Order(1, 50, IRON, 10, false, ALICE));
            check("a sell pays to be listed", m.wallets().getBalance(ALICE), before - 25);
            check("and still reserves its goods",
                    m.itemBalances().getBalance(ALICE, IRON), 90);

            // Flat, so ten small orders cost ten times what one does. That is the whole
            // point: what is being discouraged is the count, not the value.
            long beforeSmall = m.wallets().getBalance(ALICE);
            m.submitOrder(new Order(2, 1, IRON, 1, false, ALICE));
            check("a tiny order costs the same",
                    m.wallets().getBalance(ALICE), beforeSmall - 25);

            // Not refunded. A refundable fee deters nothing, and this is the only
            // charge in the market that can be paid for an order that never traded.
            long beforeCancel = m.wallets().getBalance(ALICE);
            m.cancelOrder(1, IRON, false, ALICE);
            check("cancelling returns the goods",
                    m.itemBalances().getBalance(ALICE, IRON), 99);
            check("but not the listing fee",
                    m.wallets().getBalance(ALICE), beforeCancel);
        }

        section("T1d: an order that cannot pay to be listed is refused");
        {
            // The awkward consequence of pricing placement: a seller holding goods and
            // no credits cannot list at all. Refused up front rather than discovered
            // during settlement, where the order would already have been accepted.
            MarketState m = new MarketState();
            m.deposit(ALICE, IRON, 100);
            m.wallets().setBalance(ALICE, 10L);
            m.setListingFee(25);

            check("a seller who cannot pay the fee is refused",
                    m.submitOrder(new Order(1, 50, IRON, 10, false, ALICE)).accepted ? 1 : 0, 0);
            check("and keeps their goods",
                    m.itemBalances().getBalance(ALICE, IRON), 100);
            check("and their credits", m.wallets().getBalance(ALICE), 10);

            // A buy has to cover both its reservation and the fee, or it would be
            // accepted and then be unable to pay for itself.
            MarketState b = new MarketState();
            b.wallets().setBalance(BOB, 100L);
            b.setListingFee(25);
            check("a buy needing 100 plus the fee is refused",
                    b.submitOrder(new Order(1, 10, IRON, 10, true, BOB)).accepted ? 1 : 0, 0);
            check("one that leaves room for it is not",
                    b.submitOrder(new Order(2, 7, IRON, 10, true, BOB)).accepted ? 1 : 0, 1);
            check("paying reservation and fee together",
                    b.wallets().getBalance(BOB), 100 - 70 - 25);
        }

        section("T2: the tax comes off the seller, and is burned");
        {
            MarketState m = new MarketState();
            m.deposit(ALICE, IRON, 10);
            m.wallets().setBalance(BOB, 1000L);
            m.setTaxBps(1000);              // 10%

            long supplyBefore = m.wallets().getBalance(ALICE) + m.wallets().getBalance(BOB);

            // Order is (id, price, item, volume, isBid, who) — price before volume.
            m.submitOrder(new Order(1, 50, IRON, 10, false, ALICE));   // ask 10 @ 50
            m.submitOrder(new Order(2, 50, IRON, 10, true, BOB));      // buy 10 @ 50

            // 10 units at 50 = 500. 10% of 500 = 50.
            check("seller is credited net of tax", m.wallets().getBalance(ALICE), 450);
            check("buyer pays the full price", m.wallets().getBalance(BOB), 500);
            check("buyer gets the goods", m.itemBalances().getBalance(BOB, IRON), 10);

            long supplyAfter = m.wallets().getBalance(ALICE) + m.wallets().getBalance(BOB);
            check("the tax left the system entirely", supplyBefore - supplyAfter, 50);
        }

        section("T3: a rate change is not retroactive");
        {
            // Replay gives this for free — fills settle against the rate in force when
            // they were applied, and nothing in the code says so. Which is exactly why
            // it is worth a test: it is correctness by construction that a later
            // refactor could remove without any other check noticing.
            MarketState m = new MarketState();
            m.deposit(ALICE, IRON, 20);
            m.wallets().setBalance(BOB, 2000L);

            m.submitOrder(new Order(1, 50, IRON, 10, false, ALICE));
            m.submitOrder(new Order(2, 50, IRON, 10, true, BOB));
            check("untaxed while the rate was zero", m.wallets().getBalance(ALICE), 500);

            m.setTaxBps(1000);              // 10%, from here on

            m.submitOrder(new Order(3, 50, IRON, 10, false, ALICE));
            m.submitOrder(new Order(4, 50, IRON, 10, true, BOB));
            // Second trade: 500 gross, 50 tax, 450 net. First trade keeps its full 500.
            check("only the later trade is taxed", m.wallets().getBalance(ALICE), 950);
        }

        section("V1b: every deposit rule needs deposits counted, not just the cap");
        {
            // The statistics multiple was left out of the host's list, so setting it
            // alone built a limiter with a zero-length window. It tracked nothing,
            // usedBy always answered zero, and each deposit was judged on its own
            // against the multiple — so ten handled iron authorised thirty deposited,
            // then thirty more. The rule was walked through by splitting a deposit,
            // which is the failure DepositLimiter.tracking() was written to prevent for
            // the play-hour rule before this one existed.
            ServerConfig statsOnly = ServerConfig.friendGroup(25555);
            statsOnly.maxDepositMultipleOfHandled = 3;
            check("the statistics rule counts deposits",
                    statsOnly.countsDeposits() ? 1 : 0, 1);

            ServerConfig capOnly = ServerConfig.friendGroup(25555);
            capOnly.maxDepositUnitsPerWindow = 100;
            check("so does the cap", capOnly.countsDeposits() ? 1 : 0, 1);

            ServerConfig hoursOnly = ServerConfig.friendGroup(25555);
            hoursOnly.maxDepositUnitsPerPlayHour = 100;
            check("so does claimed play time", hoursOnly.countsDeposits() ? 1 : 0, 1);

            check("and nothing configured keeps nothing",
                    ServerConfig.friendGroup(25555).countsDeposits() ? 1 : 0, 0);

            // A zero window under any of them is the same silent failure, so validation
            // refuses it rather than building a limiter that answers about one deposit.
            ServerConfig noWindow = ServerConfig.friendGroup(25555);
            noWindow.maxDepositMultipleOfHandled = 3;
            noWindow.depositWindowMinutes = 0;
            check("a zero window is refused, not quietly accepted",
                    noWindow.problem() != null ? 1 : 0, 1);

            // Tracking without a ceiling is the shape the statistics rule needs: no cap
            // to enforce, but a running total to be asked about.
            DepositLimiter tracked = new DepositLimiter(0, 60 * 60_000L);
            check("a limiter with no cap still tracks", tracked.tracking() ? 1 : 0, 1);
            check("and enforces nothing", tracked.enabled() ? 1 : 0, 0);
            tracked.record(ALICE, IRON, 30, 1_000L);
            tracked.record(ALICE, IRON, 30, 2_000L);
            check("so splitting a deposit no longer hides it",
                    tracked.usedBy(ALICE, IRON, 3_000L), 60);
        }

        section("V1: a deposit cap counts a window, on the host's clock");
        {
            // Time is passed in rather than read, so this tests the rule instead of
            // testing whether the suite can outrun a wall clock.
            long minute = 60_000L;
            DepositLimiter lim = new DepositLimiter(100, 10 * minute);
            long t = 1_000_000L;

            check("under the cap is allowed", lim.allows(ALICE, 60, t) ? 1 : 0, 1);
            lim.record(ALICE, IRON, 60, t);
            check("and is counted", lim.usedBy(ALICE, t), 60);
            check("what is left", lim.remainingFor(ALICE, t), 40);

            check("exactly reaching the cap is allowed", lim.allows(ALICE, 40, t) ? 1 : 0, 1);
            check("one past it is not", lim.allows(ALICE, 41, t) ? 1 : 0, 0);

            // One identity's spending is not another's.
            check("a different identity is unaffected", lim.usedBy(BOB, t), 0);
            check("and has its whole allowance", lim.remainingFor(BOB, t), 100);

            // The window slides rather than resetting on a boundary.
            check("still counted just inside the window",
                    lim.usedBy(ALICE, t + 9 * minute), 60);
            check("gone once it has passed", lim.usedBy(ALICE, t + 11 * minute), 0);
            check("and the allowance is whole again",
                    lim.allows(ALICE, 100, t + 11 * minute) ? 1 : 0, 1);
        }

        section("V2: an unconfigured cap refuses nothing");
        {
            // Off by default: a friend group has no cheating problem worth a ceiling,
            // and a limit that surprises people mid-session is worse than none.
            DepositLimiter off = new DepositLimiter(0, 60_000L);
            check("no ceiling is enforced", off.enabled() ? 1 : 0, 0);
            check("allows an absurd deposit",
                    off.allows(ALICE, 1_000_000_000L, 1L) ? 1 : 0, 1);

            // Counting and enforcing are separate switches. The attestation check needs
            // a running total even with no ceiling set, or it would compare each deposit
            // alone against claimed play time and never a sum — which is a hundred units
            // as often as you like.
            off.record(ALICE, IRON, 500, 1L);
            check("but deposits are still counted", off.usedBy(ALICE, 1L), 500);

            DepositLimiter untracked = new DepositLimiter(0, 0L);
            check("with no window, nothing is kept", untracked.tracking() ? 1 : 0, 0);
            untracked.record(ALICE, IRON, 500, 1L);
            check("and nothing is counted", untracked.usedBy(ALICE, 1L), 0);

            check("a default config has no cap",
                    ServerConfig.friendGroup(25555).maxDepositUnitsPerWindow, 0);

            // A cap with no window would refuse everything forever, since nothing could
            // ever age out of it.
            ServerConfig bad = ServerConfig.friendGroup(25555);
            bad.maxDepositUnitsPerWindow = 500;
            bad.depositWindowMinutes = 0;
            check("a cap with no window is refused", bad.problem() != null ? 1 : 0, 1);

            ServerConfig fine = ServerConfig.friendGroup(25555);
            fine.maxDepositUnitsPerWindow = 500;
            check("a cap with the default window is fine",
                    fine.problem() == null ? 1 : 0, 1);
        }

        section("Z1: an event must be stamped with its market before it is validated");
        {
            // The bug this pins: the local submit path stamped marketId after calling
            // validate, so checkGenesis saw null and refused everything non-genesis as
            // belonging to a different market. It surfaced as the fee control being
            // rejected, but it applied to every event authored offline.
            MarketState m = new MarketState();
            UUID marketId = UUID.randomUUID();
            m.setMarketIdentity(marketId, "stamping market", ALICE);
            m.registerKey(ALICE, "alice-key");

            Event.MarketPolicy unstamped = new Event.MarketPolicy();
            unstamped.userId = ALICE;
            unstamped.taxBps = 250;
            unstamped.grantAmount = m.welcomeGrant();
            unstamped.timestamp = 1L;
            // marketId deliberately left null, as the old order of operations left it

            SequencedEvent probe = new SequencedEvent();
            probe.seq = 2;
            probe.event = unstamped;
            check("an unstamped event is refused",
                    EventApplier.validate(m, probe).accepted ? 1 : 0, 0);

            unstamped.marketId = marketId;
            check("the same event is accepted once stamped",
                    EventApplier.validate(m, probe).accepted ? 1 : 0, 1);
        }

        section("Z2: a market can be removed from a world, except the first");
        {
            Path world = scratch("test-slots-z2");
            deleteRecursively(world);
            Files.createDirectories(world);

            Path def = MarketSlots.logPath(world, MarketSlots.DEFAULT);
            Files.createDirectories(def.getParent());
            Files.write(def, "{}".getBytes());

            String extra = MarketSlots.createNext(world);
            Path extraLog = MarketSlots.logPath(world, extra);
            Files.write(extraLog, "{}".getBytes());
            // Everything a market owns sits beside its log, so removal has to take the
            // directory — a stale high-water mark would be inherited by whatever
            // occupied the name next.
            Files.write(extraLog.resolveSibling("high-water.json"), "{}".getBytes());

            check("both are there", MarketSlots.list(world).size(), 2);

            MarketSlots.delete(world, extra);
            check("the extra one is gone", MarketSlots.list(world).contains(extra) ? 1 : 0, 0);
            check("its files went with it",
                    Files.exists(extraLog.getParent()) ? 1 : 0, 0);
            check("the first one is untouched", Files.exists(def) ? 1 : 0, 1);

            // There has to be one slot that always exists, and it is where a
            // single-market world already keeps its market.
            boolean refused = false;
            try {
                MarketSlots.delete(world, MarketSlots.DEFAULT);
            } catch (IOException e) {
                refused = true;
            }
            check("the first market cannot be removed", refused ? 1 : 0, 1);
            check("and is still there", Files.exists(def) ? 1 : 0, 1);
        }

        section("Y1: a market name is a label, not a path");
        {
            // These become directory names, so this is the boundary between the two.
            check("an ordinary name", MarketSlots.isValidName("friends") ? 1 : 0, 1);
            check("spaces, dashes and digits are fine",
                    MarketSlots.isValidName("Big Server-2") ? 1 : 0, 1);
            check("the default is always valid",
                    MarketSlots.isValidName(MarketSlots.DEFAULT) ? 1 : 0, 1);

            check("parent directories are refused",
                    MarketSlots.isValidName("..") ? 1 : 0, 0);
            check("so is anything with a separator",
                    MarketSlots.isValidName("a/b") ? 1 : 0, 0);
            check("and a backslash",
                    MarketSlots.isValidName("a\\b") ? 1 : 0, 0);
            check("and an absolute path",
                    MarketSlots.isValidName("C:\\windows") ? 1 : 0, 0);
            check("and a traversal buried in the middle",
                    MarketSlots.isValidName("ok/../../etc") ? 1 : 0, 0);
            check("empty is not a name", MarketSlots.isValidName("  ") ? 1 : 0, 0);
            check("null is not a name", MarketSlots.isValidName(null) ? 1 : 0, 0);

            // logPath refuses rather than sanitising, so a bad name cannot become a
            // path that merely looks different from what was asked for.
            Path world = scratch("test-slots-y1");
            check("a refused name yields no path",
                    MarketSlots.logPath(world, "../escape") == null ? 1 : 0, 1);
        }

        section("Y2: slots are separate markets, and the active one is remembered");
        {
            Path world = scratch("test-slots-y2");
            deleteRecursively(world);
            Files.createDirectories(world);

            // A slot is a place a world can be, not a place with something in it. The
            // default is always available, including in a world that has never had a
            // market — otherwise a freshly made slot would be impossible to switch to.
            check("an empty world still offers the default",
                    MarketSlots.list(world).size(), 1);
            check("and reports it as active",
                    MarketSlots.DEFAULT.equals(MarketSlots.active(world)) ? 1 : 0, 1);
            check("with no market in it yet",
                    MarketSlots.marketNameIn(world, MarketSlots.DEFAULT) == null ? 1 : 0, 1);

            // The default slot stays exactly where a single-market world already keeps
            // it, so nothing existing has to move.
            Path def = MarketSlots.logPath(world, MarketSlots.DEFAULT);
            Files.createDirectories(def.getParent());
            Files.write(def, "{}".getBytes());
            check("the default sits where it always did",
                    def.endsWith(Paths.get("economiesmod", "market.jsonl")) ? 1 : 0, 1);

            Path other = MarketSlots.logPath(world, "big");
            Files.createDirectories(other.getParent());
            Files.write(other, "{}".getBytes());

            List<String> slots = MarketSlots.list(world);
            check("both are listed", slots.size(), 2);
            check("default first", MarketSlots.DEFAULT.equals(slots.get(0)) ? 1 : 0, 1);

            // Everything a market owns is a sibling of its log, so the slots cannot
            // share a high-water mark — which would otherwise reset on every switch.
            check("their files do not collide",
                    def.resolveSibling("high-water.json")
                            .equals(other.resolveSibling("high-water.json")) ? 1 : 0, 0);

            MarketSlots.setActive(world, "big");
            check("the choice survives being written and re-read",
                    "big".equals(MarketSlots.active(world)) ? 1 : 0, 1);

            // A pointer at a market that no longer exists must leave a usable world.
            Files.write(world.resolve("economiesmod").resolve("active-slot"),
                    "../escape".getBytes());
            check("a hand-edited pointer falls back to the default",
                    MarketSlots.DEFAULT.equals(MarketSlots.active(world)) ? 1 : 0, 1);

            // A new slot has to be reachable the moment it is made, or the feature has
            // no way in: a world starts with one and nothing else creates them.
            String made = MarketSlots.createNext(world);
            check("a new slot appears immediately",
                    MarketSlots.list(world).contains(made) ? 1 : 0, 1);
            check("and holds no market yet",
                    MarketSlots.marketNameIn(world, made) == null ? 1 : 0, 1);
            check("making another gives a different name",
                    made.equals(MarketSlots.createNext(world)) ? 1 : 0, 0);
        }

        section("X1: a fork reset only offers back what the fork actually cost");
        {
            // Orders placed before the divergence point come back on reconnecting,
            // because that history is shared. Offering those too would invite someone to
            // place a second copy of an order the host still holds.
            Path p = scratch("test-branchdiff-x1.jsonl");
            Files.deleteIfExists(p);

            PlayerKeys keys = PlayerKeys.generate();
            EventLog log = new EventLog(p);
            MarketBootstrap.createMarket(log, ALICE, "fork diff market", keys);
            UUID marketId = log.marketId();

            Event.Deposit dep = new Event.Deposit();
            dep.userId = ALICE;
            dep.marketId = marketId;
            dep.itemId = IRON;
            dep.quantity = 100;
            dep.timestamp = 1L;
            log.append(dep, keys.sign(EventCanonical.canonicalPayload(dep)));

            // Two orders before the split, two after.
            long forkSeq = -1;
            for (int i = 0; i < 4; i++) {
                Event.PlaceOrder po = new Event.PlaceOrder();
                po.userId = ALICE;
                po.marketId = marketId;
                po.itemId = IRON;
                po.volume = 5;
                po.price = 10 + i;      // distinct prices, so a failure is legible
                po.isBid = false;
                po.timestamp = 2L + i;
                SequencedEvent se = log.append(po, keys.sign(EventCanonical.canonicalPayload(po)));
                if (i == 1) forkSeq = se.seq;    // the last event both branches agree on
            }

            List<Order> lost = BranchDiff.ordersOnlyAfter(log, forkSeq, ALICE);
            check("only the post-fork orders are offered back", lost.size(), 2);

            // The boundary is inclusive, and getting it wrong is silent either way: one
            // order too many invites a duplicate, one too few loses a real order.
            long lowest = Long.MAX_VALUE;
            for (Order o : lost) lowest = Math.min(lowest, o.value());
            check("the order at the fork point is not among them", lowest, 12);

            check("someone else's orders are not offered to us",
                    BranchDiff.ordersOnlyAfter(log, forkSeq, BOB).size(), 0);

            // A market that never diverged loses nothing by this measure.
            check("no divergence, nothing lost",
                    BranchDiff.ordersOnlyAfter(log, log.lastSeq(), ALICE).size(), 0);

            // And a fork at genesis costs every order placed since.
            check("a fork at the very start costs all four",
                    BranchDiff.ordersOnlyAfter(log, 1, ALICE).size(), 4);
        }

        section("W3: deposits are weighed against the player's own statistics");
        {
            // The one figure here the player did not write. Minecraft counts mined,
            // crafted and picked up during ordinary play; /give increments none of them,
            // so somebody handing over far more than they have ever handled is
            // contradicting a record they cannot quietly restate.
            WorldAttestation a = new WorldAttestation();
            a.gameMode = "survival";
            a.handledByItem = new java.util.HashMap<>();
            a.handledByItem.put(IRON, 500L);

            check("what it says about that item", a.handledOf(IRON), 500);
            check("and nothing about others", a.handledOf(DIAMOND), 0);
            check("or about an item it was never given",
                    new WorldAttestation().handledOf(IRON), 0);

            // Per item, so a large haul of one thing says nothing about another.
            DepositLimiter lim = new DepositLimiter(0, 60_000L);
            long t = 1_000L;
            lim.record(ALICE, IRON, 300, t);
            lim.record(ALICE, DIAMOND, 40, t);
            check("iron is counted as iron", lim.usedBy(ALICE, IRON, t), 300);
            check("diamonds separately", lim.usedBy(ALICE, DIAMOND, t), 40);
            check("and together for the overall cap", lim.usedBy(ALICE, t), 340);

            // Splitting a deposit must not get round it, which is why the running total
            // is what the rule reads rather than the single event.
            lim.record(ALICE, IRON, 300, t);
            check("a second deposit adds to the first",
                    lim.usedBy(ALICE, IRON, t), 600);
        }

        section("W4: what the market gave you, you can always give back");
        {
            // A withdrawal reaches an inventory through insertStack, which increments no
            // statistic — the same reason /give leaves no trace. So without counting
            // them, the statistics rule would refuse somebody re-depositing the very
            // goods this market handed them, which is the one case where provenance is
            // not in question at all.
            MarketState m = new MarketState();
            m.deposit(ALICE, IRON, 100);

            check("nothing withdrawn yet", m.withdrawnBy(ALICE, IRON), 0);

            check("withdrawing succeeds", m.withdraw(ALICE, IRON, 40) ? 1 : 0, 1);
            check("and is remembered", m.withdrawnBy(ALICE, IRON), 40);

            check("withdrawing again adds to it",
                    m.withdraw(ALICE, IRON, 25) ? 1 : 0, 1);
            check("cumulatively", m.withdrawnBy(ALICE, IRON), 65);

            // Only grows. Putting items back does not reduce what was handed out, or
            // the allowance would evaporate the moment it was used.
            m.deposit(ALICE, IRON, 65);
            check("depositing does not undo it", m.withdrawnBy(ALICE, IRON), 65);

            // Per person and per item, since it stands in for provenance of one thing
            // in one pair of hands.
            check("another item is separate", m.withdrawnBy(ALICE, DIAMOND), 0);
            check("another person is separate", m.withdrawnBy(BOB, IRON), 0);

            // A refused withdrawal must not count — it never left the ledger.
            check("overdrawing fails", m.withdraw(BOB, IRON, 10) ? 1 : 0, 0);
            check("and records nothing", m.withdrawnBy(BOB, IRON), 0);
        }

        section("W1: an attestation is judged by contradiction, not belief");
        {
            // Nothing here can be verified. What can be done is to notice that two
            // statements cannot both be true — the same shape as catching a client that
            // claims to be vanilla while registering plugin channels.
            ServerConfig cfg = ServerConfig.friendGroup(25555);
            cfg.maxDepositUnitsPerPlayHour = 100;

            WorldAttestation young = new WorldAttestation();
            young.gameMode = "survival";
            young.worldAgeTicks = WorldAttestation.TICKS_PER_HOUR / 2;   // half an hour

            check("half an hour reads as half an hour",
                    (long) Math.round(young.claimedHours() * 10), 5);
            check("a plausible haul passes",
                    young.objections(cfg, 40).isEmpty() ? 1 : 0, 1);
            check("more than that half hour could yield does not",
                    young.objections(cfg, 400).isEmpty() ? 1 : 0, 0);

            // Claiming a longer history lifts the ceiling — which is the point. The lie
            // is not prevented, it is made specific and recorded.
            WorldAttestation old = new WorldAttestation();
            old.gameMode = "survival";
            old.worldAgeTicks = WorldAttestation.TICKS_PER_HOUR * 40;
            check("forty claimed hours affords the same haul",
                    old.objections(cfg, 400).isEmpty() ? 1 : 0, 1);

            // The refusal has to survive being checked. It printed the hours to one
            // decimal while flooring the ceiling from the real value, so a world of
            // 1.56 hours read "1.6 hours" beside a limit of 156 — and multiplying gave
            // 160. A refusal whose own arithmetic does not add up reads as a broken
            // server rather than a caught one.
            WorldAttestation awkward = new WorldAttestation();
            awkward.gameMode = "survival";
            awkward.worldAgeTicks = (long) (WorldAttestation.TICKS_PER_HOUR * 1.56);
            String said = awkward.objections(cfg, 202).get(0);
            check("the refusal states the rate it used",
                    said.contains("100 per claimed hour") ? 1 : 0, 1);
            check("and the hours it used, to where they reconcile",
                    said.contains("1.56") ? 1 : 0, 1);
            check("and the ceiling that follows from them",
                    said.contains("156") ? 1 : 0, 1);

            // Off unless configured, like everything else in this area.
            ServerConfig noPolicy = ServerConfig.friendGroup(25555);
            check("no policy, no objection",
                    young.objections(noPolicy, 1_000_000).isEmpty() ? 1 : 0, 1);
        }

        section("W2: the claims that stand on their own");
        {
            ServerConfig strict = ServerConfig.friendGroup(25555);
            strict.refuseCreativeWorlds = true;
            strict.refuseCheatWorlds = true;

            WorldAttestation creative = new WorldAttestation();
            creative.gameMode = "creative";
            check("a creative world is objected to",
                    creative.objections(strict, 0).isEmpty() ? 1 : 0, 0);
            check("and it is recognised as creative", creative.isCreative() ? 1 : 0, 1);

            WorldAttestation cheats = new WorldAttestation();
            cheats.gameMode = "survival";
            cheats.commandsAllowed = true;
            check("so is one with commands enabled",
                    cheats.objections(strict, 0).isEmpty() ? 1 : 0, 0);

            // The Open to LAN route: a world created without cheats, with "Allow
            // Cheats" ticked afterwards. openToLan calls PlayerManager.setCheatsAllowed
            // and leaves the saved settings alone, so the world goes on reporting
            // commandsAllowed false for the rest of its life while /give works. Reading
            // only the saved flag made this the obvious way past every rule here.
            WorldAttestation lan = new WorldAttestation();
            lan.gameMode = "survival";
            lan.commandsAllowed = false;
            lan.cheatsLive = true;
            check("cheats enabled after creation are caught too",
                    lan.objections(strict, 0).isEmpty() ? 1 : 0, 0);
            check("and are reported as the later switch they are",
                    lan.cheatsEnabledLater() ? 1 : 0, 1);
            check("unlike a world that always had them",
                    cheats.cheatsEnabledLater() ? 1 : 0, 0);
            check("both count as cheats being available",
                    lan.cheatsAvailable() && cheats.cheatsAvailable() ? 1 : 0, 1);

            // Enable cheats, take what you want, quit to the title, come back. Minecraft
            // clears the LAN flag on reload and never wrote anything to the save, so the
            // world truthfully reports having never had commands while the goods are
            // still in the inventory. The only thing that knows better is the note the
            // mod wrote at the time.
            WorldAttestation reloaded = new WorldAttestation();
            reloaded.gameMode = "survival";
            reloaded.commandsAllowed = false;
            reloaded.cheatsLive = false;
            reloaded.cheatsEverSeen = true;
            check("a world reloaded to clear the flag is still caught",
                    reloaded.objections(strict, 0).isEmpty() ? 1 : 0, 0);
            check("and is not mistaken for one that has them right now",
                    reloaded.cheatsEnabledLater() ? 1 : 0, 0);

            WorldAttestation plain = new WorldAttestation();
            plain.gameMode = "survival";
            check("an ordinary survival world is not",
                    plain.objections(strict, 0).isEmpty() ? 1 : 0, 1);
            check("and has no cheats by any route",
                    plain.cheatsAvailable() ? 1 : 0, 0);

            // A host that has not asked for any of this must not start refusing people.
            ServerConfig lax = ServerConfig.friendGroup(25555);
            check("and neither is refused by a host that did not ask",
                    creative.objections(lax, 0).isEmpty() ? 1 : 0, 1);
        }

        section("U1: a welcome grant must be the amount this market publishes");
        {
            // Nothing checks who authors a grant, and nothing can: hosting rotates, so a
            // replica replaying the log cannot know who was sequencing at that point.
            // The amount is therefore the only enforceable part, and before it was
            // checked, any identity could sign itself a grant for any sum and every
            // replica accepted it. Two ways in: a server configured with a zero grant
            // never marks anyone granted, so the once-per-identity rule never fires; and
            // a grant authored in one's own local world migrates in at full value.
            MarketState m = new MarketState();
            m.setMarketIdentity(UUID.randomUUID(), "grant probe", ALICE);
            m.registerKey(ALICE, "alice-key");
            m.registerKey(BOB, "bob-key");

            check("a self-authored fortune is refused",
                    grantRejection(m, BOB, BOB, 1_000_000_000L) != null ? 1 : 0, 1);
            check("so is one credit too many",
                    grantRejection(m, BOB, BOB, m.welcomeGrant() + 1) != null ? 1 : 0, 1);

            // The fix must not simply disable grants — an honest host still issues them,
            // and the amount a liar can take is now the one they would have been given.
            check("the market's own figure is accepted",
                    grantRejection(m, ALICE, BOB, m.welcomeGrant()) == null ? 1 : 0, 1);

            // A market that grants nothing grants nothing to anybody, including the
            // people who would previously have exploited exactly this configuration.
            MarketState none = new MarketState();
            none.setMarketIdentity(UUID.randomUUID(), "no grants", ALICE);
            none.registerKey(ALICE, "alice-key");
            none.registerKey(BOB, "bob-key");
            none.setWelcomeGrant(0);
            check("a zero-grant market grants nobody anything",
                    grantRejection(none, BOB, BOB, 1000) != null ? 1 : 0, 1);
        }

        section("U2: the grant amount is policy, and bounded like the fee");
        {
            MarketState m = new MarketState();
            m.setMarketIdentity(UUID.randomUUID(), "policy market", ALICE);
            m.registerKey(ALICE, "alice-key");

            check("the default is what markets used before policy existed",
                    m.welcomeGrant(), ServerConfig.DEFAULT_WELCOME_GRANT);
            check("a sane grant is allowed",
                    policyRejection(m, ALICE, 0, 50) == null ? 1 : 0, 1);
            check("zero is allowed — a market may grant nothing",
                    policyRejection(m, ALICE, 0, 0) == null ? 1 : 0, 1);
            check("negative is refused",
                    policyRejection(m, ALICE, 0, -1) != null ? 1 : 0, 1);
            check("above the ceiling is refused",
                    policyRejection(m, ALICE, 0, MarketState.MAX_WELCOME_GRANT + 1)
                            != null ? 1 : 0, 1);
        }

        section("U3: a host says so when its configured grant is not the market's");
        {
            // Nothing breaks when the two disagree — issueWelcomeGrant takes the amount
            // from the market and reads the config only as "issue grants, or not", so
            // what goes out is correct either way. What is worth reporting is that an
            // operator editing welcomeGrant on an existing market changes a number in a
            // file and nothing else, because the amount was fixed when the market was
            // created.
            //
            // Genesis records the figure whatever it is, so a market can always be
            // asked what it grants rather than falling back to a constant.
            Path chosen = scratch("test-grant-chosen.jsonl");
            Files.deleteIfExists(chosen);
            EventLog chosenLog = new EventLog(chosen);
            MarketBootstrap.createMarket(chosenLog, ALICE, "grant 50 market",
                    testKeys(), 50);
            check("genesis records the grant it was given",
                    EventApplier.replay(chosenLog).welcomeGrant(), 50);

            Path plain = scratch("test-grant-default.jsonl");
            Files.deleteIfExists(plain);
            EventLog plainLog = new EventLog(plain);
            MarketBootstrap.createMarket(plainLog, ALICE, "default grant market",
                    testKeys());
            check("and records the default too, rather than leaving it unstated",
                    EventApplier.replay(plainLog).welcomeGrant(),
                    ServerConfig.DEFAULT_WELCOME_GRANT);

            Path file = scratch("test-grant-mismatch.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketBootstrap.createMarket(log, ALICE, "mismatch market", testKeys());

            ServerConfig agrees = ServerConfig.friendGroup(25555);
            agrees.hostUserId = ALICE.toString();
            agrees.welcomeGrant = ServerConfig.DEFAULT_WELCOME_GRANT;
            check("silent when the server and the market agree",
                    hostFor(agrees, file).grantMismatchWarning() == null ? 1 : 0, 1);

            ServerConfig disagrees = ServerConfig.friendGroup(25555);
            disagrees.hostUserId = ALICE.toString();
            disagrees.welcomeGrant = 50;
            String warning = hostFor(disagrees, file).grantMismatchWarning();
            check("a mismatch is reported", warning != null ? 1 : 0, 1);

            // Zero is the opt-out from issuing grants at all, not an operator who got
            // the number wrong, so it must not be reported as one.
            ServerConfig off = ServerConfig.friendGroup(25555);
            off.hostUserId = ALICE.toString();
            off.welcomeGrant = 0;
            check("but issuing none is a choice, not a mistake",
                    hostFor(off, file).grantMismatchWarning() == null ? 1 : 0, 1);
            // Both numbers, because "your grant is wrong" without them sends an
            // operator back to the file to work out which way round it is.
            check("and names the market's figure and the server's",
                    warning != null && warning.contains("1000") && warning.contains("50")
                            ? 1 : 0, 1);
        }

        section("T5b: a listing fee typed with its allowance");
        {
            // The allowance had no control at all until now — no field, no server-config
            // key, nothing. listingFreeOrders was set in exactly one place, submitPolicy,
            // which copies whatever it already was, so it was zero at genesis and zero
            // forever. Every market ever created charged the flat fee, and the whole
            // escalating half of this feature was unreachable code with a test behind it.
            //
            // One field for both, because a fee and the allowance it climbs past are one
            // decision, and this project keeps finding the same bug in two numbers kept
            // in two places.
            check("a bare number is a flat fee",
                    MarketState.listingFeeFromText("2").fee, 2);
            check("and carries no allowance",
                    MarketState.listingFeeFromText("2").freeOrders, 0);

            check("a slash carries the allowance",
                    MarketState.listingFeeFromText("2/3").fee, 2);
            check("with the orders after it",
                    MarketState.listingFeeFromText("2/3").freeOrders, 3);

            check("space around it is not an error",
                    MarketState.listingFeeFromText("  2 / 3  ").freeOrders, 3);
            check("zero turns the fee off",
                    MarketState.listingFeeFromText("0").fee, 0);
            check("an explicit zero allowance is flat",
                    MarketState.listingFeeFromText("5/0").freeOrders, 0);

            // Half-typed rather than meaning zero. Reading "2/" as a flat fee would set
            // a policy the typist did not ask for and say nothing about it.
            check("a trailing slash is refused",
                    MarketState.listingFeeFromText("2/") == null ? 1 : 0, 1);
            check("a leading slash is refused",
                    MarketState.listingFeeFromText("/3") == null ? 1 : 0, 1);
            check("two slashes are refused",
                    MarketState.listingFeeFromText("2/3/4") == null ? 1 : 0, 1);
            check("a negative fee is refused",
                    MarketState.listingFeeFromText("-1") == null ? 1 : 0, 1);
            check("a negative allowance is refused",
                    MarketState.listingFeeFromText("2/-1") == null ? 1 : 0, 1);
            check("a decimal is refused — orders are whole",
                    MarketState.listingFeeFromText("2.5") == null ? 1 : 0, 1);
            check("words are refused",
                    MarketState.listingFeeFromText("two") == null ? 1 : 0, 1);
            check("empty is refused",
                    MarketState.listingFeeFromText("") == null ? 1 : 0, 1);
            check("null is refused",
                    MarketState.listingFeeFromText(null) == null ? 1 : 0, 1);

            // And what the parsed pair actually does, so this block is about the feature
            // rather than about string handling.
            MarketState m = new MarketState();
            m.setMarketIdentity(UUID.randomUUID(), "allowance market", ALICE);
            m.registerKey(ALICE, "alice-key");
            m.wallets().setBalance(ALICE, 10_000L);
            m.deposit(ALICE, IRON, 100);

            MarketState.ListingFeeSetting typed = MarketState.listingFeeFromText("2/3");
            m.setListingFee(typed.fee);
            m.setListingFreeOrders(typed.freeOrders);

            for (int i = 0; i < 3; i++) {
                m.submitOrder(new Order(700 + i, 60 + i, IRON, 1, false, ALICE));
            }
            check("typing 2/3 really does buy three orders at the base fee",
                    m.listingFeeFor(ALICE), 4);
        }

        section("T5: a fee typed as a percentage becomes exact basis points");
        {
            // The one place a human decimal meets a number every replica must agree on.
            check("whole percent", MarketState.bpsFromPercent("1"), 100);
            check("half percent", MarketState.bpsFromPercent("0.5"), 50);

            // Double.parseDouble("2.5") * 100 can land on 249.99999999999997, which
            // truncates to a 2.49% fee nobody asked for.
            check("the case a double gets wrong", MarketState.bpsFromPercent("2.5"), 250);
            check("two decimal places", MarketState.bpsFromPercent("0.01"), 1);
            check("zero turns it off", MarketState.bpsFromPercent("0"), 0);
            check("surrounding space is not an error",
                    MarketState.bpsFromPercent("  2.5 "), 250);
            check("the ceiling", MarketState.bpsFromPercent("50"), 5000);

            // Refused rather than rounded: a fee is not a number to be approximate about.
            check("finer than basis points is refused",
                    MarketState.bpsFromPercent("0.005"), -1);
            check("negative is refused", MarketState.bpsFromPercent("-1"), -1);
            check("words are refused", MarketState.bpsFromPercent("two"), -1);
            check("empty is refused", MarketState.bpsFromPercent(""), -1);
            check("null is refused", MarketState.bpsFromPercent(null), -1);
        }

        section("T4: only the creator sets policy, and only within bounds");
        {
            // Bounds live in EventApplier because that is the gate every replica passes
            // through. A rate the UI refused but the applier accepted would be a rate
            // one client could still write and everyone else would replay.
            MarketState m = new MarketState();
            m.setMarketIdentity(UUID.randomUUID(), "policy market", ALICE);
            // Authorship precedes everything else in validate, so both have to exist in
            // the market before the policy rule is the thing being tested.
            m.registerKey(ALICE, "alice-key");
            m.registerKey(BOB, "bob-key");

            check("the creator may set a rate",
                    policyRejection(m, ALICE, 250) == null ? 1 : 0, 1);
            check("someone else may not",
                    policyRejection(m, BOB, 250) != null ? 1 : 0, 1);
            check("above the ceiling is refused",
                    policyRejection(m, ALICE, MarketState.MAX_TAX_BPS + 1) != null ? 1 : 0, 1);
            check("the ceiling itself is allowed",
                    policyRejection(m, ALICE, MarketState.MAX_TAX_BPS) == null ? 1 : 0, 1);
            check("negative is refused",
                    policyRejection(m, ALICE, -1) != null ? 1 : 0, 1);
            check("zero is allowed — it turns the tax off",
                    policyRejection(m, ALICE, 0) == null ? 1 : 0, 1);

            // The listing fee is bounded low on purpose: a charge big enough to feel
            // like a tax is big enough to stop people repricing.
            check("a sane listing fee is allowed",
                    policyRejection(m, ALICE, 0, m.welcomeGrant(), 25) == null ? 1 : 0, 1);
            check("zero is allowed — it turns listing fees off",
                    policyRejection(m, ALICE, 0, m.welcomeGrant(), 0) == null ? 1 : 0, 1);
            check("negative is refused",
                    policyRejection(m, ALICE, 0, m.welcomeGrant(), -1) != null ? 1 : 0, 1);
            check("above the ceiling is refused",
                    policyRejection(m, ALICE, 0, m.welcomeGrant(),
                            MarketState.MAX_LISTING_FEE + 1) != null ? 1 : 0, 1);
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL " + checksRun + " CHECKS PASSED");
        } else {
            System.out.println(failures + " of " + checksRun + " checks FAILED");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    // ── helpers ──

    private static int failures = 0;
    private static int checksRun = 0;

    /**
     * Scratch files go under build/, not the directory you happened to launch from.
     * Nothing here cleans up after itself — each test deletes its own file on the way
     * in — so they need somewhere to live that isn't the repo root.
     */
    private static final Path SCRATCH_DIR = Paths.get("build", "test-scratch");

    /**
     * Clears a scratch world so a run does not inherit slots from the last one.
     *
     * Deepest-first, because a directory cannot be removed while it still holds
     * anything. Failures are ignored: this only ever runs against build/test-scratch.
     */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        List<Path> paths = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.forEach(paths::add);
        }
        java.util.Collections.sort(paths, java.util.Collections.reverseOrder());
        for (Path p : paths) {
            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        }
    }

    /** A host over an existing log, built but never started — no socket is opened. */
    private static io.github.badbull643.economiesmod.core.net.HostServer hostFor(
            ServerConfig cfg, Path log) throws Exception {
        return new io.github.badbull643.economiesmod.core.net.HostServer(
                cfg, log, testKeys(), new PeerCache(scratch("test-grant-peers.json")));
    }

    /**
     * Which line holds the first event of this type.
     *
     * These tests used to index the log by position, which quietly stopped meaning what
     * it said the moment genesis grew a second event: one of them went on passing while
     * tampering with the wrong record entirely. Position in the file is not a contract;
     * the event type is.
     */
    private static int lineOf(List<String> lines, String eventType) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("\"eventType\":\"" + eventType + "\"")) return i;
        }
        throw new IllegalStateException("no " + eventType + " event in the log");
    }

    private static Path scratch(String name) {
        try {
            Files.createDirectories(SCRATCH_DIR);
        } catch (IOException e) {
            throw new RuntimeException("could not create scratch dir " + SCRATCH_DIR, e);
        }
        return SCRATCH_DIR.resolve(name);
    }

    private static void section(String name) {
        System.out.println("  [" + name + "]");
    }

    /**
     * Why EventApplier would refuse this policy change, or null if it would accept it.
     *
     * Goes through validate rather than testing the bounds directly, because the bounds
     * only mean anything at the gate every replica passes through — checking them
     * anywhere else would prove something no client actually relies on.
     */
    /** Why EventApplier would refuse this grant, or null if it would accept it. */
    private static String grantRejection(MarketState state, UUID author, UUID target,
                                         long amount) {
        Event.WelcomeGrant wg = new Event.WelcomeGrant();
        wg.userId = author;
        wg.targetUserId = target;
        wg.marketId = state.marketId();
        wg.amount = amount;
        wg.timestamp = 1L;

        SequencedEvent se = new SequencedEvent();
        se.seq = 2;
        se.event = wg;

        EventApplier.Result r = EventApplier.validate(state, se);
        return r.accepted ? null : r.reason;
    }

    /** Why a stipend claim would be refused, or null if it would stand. */
    private static String stipendRejection(MarketState state, UUID who, long amount) {
        Event.Stipend st = new Event.Stipend();
        st.userId = who;
        st.marketId = state.marketId();
        st.amount = amount;
        st.timestamp = 1L;
        SequencedEvent se = new SequencedEvent();
        se.seq = state.fillsEver() + 100;   // past genesis; the rule counts fills, not seq
        se.event = st;
        EventApplier.Result r = EventApplier.validate(state, se);
        return r.accepted ? null : r.reason;
    }

    /** Why a policy setting this stipend against this fee would be refused. */
    private static String policyStipendRejection(MarketState state, UUID author,
                                                 long listingFee, long stipend,
                                                 long everyFills) {
        Event.MarketPolicy mp = new Event.MarketPolicy();
        mp.userId = author;
        mp.marketId = state.marketId();
        mp.taxBps = 0;
        mp.grantAmount = state.welcomeGrant();
        mp.listingFee = listingFee;
        mp.stipendAmount = stipend;
        mp.stipendEveryFills = everyFills;
        mp.timestamp = 1L;
        SequencedEvent se = new SequencedEvent();
        se.seq = 2;
        se.event = mp;
        EventApplier.Result r = EventApplier.validate(state, se);
        return r.accepted ? null : r.reason;
    }

    /** N fills of one iron at one credit, for advancing the market's fill count. */
    private static List<Fill> fillsOf(int n) {
        List<Fill> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Fill(ALICE, BOB, 1, 1, IRON));
        return out;
    }

    private static String policyRejection(MarketState state, UUID author, int bps) {
        return policyRejection(state, author, bps, state.welcomeGrant());
    }

    private static String policyRejection(MarketState state, UUID author, int bps,
                                          long grant) {
        return policyRejection(state, author, bps, grant, state.listingFee());
    }

    private static String policyRejection(MarketState state, UUID author, int bps,
                                          long grant, long listingFee) {
        Event.MarketPolicy mp = new Event.MarketPolicy();
        mp.userId = author;
        mp.marketId = state.marketId();
        mp.taxBps = bps;
        mp.grantAmount = grant;
        mp.listingFee = listingFee;
        mp.timestamp = 1L;

        SequencedEvent se = new SequencedEvent();
        se.seq = 2;
        se.event = mp;

        EventApplier.Result r = EventApplier.validate(state, se);
        return r.accepted ? null : r.reason;
    }

    private static void check(String label, long actual, long expected) {
        checksRun++;
        boolean ok = actual == expected;
        if (!ok) failures++;
        System.out.println((ok ? "    ok   " : "    FAIL ") + label
                + " — expected " + expected + ", got " + actual);
    }

    private static PlayerKeys testKeys;

    /** One keypair for the whole suite — generating RSA keys is the slow part. */
    private static PlayerKeys testKeys() throws Exception {
        if (testKeys == null) testKeys = PlayerKeys.generate();
        return testKeys;
    }

    /** Appends to the log and applies to state, mirroring what MarketStateHolder.submit does. */
    private static EventApplier.Result apply(EventLog log, MarketState state, Event e)
            throws Exception {
        e.marketId = state.marketId();   // stamped centrally in the real call sites too
        String sig = testKeys().sign(EventCanonical.canonicalPayload(e));
        SequencedEvent se = log.append(e, sig);
        return EventApplier.apply(state, se);
    }

    /** Every log now needs a genesis event before anything else will validate. */
    private static Event.MarketCreated seedMarket(EventLog log, MarketState state)
            throws Exception {
        Event.MarketCreated mc = MarketBootstrap.createMarket(log, ALICE, "test market", testKeys());
        SequencedEvent se = log.readFrom(1).get(0);
        EventApplier.apply(state, se);
        return mc;
    }

    /** Puts currency in a wallet through a real event. Tests that later replay the log
     *  must use this rather than WalletRegistry.setBalance, which replay cannot
     *  reproduce — the balance would silently vanish and orders would be rejected. */
    private static void grant(EventLog log, MarketState state, UUID user, long amount)
            throws Exception {
        Event.WelcomeGrant wg = new Event.WelcomeGrant();
        wg.userId = ALICE;              // the founder issues grants
        wg.targetUserId = user;
        wg.amount = amount;
        apply(log, state, wg);
    }

    /** Registers a user in the log, since nothing they author is valid until then. */
    private static void register(EventLog log, MarketState state, UUID user)
            throws Exception {
        Event.KeyRegistered kr = new Event.KeyRegistered();
        kr.userId = user;
        kr.marketId = state.marketId();
        kr.publicKey = testKeys().publicKeyString();
        SequencedEvent se = log.append(kr, testKeys().sign(EventCanonical.canonicalPayload(kr)));
        EventApplier.apply(state, se);
    }

    /** Applies an event at a given sequence number, with no log behind it. */
    private static EventApplier.Result applyAt(MarketState state, long seq, Event e) {
        SequencedEvent se = new SequencedEvent();
        se.seq = seq;
        se.event = e;
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


}