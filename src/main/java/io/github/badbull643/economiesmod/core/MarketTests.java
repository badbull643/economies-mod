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
            Path file = Paths.get("./test-log-f9.jsonl");
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

        section("J1: the log stores signatures, so authorship survives a round trip");
        {
            Path file = Paths.get("./test-log-j1.jsonl");
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
            Path file = Paths.get("./test-log-j2.jsonl");
            Files.deleteIfExists(file);
            EventLog log = new EventLog(file);
            MarketState live = new MarketState();
            seedMarket(log, live);
            apply(log, live, deposit(ALICE, IRON, 100));

            // Swap in a valid signature from a different event. Without the signature
            // in the hash, this would go undetected.
            List<String> lines = Files.readAllLines(file);
            String forged = lines.get(1).replaceAll("\"signature\":\"[^\"]*\"",
                    "\"signature\":\"" + testKeys().sign("something else") + "\"");
            lines.set(1, forged);
            Files.write(file, lines);

            EventLog tampered = new EventLog(file);
            check("swapped signature breaks the chain", tampered.verifyChain(), 2);
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
            Path file = Paths.get("./test-log-j4.jsonl");
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
            Path file = Paths.get("./test-log-j6.jsonl");
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
            Path file = Paths.get("./test-log-j7.jsonl");
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
            Path file = Paths.get("./test-log-k1.jsonl");
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
            Path file = Paths.get("./test-log-k2.jsonl");
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
            Path file = Paths.get("./test-log-k3.jsonl");
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
            Path fileX = Paths.get("./test-log-l1x.jsonl");
            Path fileY = Paths.get("./test-log-l1y.jsonl");
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
            Path file = Paths.get("./test-log-l3.jsonl");
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
            Path src = Paths.get("./test-archive-l4.jsonl");
            Path dest = Paths.get("./test-import-l4.jsonl");
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
            Path file = Paths.get("./test-log-l5.jsonl");
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
            Path file = Paths.get("./test-highwater-l6.json");
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
            Path file = Paths.get("./test-log-m1.jsonl");
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
            Path file = Paths.get("./test-log-m2.jsonl");
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
            Path file = Paths.get("./test-log-m3.jsonl");
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
            Path file = Paths.get("./test-log-m6.jsonl");
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

        section("M5: a fast-forward is distinguishable from a fork");
        {
            // The test the host cannot perform for itself: given only "you are ahead of
            // me", is the client a strict extension or a divergent branch? The client
            // answers it by checking its own hash at the host's head.
            Path shared = Paths.get("./test-log-m5-shared.jsonl");
            Path forked = Paths.get("./test-log-m5-fork.jsonl");
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
            Path file = Paths.get("./test-log-l2.jsonl");
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
            Path src = Paths.get("./test-archive-k4.jsonl");
            Path dest = Paths.get("./test-import-k4.jsonl");
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
            Path src = Paths.get("./test-archive-k5.jsonl");
            Files.deleteIfExists(src);
            EventLog log = new EventLog(src);
            MarketState live = new MarketState();
            seedMarket(log, live);
            apply(log, live, deposit(ALICE, IRON, 100));

            List<String> lines = Files.readAllLines(src);
            String tampered = lines.get(1).replace("\"quantity\":100", "\"quantity\":999999");
            SequencedEvent se = EventLog.parseLine(tampered);
            // Re-chain it so nothing but the signature is wrong.
            String rehashed = tampered.replace("\"hash\":\"" + se.hash + "\"",
                    "\"hash\":\"" + EventLog.recomputeHash(se) + "\"");
            lines.set(1, rehashed);
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

        section("K6: import refuses to overwrite existing history");
        {
            Path src = Paths.get("./test-archive-k6.jsonl");
            Path dest = Paths.get("./test-import-k6.jsonl");
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
            Path file = Paths.get("./test-log-j5.jsonl");
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