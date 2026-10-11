package io.github.badbull643.economiesmod.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The bot rules, as every player's game enforces them.
 *
 * Events go through what a host does to a proposal — check the signature against the key
 * EventVerifier picks, validate, append, apply — so a rule that only one of those steps knew
 * about would show up here as an event that should have been refused and was not.
 */
public class BotTest {

    private static int failures = 0;
    private static int checksRun = 0;

    private static void check(String label, boolean ok) {
        checksRun++;
        if (!ok) failures++;
        System.out.println((ok ? "    ok   " : "    FAIL ") + label);
    }

    private static final UUID CREATOR = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID PLAYER  = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String WOOD = "minecraft:oak_log";
    private static final String IRON = "minecraft:iron_ingot";

    private static Path dir;
    private static int n = 0;

    // The market under test, rebuilt by fresh().
    private static EventLog log;
    private static MarketState state;
    private static PlayerKeys creatorKeys;
    private static PlayerKeys serverKeys;     // the bot key
    private static long clock;

    public static void main(String[] args) throws Exception {
        dir = Paths.get("build", "test-scratch");
        Files.createDirectories(dir);

        System.out.println("  [B1: bots are switched on once, by the creator, at creation]");
        {
            fresh(false);
            check("the creator can switch them on",
                    submit(botsEnabled(serverKeys), creatorKeys).accepted);
            check("but only once", !submit(botsEnabled(serverKeys), creatorKeys).accepted);

            fresh(false);
            Event.BotsEnabled byOther = botsEnabled(serverKeys);
            byOther.userId = PLAYER;
            register(PLAYER, PlayerKeys.generate());
            check("not by anyone else", !submit(byOther, serverKeys).accepted);

            fresh(false);
            register(PLAYER, PlayerKeys.generate());
            check("not once a player has joined",
                    !submit(botsEnabled(serverKeys), creatorKeys).accepted);

            fresh(false);
            Event.BotsEnabled junk = botsEnabled(serverKeys);
            junk.botAuthorityPublicKey = "not a key";
            check("not with a key that is not a key", !submit(junk, creatorKeys).accepted);
        }

        System.out.println("  [B2: spawning grants exactly the fixed amounts, up to the cap]");
        {
            fresh(true);
            UUID p = spawn(BotRules.PRODUCER);
            check("a producer spawns", p != null && state.isBot(p));
            check("with exactly its starting credits",
                    state.wallets().getBalance(p) == BotRules.startingCredits(BotRules.PRODUCER));
            boolean items = true;
            for (String item : BotRules.ITEMS) {
                if (state.itemBalances().getBalance(p, item) != BotRules.startingItemsEach(BotRules.PRODUCER)) items = false;
            }
            check("and exactly its starting stock of every item", items);
            check("named Producer #1", "Producer #1".equals(state.botName(p)));

            UUID m = spawn(BotRules.MARKET_MAKER);
            check("a market maker gets its own amounts",
                    state.wallets().getBalance(m) == BotRules.startingCredits(BotRules.MARKET_MAKER)
                            && state.itemBalances().getBalance(m, IRON) == BotRules.startingItemsEach(BotRules.MARKET_MAKER));

            for (int i = 1; i < BotRules.MAX_PER_TYPE; i++) spawn(BotRules.PRODUCER);
            check("eight producers is the most", state.botCount(BotRules.PRODUCER) == BotRules.MAX_PER_TYPE);
            check("a ninth is refused", spawn(BotRules.PRODUCER) == null);
            check("market makers are counted separately", spawn(BotRules.MARKET_MAKER) != null);
        }

        System.out.println("  [B3: only the bot key can act on bots]");
        {
            fresh(true);
            UUID fresh = registerFresh();
            Event.BotSpawned forged = spawned(fresh, BotRules.PRODUCER);
            PlayerKeys someHost = PlayerKeys.generate();
            EventApplier.Result r = submit(forged, someHost);
            check("a spawn signed by any other key is refused", !r.accepted);
            check("as a bad signature", r.reason != null && r.reason.contains("signature"));
            check("even the creator's", !submit(spawned(fresh, BotRules.PRODUCER), creatorKeys).accepted);

            fresh(false);
            UUID other = registerFresh();
            check("a market without bots refuses a spawn whoever signs it",
                    !submit(spawned(other, BotRules.PRODUCER), serverKeys).accepted);
        }

        System.out.println("  [B4: only a new identity with no history becomes a bot]");
        {
            fresh(true);
            UUID granted = registerFresh();
            Event.WelcomeGrant wg = new Event.WelcomeGrant();
            wg.userId = CREATOR;
            wg.targetUserId = granted;
            wg.amount = state.welcomeGrant();
            check("(a player gets a welcome grant)", submit(wg, creatorKeys).accepted);
            check("that player cannot be made a bot", !submit(spawned(granted, BotRules.PRODUCER), serverKeys).accepted);
            check("nor can the creator", !submit(spawned(CREATOR, BotRules.PRODUCER), serverKeys).accepted);
            Event.BotSpawned unknown = spawned(registerFresh(), "banker");
            check("nor anyone as an unknown type", !submit(unknown, serverKeys).accepted);
            UUID b = spawn(BotRules.PRODUCER);
            check("nor a bot twice", !submit(spawned(b, BotRules.PRODUCER), serverKeys).accepted);
        }

        System.out.println("  [B5: bots trade, and do nothing else]");
        {
            fresh(true);
            UUID b = spawn(BotRules.PRODUCER);
            PlayerKeys bk = botKeys.get(b);
            check("no deposits", !submit(deposit(b, WOOD, 5), bk).accepted);
            check("no withdrawals", !submit(withdraw(b, WOOD, 5), bk).accepted);
            check("no single orders", !submit(placeOrder(b, WOOD, false, 3, 5), bk).accepted);
            Event.Stipend st = new Event.Stipend();
            st.userId = b;
            st.amount = 1;
            check("no stipend", !submit(st, bk).accepted);
            Event.WelcomeGrant wg = new Event.WelcomeGrant();
            wg.userId = CREATOR;
            wg.targetUserId = b;
            wg.amount = state.welcomeGrant();
            check("no welcome grant", !submit(wg, creatorKeys).accepted);
        }

        System.out.println("  [B6: production keeps to its rate and its ceiling]");
        {
            fresh(true);
            UUID p = spawn(BotRules.PRODUCER);
            // Bring its wood down below the ceiling by selling to a player first.
            UUID buyer = player(100_000);
            check("(the producer sells 100 wood)",
                    submit(actions(p, Event.BotAction.place(WOOD, false, 1, 100)), botKeys.get(p)).accepted
                            && submit(placeOrder(buyer, WOOD, true, 1, 100), playerKeys.get(buyer)).accepted
                            && state.itemBalances().getBalance(p, WOOD) == 412);
            clock += BotRules.PRODUCTION_INTERVAL_MS;
            check("one lot after one interval", submit(produced(p, WOOD, BotRules.PRODUCTION_LOT), serverKeys).accepted);
            check("but not a second straight after", !submit(produced(p, WOOD, 1), serverKeys).accepted);
            clock += BotRules.PRODUCTION_INTERVAL_MS / 2;
            check("half an interval allows half a lot", submit(produced(p, WOOD, BotRules.PRODUCTION_LOT / 2), serverKeys).accepted);
            clock += BotRules.PRODUCTION_INTERVAL_MS * 100;
            long room = BotRules.PRODUCTION_CEILING - state.itemBalances().getBalance(p, WOOD);
            check("never past the ceiling", !submit(produced(p, WOOD, room + 1), serverKeys).accepted);
            check("up to it is fine", submit(produced(p, WOOD, room), serverKeys).accepted);
            check("an item it does not make is refused", !submit(produced(p, "minecraft:diamond", 1), serverKeys).accepted);
            UUID m = spawn(BotRules.MARKET_MAKER);
            clock += BotRules.PRODUCTION_INTERVAL_MS;
            check("a market maker does not produce", !submit(produced(m, WOOD, 1), serverKeys).accepted);
        }

        System.out.println("  [B7: a batch is applied in order, in one step, skipping what cannot happen]");
        {
            fresh(true);
            UUID m = spawn(BotRules.MARKET_MAKER);
            PlayerKeys mk = botKeys.get(m);
            EventApplier.Result first = submit(actions(m,
                    Event.BotAction.place(IRON, false, 10, 4),
                    Event.BotAction.place(IRON, true, 5, 4)), mk);
            check("two orders from one event", first.accepted && state.restingOrdersOf(m).size() == 2);
            long sellId = BotRules.batchOrderId(lastSeq(), 0), buyId = BotRules.batchOrderId(lastSeq(), 1);
            List<Long> ids = new ArrayList<>();
            for (Order o : state.restingOrdersOf(m)) ids.add(o.orderId());
            check("with distinct ids: the batch's own seq, and the next stride up",
                    ids.contains(sellId) && ids.contains(buyId) && sellId != buyId);

            // Cancel and re-place at a new price: a clean price change.
            submit(actions(m,
                    Event.BotAction.cancel(IRON, false, sellId),
                    Event.BotAction.place(IRON, false, 11, 4)), mk);
            boolean moved = false;
            for (Order o : state.restingOrdersOf(m)) if (!o.isBid() && o.value() == 11) moved = true;
            check("cancel then re-place moves the price", moved && state.restingOrdersOf(m).size() == 2);

            // An order it cannot afford in the middle: skipped, the rest still apply.
            long credits = state.wallets().getBalance(m);
            submit(actions(m,
                    Event.BotAction.place(WOOD, false, 3, 2),
                    Event.BotAction.place(IRON, true, credits * 10, 1),
                    Event.BotAction.place(WOOD, false, 4, 2)), mk);
            long woodAsks = state.restingVolume(m, WOOD, false);
            check("an unaffordable action is skipped and the others land", woodAsks == 4);
            check("a cancel of somebody else's order is skipped", submit(actions(m,
                    Event.BotAction.cancel(IRON, true, 999_999)), mk).accepted);

            Event.BotAction[] nine = new Event.BotAction[BotRules.MAX_ACTIONS_PER_BATCH + 1];
            for (int i = 0; i < nine.length; i++) nine[i] = Event.BotAction.place(WOOD, false, 50 + i, 1);
            check("more than eight actions is refused", !submit(actions(m, nine), mk).accepted);
            check("an empty batch is refused", !submit(actions(m), mk).accepted);
            check("a batch signed by the bot key, not the bot, is refused",
                    !submit(actions(m, Event.BotAction.place(WOOD, false, 9, 1)), serverKeys).accepted);
        }

        check("a batch's first order shows as its seq", BotRules.displayOrderId(BotRules.batchOrderId(9, 0)).equals("9"));
        check("its later ones as seq.position", BotRules.displayOrderId(BotRules.batchOrderId(9, 1)).equals("9.1")
                && BotRules.displayOrderId(BotRules.batchOrderId(1234, 7)).equals("1234.7"));
        check("a player's order id shows as itself", BotRules.displayOrderId(20).equals("20"));

        System.out.println("  [B8: bots pay the trading fee, not the listing fee]");
        {
            fresh(true);
            setPolicy(100, 2, 1, 0, 0);
            UUID m = spawn(BotRules.MARKET_MAKER);
            long before = state.wallets().getBalance(m);
            Event.BotAction[] many = new Event.BotAction[5];
            for (int i = 0; i < many.length; i++) many[i] = Event.BotAction.place(WOOD, false, 20 + i, 1);
            submit(actions(m, many), botKeys.get(m));
            check("five orders, far past one free one, cost a bot nothing",
                    state.wallets().getBalance(m) == before && state.restingOrdersOf(m).size() == 5);
            UUID buyer = player(1_000);
            submit(placeOrder(buyer, WOOD, true, 20, 1), playerKeys.get(buyer));
            long tax = MarketState.taxOn(20, 100);
            check("its sale paid the trading fee", state.wallets().getBalance(m) == before + 20 - tax);
            check("(and the player paid their listing fee)", state.wallets().getBalance(buyer) == 1_000 - 20 - 2);
        }

        System.out.println("  [B9: bots are not counted for the stipend]");
        {
            fresh(true);
            for (int i = 0; i < 4; i++) spawn(BotRules.PRODUCER);
            check("claimants are the players only", state.stipendClaimants() == state.registeredCount() - 4);
            // A stipend the creator alone can be paid, which four bots would have priced out.
            long every = MarketState.DEFAULT_STIPEND_EVERY_FILLS;
            long amount = every * 2 - 1;     // listing fee 2: collected = 2 * every > amount * 1
            check("a stipend affordable for the players is allowed", setPolicy(0, 2, 0, amount, every));
        }

        System.out.println("  [B10: pausing takes a bot's orders off the book]");
        {
            fresh(true);
            UUID m = spawn(BotRules.MARKET_MAKER);
            submit(actions(m, Event.BotAction.place(IRON, false, 10, 4), Event.BotAction.place(WOOD, true, 2, 10)),
                    botKeys.get(m));
            long credits = state.wallets().getBalance(m), iron = state.itemBalances().getBalance(m, IRON);
            check("paused", submit(paused(m), serverKeys).accepted);
            check("its orders are gone", state.restingOrdersOf(m).isEmpty());
            check("and what they reserved is back",
                    state.wallets().getBalance(m) == credits + 20 && state.itemBalances().getBalance(m, IRON) == iron + 4);
            check("a paused bot cannot act", !submit(actions(m, Event.BotAction.place(IRON, false, 10, 1)), botKeys.get(m)).accepted);
            check("or be paused again", !submit(paused(m), serverKeys).accepted);
            check("resumed", submit(resumed(m), serverKeys).accepted);
            check("and acts again", submit(actions(m, Event.BotAction.place(IRON, false, 10, 1)), botKeys.get(m)).accepted);

            UUID p = spawn(BotRules.PRODUCER);
            UUID buyer = player(100_000);
            submit(actions(p, Event.BotAction.place(WOOD, false, 1, 100)), botKeys.get(p));
            submit(placeOrder(buyer, WOOD, true, 1, 100), playerKeys.get(buyer));
            submit(paused(p), serverKeys);
            clock += BotRules.PRODUCTION_INTERVAL_MS * 3;
            submit(resumed(p), serverKeys);
            check("a producer earns nothing for the time it was paused",
                    !submit(produced(p, WOOD, 1), serverKeys).accepted);
            clock += BotRules.PRODUCTION_INTERVAL_MS;
            check("and its clock runs again from the resume",
                    submit(produced(p, WOOD, BotRules.PRODUCTION_LOT), serverKeys).accepted
                            && !submit(produced(p, WOOD, 1), serverKeys).accepted);
        }

        System.out.println("  [B11: replay and snapshots agree with the live market]");
        {
            fresh(true);
            UUID p = spawn(BotRules.PRODUCER);
            UUID m = spawn(BotRules.MARKET_MAKER);
            submit(actions(m, Event.BotAction.place(IRON, false, 10, 4)), botKeys.get(m));
            // A trade between the two, so profit moves: both sides' money and goods come from
            // logged events, which a replay and a snapshot both have to reproduce.
            submit(actions(m, Event.BotAction.place(WOOD, true, 12, 3)), botKeys.get(m));
            submit(actions(p, Event.BotAction.place(WOOD, false, 12, 3)), botKeys.get(p));
            clock += BotRules.PRODUCTION_INTERVAL_MS;
            submit(paused(p), serverKeys);
            long before = state.version();
            submit(actions(m, Event.BotAction.place(IRON, false, 11, 1)), botKeys.get(m));
            check("every applied event moves the version screens cache against", state.version() == before + 1);
            MarketState replayed = EventApplier.replay(new EventLog(log.file()));
            check("a replay finds the same bots", sameBots(state, replayed));
            check("and the same profit and loss",
                    state.realizedPnl(p) == 36 && replayed.realizedPnl(p) == state.realizedPnl(p)
                            && samePositions(state, replayed, m) && samePositions(state, replayed, p));
            MarketSnapshot.save(log, state, log.lastSeq(), log.lastHash());
            MarketSnapshot.Restored restored = MarketSnapshot.loadIfValid(new EventLog(log.file()));
            check("a snapshot keeps them", restored != null && sameBots(state, restored.state));
            check("and their balances",
                    restored != null && restored.state.wallets().getBalance(m) == state.wallets().getBalance(m));
            check("and still exempts them from the listing fee",
                    restored != null && restored.state.isBot(m) && restored.state.botAuthorityKey() != null);
            check("and the profit and loss",
                    restored != null && restored.state.realizedPnl(p) == state.realizedPnl(p)
                            && samePositions(state, restored.state, m) && samePositions(state, restored.state, p));
        }

        System.out.println("  [B12: version 2 bots: one item each, their own grants, and using goods up]");
        {
            fresh(true);
            check("a version 2 producer needs an item to make", spawnV2(BotRules.PRODUCER, null) == null);
            check("one from the basket", spawnV2(BotRules.PRODUCER, "minecraft:diamond") == null);
            check("a market maker has none", spawnV2(BotRules.MARKET_MAKER, WOOD) == null);
            Event.BotSpawned future = spawned(registerFresh(), BotRules.MARKET_MAKER);
            future.algorithmVersion = BotRules.LATEST_VERSION + 1;
            check("a version this build does not know is refused", !submit(future, serverKeys).accepted);

            UUID p = spawnV2(BotRules.PRODUCER, IRON);
            check("a producer spawns making iron", p != null && IRON.equals(state.bot(p).product));
            check("with 1,000 credits", state.wallets().getBalance(p) == 1_000);
            check("256 iron and nothing else",
                    state.itemBalances().getBalance(p, IRON) == 256 && state.itemBalances().getBalance(p, WOOD) == 0);
            UUID m = spawnV2(BotRules.MARKET_MAKER, null);
            check("a market maker gets 1,500 credits and 32 of each",
                    state.wallets().getBalance(m) == 1_500 && state.itemBalances().getBalance(m, WOOD) == 32
                            && state.itemBalances().getBalance(m, IRON) == 32);

            clock += BotRules.PRODUCTION_INTERVAL_MS * 100;
            check("it produces only its own item", !submit(produced(p, WOOD, 1), serverKeys).accepted);
            check("and its ceiling is 256, already reached", !submit(produced(p, IRON, 1), serverKeys).accepted);
            submit(actions(p, Event.BotAction.consume(IRON, 50)), botKeys.get(p));
            check("using goods up takes them out of the market", state.itemBalances().getBalance(p, IRON) == 206);
            check("after which it can produce back to 256", submit(produced(p, IRON, 50), serverKeys).accepted
                    && !submit(produced(p, IRON, 1), serverKeys).accepted);
            submit(actions(m, Event.BotAction.consume(WOOD, 1_000)), botKeys.get(m));
            check("it can use up no more than it holds", state.itemBalances().getBalance(m, WOOD) == 0);
            check("a batch to use up nothing is refused",
                    !submit(actions(m, Event.BotAction.consume(IRON, 0)), botKeys.get(m)).accepted);

            // The maker uses up the iron it was granted (which cost it nothing), then buys 10
            // from the producer at 12 and uses up 4 of those: an expense of 48.
            submit(actions(m, Event.BotAction.consume(IRON, 32)), botKeys.get(m));
            submit(actions(p, Event.BotAction.place(IRON, false, 12, 10)), botKeys.get(p));
            submit(actions(m, Event.BotAction.place(IRON, true, 12, 10)), botKeys.get(m));
            long before = state.realizedPnl(m);
            submit(actions(m, Event.BotAction.consume(IRON, 4)), botKeys.get(m));
            check("using up bought goods is an expense at their cost", state.realizedPnl(m) == before - 48);

            Event.BotSpawned v1 = spawned(UUID.randomUUID(), BotRules.PRODUCER);
            Event.BotSpawned v2 = spawned(v1.userId, BotRules.PRODUCER);
            v2.algorithmVersion = 2;
            v2.productItem = IRON;
            String v1Payload = EventCanonical.canonicalPayload(v1);
            check("a version 1 spawn signs what it always did, with nothing for the item",
                    v1Payload.endsWith("|producer|1"));
            check("a version 2 spawn signs its item", EventCanonical.canonicalPayload(v2).endsWith("|producer|2|" + IRON));
            MarketState replayed = EventApplier.replay(new EventLog(log.file()));
            check("a replay agrees about who makes what", IRON.equals(replayed.bot(p).product)
                    && replayed.itemBalances().getBalance(p, IRON) == state.itemBalances().getBalance(p, IRON));
        }

        System.out.println("  [B13: market makers quote items players have established, carefully]");
        {
            fresh(true);
            UUID m = spawnV2(BotRules.MARKET_MAKER, null);
            String diamond = "minecraft:diamond";
            UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
            long seq = 10_000;
            for (int i = 0; i < 4; i++) state.recordTrades(seq++, clock, java.util.Collections.singletonList(new Fill(a, b, 1, 100, diamond)));
            check("four trades are not a market yet", ordersFor(m, diamond).isEmpty());
            state.recordTrades(seq++, clock, java.util.Collections.singletonList(new Fill(b, a, 1, 100, diamond)));
            check("nor five between only two players", ordersFor(m, diamond).isEmpty());
            state.recordTrades(seq++, clock, java.util.Collections.singletonList(new Fill(c, a, 1, 100, diamond)));
            List<Event.BotAction> quote = ordersFor(m, diamond);
            check("five or more between three players are", quote.size() == 1 && quote.get(0).isBid);
            check("it bids under the market price, not over it, holding none",
                    !quote.isEmpty() && quote.get(0).price <= 100 && quote.get(0).price >= 90);
            check("in a small lot, by value (about 80 credits' worth)", !quote.isEmpty() && quote.get(0).volume == 1);
            check("and offers none, having none", quote.stream().noneMatch(x -> !x.isBid));
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL " + checksRun + " CHECKS PASSED");
        } else {
            System.out.println(failures + " of " + checksRun + " checks FAILED");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    // ─────────── a market to test in ───────────

    private static final java.util.Map<UUID, PlayerKeys> botKeys = new java.util.HashMap<>();
    private static final java.util.Map<UUID, PlayerKeys> playerKeys = new java.util.HashMap<>();

    /** A new market, created the normal way; with bots switched on straight after, if asked. */
    private static void fresh(boolean withBots) throws Exception {
        Path p = dir.resolve("bots-" + (++n) + ".jsonl");
        for (Path f : EventLog.filesFor(p)) Files.deleteIfExists(f);
        Files.deleteIfExists(p.resolveSibling(p.getFileName() + ".snapshot.json"));
        log = new EventLog(p);
        creatorKeys = PlayerKeys.generate();
        serverKeys = PlayerKeys.generate();
        botKeys.clear();
        playerKeys.clear();
        clock = 1_000_000_000_000L;
        MarketBootstrap.createMarket(log, CREATOR, "bot test market", creatorKeys, 100, 0, 0);
        state = EventApplier.replay(log);
        if (withBots && !submit(botsEnabled(serverKeys), creatorKeys).accepted) {
            throw new IllegalStateException("could not switch bots on");
        }
    }

    /** What a host does with a proposal: signature, validate, append, apply. */
    private static EventApplier.Result submit(Event e, PlayerKeys signer) throws Exception {
        e.marketId = state.marketId();
        e.clientEventId = UUID.randomUUID().toString();
        e.timestamp = clock;
        String sig = signer.sign(EventCanonical.canonicalPayload(e));
        String why = EventVerifier.verify(state, e, sig);
        if (why != null) return EventApplier.Result.reject(why);
        SequencedEvent probe = new SequencedEvent();
        probe.seq = log.lastSeq() + 1;
        probe.event = e;
        EventApplier.Result check = EventApplier.validate(state, probe);
        if (!check.accepted) return check;
        SequencedEvent se = log.append(e, sig);
        EventApplier.Result r = EventApplier.apply(state, se);
        if (!r.accepted) throw new IllegalStateException("validate said yes and apply said " + r.reason);
        return r;
    }

    private static long lastSeq() { return log.lastSeq(); }

    private static boolean samePositions(MarketState a, MarketState b, UUID who) {
        java.util.Map<String, long[]> pa = a.positionsOf(who), pb = b.positionsOf(who);
        if (!pa.keySet().equals(pb.keySet())) return false;
        for (String k : pa.keySet()) if (!java.util.Arrays.equals(pa.get(k), pb.get(k))) return false;
        return true;
    }

    private static Event.BotsEnabled botsEnabled(PlayerKeys authority) {
        Event.BotsEnabled be = new Event.BotsEnabled();
        be.userId = CREATOR;
        be.botAuthorityPublicKey = authority.publicKeyString();
        return be;
    }

    private static void register(UUID who, PlayerKeys keys) throws Exception {
        Event.KeyRegistered kr = new Event.KeyRegistered();
        kr.userId = who;
        kr.publicKey = keys.publicKeyString();
        if (!submit(kr, keys).accepted) throw new IllegalStateException("could not register " + who);
    }

    private static UUID registerFresh() throws Exception {
        UUID id = UUID.randomUUID();
        PlayerKeys k = PlayerKeys.generate();
        register(id, k);
        botKeys.put(id, k);
        return id;
    }

    private static Event.BotSpawned spawned(UUID who, String type) {
        Event.BotSpawned bs = new Event.BotSpawned();
        bs.userId = who;
        bs.botType = type;
        bs.algorithmVersion = 1;
        return bs;
    }

    /** Registers a new identity and spawns it as a bot; null if the spawn is refused. */
    /** What a version 2 market maker would place for one item on its next decision. */
    private static List<Event.BotAction> ordersFor(UUID maker, String item) {
        List<Event.BotAction> out = new ArrayList<>();
        BotBrain.BotView view = new BotBrain.BotView(maker, state, new java.util.HashMap<>(), 0, clock);
        for (Event.BotAction a : new BotBrain.MarketMaker().tick(view)) {
            if (item.equals(a.itemId) && BotRules.ACTION_PLACE.equals(a.kind)) out.add(a);
        }
        return out;
    }

    /** Spawns a version 2 bot, a producer making the item given; null if the spawn is refused. */
    private static UUID spawnV2(String type, String product) throws Exception {
        UUID id = registerFresh();
        Event.BotSpawned bs = spawned(id, type);
        bs.algorithmVersion = 2;
        bs.productItem = product;
        return submit(bs, serverKeys).accepted ? id : null;
    }

    private static UUID spawn(String type) throws Exception {
        UUID id = registerFresh();
        return submit(spawned(id, type), serverKeys).accepted ? id : null;
    }

    /** A registered player with this many credits (granted by the creator for the test). */
    private static UUID player(long credits) throws Exception {
        UUID id = UUID.randomUUID();
        PlayerKeys k = PlayerKeys.generate();
        register(id, k);
        playerKeys.put(id, k);
        state.wallets().adjust(id, credits);   // test setup, not an event: only balances matter here
        return id;
    }

    private static Event.BotProduced produced(UUID bot, String item, long amount) {
        Event.BotProduced bp = new Event.BotProduced();
        bp.userId = bot;
        bp.itemId = item;
        bp.amount = amount;
        return bp;
    }

    private static Event.BotPaused paused(UUID bot) {
        Event.BotPaused e = new Event.BotPaused();
        e.userId = bot;
        return e;
    }

    private static Event.BotResumed resumed(UUID bot) {
        Event.BotResumed e = new Event.BotResumed();
        e.userId = bot;
        return e;
    }

    private static Event.BotActions actions(UUID bot, Event.BotAction... list) {
        Event.BotActions ba = new Event.BotActions();
        ba.userId = bot;
        ba.actions = new ArrayList<>(Arrays.asList(list));
        return ba;
    }

    private static Event.Deposit deposit(UUID who, String item, long qty) {
        Event.Deposit d = new Event.Deposit();
        d.userId = who;
        d.itemId = item;
        d.quantity = qty;
        return d;
    }

    private static Event.Withdraw withdraw(UUID who, String item, long qty) {
        Event.Withdraw w = new Event.Withdraw();
        w.userId = who;
        w.itemId = item;
        w.quantity = qty;
        return w;
    }

    private static Event.PlaceOrder placeOrder(UUID who, String item, boolean bid, long price, long volume) {
        Event.PlaceOrder p = new Event.PlaceOrder();
        p.userId = who;
        p.itemId = item;
        p.isBid = bid;
        p.price = price;
        p.volume = volume;
        return p;
    }

    /** A MarketPolicy from the creator. Returns whether it was accepted. */
    private static boolean setPolicy(int taxBps, long listingFee, int freeOrders,
                                     long stipend, long every) throws Exception {
        Event.MarketPolicy mp = new Event.MarketPolicy();
        mp.userId = CREATOR;
        mp.taxBps = taxBps;
        mp.grantAmount = state.welcomeGrant();
        mp.listingFee = listingFee;
        mp.listingFreeOrders = freeOrders;
        mp.stipendAmount = stipend;
        mp.stipendEveryFills = every;
        return submit(mp, creatorKeys).accepted;
    }

    private static boolean sameBots(MarketState a, MarketState b) {
        if (a.bots().size() != b.bots().size()) return false;
        if (a.botAuthorityKey() == null || !a.botAuthorityKey().equals(b.botAuthorityKey())) return false;
        for (java.util.Map.Entry<UUID, MarketState.BotRecord> e : a.bots().entrySet()) {
            MarketState.BotRecord x = e.getValue(), y = b.bot(e.getKey());
            if (y == null || !x.type.equals(y.type) || x.number != y.number
                    || x.paused() != y.paused() || !x.lastProducedAtAll().equals(y.lastProducedAtAll())) {
                return false;
            }
        }
        return true;
    }
}
