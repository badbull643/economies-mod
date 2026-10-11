package io.github.badbull643.economiesmod.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a bot decides, once per tick. The algorithm contract from docs/BOTS-SPEC.md.
 *
 * An implementation reads the market through a {@link BotView} and returns the actions for
 * this tick: orders to place and its own orders to cancel. It cannot deposit, withdraw or
 * produce — production is the scheduler's, at the fixed rate — and whatever it returns past
 * its action budget is dropped, not queued.
 *
 * It may keep anything it likes between ticks in {@link BotView#memory()}. It does not have
 * to be deterministic, but should be testable: randomness should come from a seed.
 *
 * Each implementation has an algorithm version, recorded in the bot's BotSpawned event. A
 * changed algorithm is a new version; bots already running keep the one they started with.
 */
public interface BotBrain {

    /** The version recorded when a bot running this is spawned. */
    int version();

    /** This tick's actions, in the order they should be applied. May be empty. */
    List<Event.BotAction> tick(BotView view);

    /** The brain for a bot type and algorithm version, or null if this build has none. */
    static BotBrain forType(String type, int version) {
        if (version != 1) return null;
        if (BotRules.PRODUCER.equals(type)) return new PlaceholderProducer();
        if (BotRules.MARKET_MAKER.equals(type)) return new PlaceholderMarketMaker();
        return null;
    }

    /** The version a newly spawned bot of this type gets. */
    static int currentVersion(String type) {
        return 1;
    }

    // ─────────── what a brain can see ───────────

    /**
     * A bot's view of the market for one tick: read-only, taken under the state's read lock
     * by the scheduler, so everything a brain reads in one tick is one consistent moment.
     */
    final class BotView {
        private final UUID self;
        private final MarketState state;
        private final Map<String, Object> memory;
        private final long tick;

        public BotView(UUID self, MarketState state, Map<String, Object> memory, long tick) {
            this.self = self;
            this.state = state;
            this.memory = memory;
            this.tick = tick;
        }

        public UUID self() { return self; }
        public long tick() { return tick; }
        /** Kept between ticks for this bot only. Starts empty, and empty again after a restart. */
        public Map<String, Object> memory() { return memory; }

        public long credits() { return state.wallets().getBalance(self); }
        /** Items it holds and could sell, not counting what is already on the book. */
        public long stock(String itemId) { return state.itemBalances().getBalance(self, itemId); }
        /** What it has resting on one side of an item's book. */
        public long resting(String itemId, boolean bids) { return state.restingVolume(self, itemId, bids); }

        /** Its own resting orders, every book. */
        public List<Order> myOrders() { return state.restingOrdersOf(self); }

        /** The last price an item traded at, or -1 if it never has. */
        public long lastPrice(String itemId) { return state.trades().lastPrice(itemId); }

        /** Every resting order on one side of an item's book, best first. */
        public List<Order> book(String itemId, boolean bids) {
            OrderBook b = state.peekBook(itemId);
            if (b == null) return Collections.emptyList();
            return bids ? b.restingBids() : b.restingAsks();
        }

        /** Whether an order belongs to a bot of a given type — so a market maker can avoid trading with its own kind. */
        public boolean isBotOfType(Order o, String type) {
            MarketState.BotRecord r = state.bot(o.userID());
            return r != null && r.type.equals(type);
        }

        public int taxBps() { return state.taxBps(); }
    }

    // ─────────── placeholders (version 1) ───────────
    //
    // Simple stand-ins that keep the engine honest until the real algorithms are written: they
    // exercise every kind of action and give a quiet market something on the book. Replace
    // them by adding a new version, not by editing these — bots already running were spawned
    // with version 1 and keep it.

    /** Placeholder base prices in credits, for the basket in BotRules. */
    static long basePrice(String itemId) {
        switch (itemId) {
            case "minecraft:oak_log":    return 2;
            case "minecraft:cobblestone": return 1;
            case "minecraft:coal":       return 3;
            case "minecraft:wheat":      return 2;
            case "minecraft:iron_ingot": return 8;
            default:                     return 1;
        }
    }

    /**
     * Keeps one sell order of 16 per item, priced up as its stock runs down: base price at a
     * full stock, up to twice base when nearly out. Re-prices a resting order whose price is out
     * of date. Never buys.
     */
    final class PlaceholderProducer implements BotBrain {
        public int version() { return 1; }

        public List<Event.BotAction> tick(BotView v) {
            List<Event.BotAction> out = new ArrayList<>();
            Map<String, Order> resting = new HashMap<>();
            for (Order o : v.myOrders()) if (!o.isBid()) resting.put(o.itemID(), o);
            for (String item : BotRules.ITEMS) {
                long start = BotRules.startingItemsEach(BotRules.PRODUCER);
                long held = v.stock(item) + v.resting(item, false);
                long scarcity = Math.max(0, start - held);
                long price = basePrice(item) + basePrice(item) * scarcity / start;
                Order mine = resting.get(item);
                if (mine != null && mine.value() == price) continue;
                if (mine != null) out.add(Event.BotAction.cancel(item, false, mine.orderId()));
                long lot = Math.min(16, v.stock(item) + (mine != null ? mine.volume() : 0));
                if (lot > 0) out.add(Event.BotAction.place(item, false, Math.max(1, price), lot));
            }
            return out;
        }
    }

    /**
     * Keeps a buy at 90% and a sell at 110% of the last traded price (base price if none) on each
     * item, lots of 8, leaning both 5% down when holding more than it started with and 5% up when
     * holding less. Never places an order that would fill against another market maker.
     */
    final class PlaceholderMarketMaker implements BotBrain {
        public int version() { return 1; }

        public List<Event.BotAction> tick(BotView v) {
            List<Event.BotAction> out = new ArrayList<>();
            Map<String, Order> bids = new HashMap<>(), asks = new HashMap<>();
            for (Order o : v.myOrders()) (o.isBid() ? bids : asks).put(o.itemID(), o);
            long start = BotRules.startingItemsEach(BotRules.MARKET_MAKER);
            for (String item : BotRules.ITEMS) {
                long ref = v.lastPrice(item) > 0 ? v.lastPrice(item) : basePrice(item);
                long held = v.stock(item) + v.resting(item, false);
                long lean = held > start ? 95 : held < start ? 105 : 100;
                long bid = Math.max(1, ref * 90 * lean / 10_000);
                long ask = Math.max(bid + 1, (ref * 110 * lean + 9_999) / 10_000);

                if (crossesAnotherMaker(v, item, true, bid)) bid = 0;
                if (crossesAnotherMaker(v, item, false, ask)) ask = 0;

                requote(out, item, true, bid, bids.get(item), bid > 0 && v.credits() >= bid * 8 ? 8 : 0);
                requote(out, item, false, ask, asks.get(item), ask > 0 ? Math.min(8, v.stock(item)) : 0);
            }
            return out;
        }

        private static void requote(List<Event.BotAction> out, String item, boolean isBid,
                                    long price, Order current, long lot) {
            if (current != null && current.value() == price && lot > 0) return;
            if (current != null) out.add(Event.BotAction.cancel(item, isBid, current.orderId()));
            if (lot > 0 && price > 0) out.add(Event.BotAction.place(item, isBid, price, lot));
        }

        /** Would an order at this price fill against a resting order from another market maker? */
        private static boolean crossesAnotherMaker(BotView v, String item, boolean isBid, long price) {
            for (Order o : v.book(item, !isBid)) {
                boolean crosses = isBid ? o.value() <= price : o.value() >= price;
                if (crosses && !o.userID().equals(v.self()) && v.isBotOfType(o, BotRules.MARKET_MAKER)) {
                    return true;
                }
            }
            return false;
        }
    }
}
