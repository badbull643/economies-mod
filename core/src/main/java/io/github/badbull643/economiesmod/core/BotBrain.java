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
        boolean producer = BotRules.PRODUCER.equals(type), maker = BotRules.MARKET_MAKER.equals(type);
        if (version == 1) return producer ? new PlaceholderProducer() : maker ? new PlaceholderMarketMaker() : null;
        if (version == 2) return producer ? new Producer() : maker ? new MarketMaker() : null;
        return null;
    }

    /** The version a newly spawned bot of this type gets. */
    static int currentVersion(String type) {
        return BotRules.LATEST_VERSION;
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
        private final long now;

        public BotView(UUID self, MarketState state, Map<String, Object> memory, long tick, long now) {
            this.self = self;
            this.state = state;
            this.memory = memory;
            this.tick = tick;
            this.now = now;
        }

        public UUID self() { return self; }
        public long tick() { return tick; }
        /** The scheduler's clock, in milliseconds. A brain's sense of time passing comes from here. */
        public long now() { return now; }

        /** The item this bot makes, if it is a version 2 producer; null otherwise. */
        public String product() {
            MarketState.BotRecord r = state.bot(self);
            return r == null ? null : r.product;
        }

        /**
         * Items outside the basket that players have established a market in: at least
         * minTrades of the last twenty trades, between at least minTraders different
         * players (bots not counted), busiest first, at most max of them.
         *
         * The bar is there because these have no seed price to lean on: a market maker
         * quoting one trusts its recent trades entirely, and a price two accounts traded
         * between themselves is not a price.
         */
        public List<String> establishedOutsideBasket(int minTrades, int minTraders, int max) {
            List<String> out = new ArrayList<>();
            Map<String, Long> volume = new HashMap<>();
            for (String id : state.trades().tradedItems()) {
                if (BotRules.ITEMS.contains(id)) continue;
                List<Trade> recent = state.trades().recentFor(id, 20);
                if (recent.size() < minTrades) continue;
                java.util.Set<UUID> traders = new java.util.HashSet<>();
                long units = 0;
                for (Trade t : recent) {
                    if (!state.isBot(t.buyerId)) traders.add(t.buyerId);
                    if (!state.isBot(t.sellerId)) traders.add(t.sellerId);
                    units += t.quantity;
                }
                if (traders.size() < minTraders) continue;
                out.add(id);
                volume.put(id, units);
            }
            out.sort((a, b) -> Long.compare(volume.get(b), volume.get(a)));
            return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
        }

        /** The market price players see: recent trades averaged by quantity. -1 if it never traded. */
        public long marketPrice(String itemId) {
            return MarketView.marketPrice(state.trades().recentFor(itemId));
        }
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

    // ─────────── version 2: the default algorithms ───────────
    //
    // An economy rather than a pair of quote machines. Each producer makes one basket item
    // and uses up the others as upkeep, so it sells what it makes and buys what it needs:
    // credits go round instead of settling in whoever sells most, goods are made and used
    // up instead of piling up, and producers trade with each other for a reason. Players sit
    // in the middle of all of it. Market makers quote both sides close to the market price
    // and earn the gap, leaning against their own stock so they never become the market.
    //
    // Prices come from pressure, not from a table: a producer sells cheaper when it has
    // plenty and bids more the longer it goes short; a market maker shades its quotes by
    // what it holds. The base prices below only seed a market that has never traded, and
    // pull gently on one that has (a third of the weight), so a price can drift with real
    // supply and demand without collapsing to 1 the moment one side floods it — which is
    // what version 1 did.

    /**
     * Seed prices, five times version 1's. Prices are whole credits, and at 1 or 2 a market
     * maker cannot quote any gap that is not 50 or 100 per cent; at these, five per cent is
     * still at least one credit on everything but cobblestone.
     */
    static long basePriceV2(String itemId) {
        switch (itemId) {
            case "minecraft:oak_log":     return 10;
            case "minecraft:cobblestone": return 5;
            case "minecraft:coal":        return 15;
            case "minecraft:wheat":       return 10;
            case "minecraft:iron_ingot":  return 40;
            default:                      return 5;
        }
    }

    /** What a version 2 bot takes an item to be worth: mostly the market's price, a third the seed. */
    static double anchor(BotView v, String itemId) {
        long market = v.marketPrice(itemId);
        long base = basePriceV2(itemId);
        return market <= 0 ? base : market * 0.7 + base * 0.3;
    }

    /** Keeps an order at a price, replacing it when the price moved, adding or withdrawing it as lot says. */
    static void requote(List<Event.BotAction> out, String item, boolean isBid, long price, Order current, long lot) {
        if (current != null && current.value() == price && lot > 0) return;
        if (current != null) out.add(Event.BotAction.cancel(item, isBid, current.orderId()));
        if (lot > 0 && price > 0) out.add(Event.BotAction.place(item, isBid, price, lot));
    }

    /** The basket, starting at a different item each tick, so a capped batch reaches all of them in turn. */
    static List<String> rotated(long tick) {
        return rotated(BotRules.ITEMS, tick);
    }

    static List<String> rotated(List<String> list, long tick) {
        List<String> items = new ArrayList<>(list);
        if (!items.isEmpty()) Collections.rotate(items, (int) (tick % items.size()));
        return items;
    }

    final class Producer implements BotBrain {
        /** Credits it never spends below. */
        static final long CASH_RESERVE = 200;
        /** Above this it buys a little more eagerly: it has more than it needs. */
        static final long CASH_TARGET = 1_000;
        /**
         * How much it uses up of each other basket item an hour, in credits' worth rather than
         * in units: about one iron or eight cobblestone. A fixed four of each, the first way
         * round, cost a producer 160 credits an hour in iron and earned a cobblestone producer
         * 20 from each customer, so the producers of cheap things went broke within the first
         * playtest and stopped buying. Measured in value, every producer's item meets the same
         * demand in credits, whatever it costs.
         */
        static final double UPKEEP_VALUE_PER_HOUR = 40;
        /** How much of each input it tries to keep in stock, in credits' worth, at its base rate. */
        static final double INPUT_TARGET_VALUE = 160;
        static final long SELL_LOT = 16;
        static final long BUY_LOT = 8;
        /** The most its upkeep rises to, as a multiple of the base rate. */
        static final double MAX_APPETITE = 8;

        /**
         * How much more than its base upkeep it uses: one, plus a step for each cash target's
         * worth it holds above the target. Without this, upkeep was met once it held sixteen
         * of each input and it stopped buying, while what it made went on selling — in the
         * first comparison run producers ended up holding four fifths of every credit in the
         * market. Spending in proportion to the surplus sends money back out as fast as it
         * comes in, to whoever sells the inputs, which is mostly players.
         */
        static double appetite(long credits) {
            return Math.min(MAX_APPETITE, 1 + Math.max(0, credits - CASH_TARGET) / (double) CASH_TARGET);
        }

        public int version() { return 2; }

        public List<Event.BotAction> tick(BotView v) {
            String product = v.product();
            if (product == null) return Collections.emptyList();
            Map<String, Object> mem = v.memory();
            long now = v.now();
            double appetite = appetite(v.credits());
            Object lastSeen = mem.get("lastTick");
            long elapsed = lastSeen == null ? 0 : Math.min(3_600_000L, Math.max(0, now - (Long) lastSeen));
            mem.put("lastTick", now);

            Map<String, Order> asks = new HashMap<>(), bids = new HashMap<>();
            for (Order o : v.myOrders()) (o.isBid() ? bids : asks).put(o.itemID(), o);
            List<Event.BotAction> use = new ArrayList<>(), sell = new ArrayList<>(), buy = new ArrayList<>();

            // Upkeep: what it needs of each input builds up with time, and is used from stock.
            for (String input : BotRules.ITEMS) {
                if (input.equals(product)) continue;
                double need = mem.get("need:" + input) == null ? 0 : (Double) mem.get("need:" + input);
                double value = Math.max(1, anchor(v, input));
                long inputTarget = Math.max(2, Math.round(INPUT_TARGET_VALUE * appetite / value));
                need += UPKEEP_VALUE_PER_HOUR / value * appetite * elapsed / 3_600_000.0;
                long take = Math.min((long) need, v.stock(input));
                if (take > 0) {
                    use.add(Event.BotAction.consume(input, take));
                    need -= take;
                }
                mem.put("need:" + input, Math.min(need, inputTarget));
            }

            // Sell what it makes: cheaper the fuller it is, dearer as it runs low.
            Order ask = asks.get(product);
            long held = v.stock(product) + v.resting(product, false);
            double full = Math.min(1.0, held / (double) BotRules.productionCeiling(2));
            // Never below half its seed price, standing in for what making it costs: a flood of
            // the same goods from players can push the price lower between them, but the bot
            // does not race them down to 1.
            long floor = (basePriceV2(product) + 1) / 2;
            long sellPrice = Math.max(floor, Math.round(anchor(v, product) * (1.15 - 0.25 * full)));
            long sellLot = Math.min(SELL_LOT, v.stock(product) + (ask != null ? ask.volume() : 0));
            requote(sell, product, false, sellPrice, ask, sellLot);

            // Buy what it uses: more eagerly the less it has, never below its reserve.
            long spendable = v.credits() - CASH_RESERVE;
            for (String input : rotated(v.tick())) {
                if (input.equals(product)) continue;
                Order bid = bids.get(input);
                long have = v.stock(input);
                long inputTarget = Math.max(2, Math.round(INPUT_TARGET_VALUE * appetite / Math.max(1, anchor(v, input))));
                long lot = Math.min(Math.round(BUY_LOT * Math.min(2, appetite)), Math.max(0, inputTarget - have));
                double short_ = 1 - Math.min(1.0, have / (double) inputTarget);
                double eager = 0.95 + 0.25 * short_ + (v.credits() > CASH_TARGET ? 0.05 : 0);
                long price = Math.max(1, Math.round(anchor(v, input) * eager));
                if (bid != null) spendable += bid.value() * bid.volume();   // reserved already
                if (price * lot > spendable) lot = Math.max(0, spendable / price);
                requote(buy, input, true, price, bid, lot);
                spendable -= price * lot;
            }

            List<Event.BotAction> out = new ArrayList<>(use);
            out.addAll(sell);
            out.addAll(buy);
            return out;
        }
    }

    final class MarketMaker implements BotBrain {
        /** What it aims to hold of each item, and the most it will. */
        public static final long TARGET = 32;
        public static final long CAP = 96;
        static final long LOT = 8;
        static final long CASH_RESERVE = 200;
        /**
         * Price support: it never bids below this share of an item's seed price, while it has
         * room and money. A soft floor under the market — when players flood an item, the bots
         * soak up the cheap ones rather than the price running down to 1, at the cost of
         * holding more goods and less money while it lasts. Set to 0 to switch it off; it is
         * this algorithm's choice, not a market rule.
         */
        static final double SUPPORT = 0.5;

        // Items outside the basket, which players have established a market in. No seed
        // price and no support: it quotes around their recent trades alone, and holds only a
        // small amount of each by value, so a price somebody arranged costs it little.
        static final int EXTRA_MIN_TRADES = 5;
        static final int EXTRA_MIN_TRADERS = 3;
        static final int EXTRA_MAX_ITEMS = 8;
        static final double EXTRA_TARGET_VALUE = 160;
        static final double EXTRA_CAP_VALUE = 480;
        static final double EXTRA_LOT_VALUE = 80;

        public int version() { return 2; }

        public List<Event.BotAction> tick(BotView v) {
            Map<String, Order> bids = new HashMap<>(), asks = new HashMap<>();
            for (Order o : v.myOrders()) (o.isBid() ? bids : asks).put(o.itemID(), o);
            List<Event.BotAction> out = new ArrayList<>();
            long spendable = v.credits() - CASH_RESERVE;
            for (Order o : bids.values()) spendable += o.value() * o.volume();

            List<String> items = new ArrayList<>(BotRules.ITEMS);
            items.addAll(v.establishedOutsideBasket(EXTRA_MIN_TRADES, EXTRA_MIN_TRADERS, EXTRA_MAX_ITEMS));
            for (String item : rotated(items, v.tick())) {
                boolean basket = BotRules.ITEMS.contains(item);
                double ref = basket ? anchor(v, item) : v.marketPrice(item);
                if (ref <= 0) continue;
                long target = basket ? TARGET : Math.max(1, Math.round(EXTRA_TARGET_VALUE / ref));
                long cap = basket ? CAP : Math.max(2, Math.round(EXTRA_CAP_VALUE / ref));
                long lotSize = basket ? LOT : Math.max(1, Math.min(LOT, Math.round(EXTRA_LOT_VALUE / ref)));
                long stock = v.stock(item);
                long held = stock + v.resting(item, false);

                // Lean against its own stock: holding more than it aims for, it quotes lower
                // to sell it on; holding less, higher to buy some back.
                double lean = Math.max(-1, Math.min(2, (held - target) / (double) target));
                // Outside the basket it only leans down, when it holds too much: leaning up to
                // buy some back would mean paying over the market for something it never held.
                if (!basket) lean = Math.max(0, lean);
                double centre = Math.max(ref * 0.75, Math.min(ref * 1.25, ref * (1 - 0.12 * lean)));
                long half = Math.max(1, Math.round(ref * 0.05));
                long last = v.lastPrice(item), market = v.marketPrice(item);
                if (last > 0 && market > 0 && Math.abs(last - market) > market * 0.25) half *= 2;   // swinging: stand back

                long support = basket ? (long) Math.ceil(basePriceV2(item) * SUPPORT) : 1;
                long bid = Math.max(Math.max(1, support), (long) Math.floor(centre - half));
                long ask = Math.max(bid + 1, (long) Math.ceil(centre + half));
                if (crossesAnotherMaker(v, item, true, bid)) bid = 0;
                if (crossesAnotherMaker(v, item, false, ask)) ask = 0;

                Order myBid = bids.get(item), myAsk = asks.get(item);
                long bidLot = held >= cap || bid <= 0 ? 0 : lotSize;
                if (bidLot > 0 && bid * bidLot > spendable) bidLot = 0;
                long askLot = ask <= 0 ? 0 : Math.min(lotSize, stock + (myAsk != null ? myAsk.volume() : 0));
                requote(out, item, true, bid, myBid, bidLot);
                requote(out, item, false, ask, myAsk, askLot);
                spendable -= bid * bidLot;
            }
            return out;
        }

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
     * holding less. Stops buying an item once it holds MAX_HOLDING_MULTIPLE times what it
     * started with, so it passes stock on rather than piling it up. Never places an order that
     * would fill against another market maker.
     */
    final class PlaceholderMarketMaker implements BotBrain {
        public int version() { return 1; }

        /**
         * The most of an item it will hold, as a multiple of its starting stock, before it stops
         * bidding for more.
         *
         * Without it the first playtest's market makers kept buying cobblestone from the
         * producers at 1 — the floor, where its bid and their ask meet — until each held about
         * 570, and were paying credits to bots that never spend them: roughly 600 each in
         * twenty minutes, a couple of hours from running dry. Buying from producers is fine;
         * buying without limit is not.
         */
        public static final long MAX_HOLDING_MULTIPLE = 2;

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

                if (held >= start * MAX_HOLDING_MULTIPLE) bid = 0;
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
