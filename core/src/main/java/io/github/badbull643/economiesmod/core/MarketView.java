package io.github.badbull643.economiesmod.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What the market screen shows, worked out from the market and nothing else.
 *
 * The screen exists in five copies, one per Minecraft version, and the copies only differ
 * in how they draw. Everything a player reads off it — which items are on offer and at
 * what, how the book stands at each price, what an order would cost and whether it would
 * fill, their own open orders, their own trades — is decided here once, so the copies
 * cannot drift and the answers can be tested without a game.
 *
 * Read-only. Call with the state's read lock held if the state can change underneath
 * (it can, on any client), so one screen frame describes one moment.
 */
public final class MarketView {
    private MarketView() {}

    /** No price on that side, or none ever traded. */
    public static final long NONE = -1;

    // ─────────── browse ───────────

    /** One item in the browse list. */
    public static final class ItemRow {
        public final String itemId;
        /** The lowest price someone is selling at: what buying right now costs. NONE if nobody sells. */
        public final long buyNow;
        /** The highest price someone is buying at: what selling right now earns. NONE if nobody buys. */
        public final long sellNow;
        /** What it last changed hands for, NONE if it never has. */
        public final long last;
        /**
         * The market price: the average of the last {@link #MARKET_PRICE_TRADES} trades,
         * weighted by how much each moved, or NONE if it never traded. Steadier than the
         * last price, which one odd trade can set anywhere.
         */
        public final long marketPrice;
        /**
         * Change in the last price over the last day, in percent, or null when the history
         * here does not reach back a day (it keeps a few hundred trades per item) or there
         * was no trade before then to compare with.
         */
        public final Double dayChangePercent;
        /** Units traded in the trades the market still remembers for this item. */
        public final long recentVolume;
        /** How much of it you hold in the market. */
        public final long youHold;
        /** How many of your orders are resting on it. */
        public final int yourOrders;

        ItemRow(String itemId, long buyNow, long sellNow, long last, long marketPrice, Double dayChangePercent,
                long recentVolume, long youHold, int yourOrders) {
            this.itemId = itemId;
            this.buyNow = buyNow;
            this.sellNow = sellNow;
            this.last = last;
            this.marketPrice = marketPrice;
            this.dayChangePercent = dayChangePercent;
            this.recentVolume = recentVolume;
            this.youHold = youHold;
            this.yourOrders = yourOrders;
        }
    }

    static final long DAY_MS = 24L * 60 * 60 * 1000;

    /**
     * Every item worth listing: on the book, traded here, or held in the market by you,
     * plus any extra ids the caller wants included. Busiest first, then by id so the
     * order is stable from one frame to the next.
     */
    public static List<ItemRow> browse(MarketState state, UUID me, Collection<String> extra, long now) {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(state.activeItems());
        ids.addAll(state.trades().tradedItems());
        if (me != null) {
            for (java.util.Map.Entry<String, Long> e : state.itemBalances().heldBy(me).entrySet()) {
                if (e.getValue() != null && e.getValue() > 0) ids.add(e.getKey());
            }
        }
        if (extra != null) ids.addAll(extra);

        List<ItemRow> rows = new ArrayList<>();
        for (String id : ids) rows.add(row(state, me, id, now));
        rows.sort((a, b) -> {
            int byVolume = Long.compare(b.recentVolume, a.recentVolume);
            return byVolume != 0 ? byVolume : a.itemId.compareTo(b.itemId);
        });
        return rows;
    }

    /** The browse row for one item, also used as the item view's headline numbers. */
    public static ItemRow row(MarketState state, UUID me, String itemId, long now) {
        OrderBook book = state.peekBook(itemId);
        long buyNow = NONE, sellNow = NONE;
        int mine = 0;
        if (book != null) {
            List<Order> asks = book.restingAsks();
            List<Order> bids = book.restingBids();
            if (!asks.isEmpty()) buyNow = asks.get(0).value();
            if (!bids.isEmpty()) sellNow = bids.get(0).value();
            if (me != null) {
                for (Order o : asks) if (me.equals(o.userID())) mine++;
                for (Order o : bids) if (me.equals(o.userID())) mine++;
            }
        }
        List<Trade> trades = state.trades().recentFor(itemId);
        long volume = 0;
        for (Trade t : trades) volume = saturatingAdd(volume, t.quantity);
        long last = trades.isEmpty() ? NONE : trades.get(trades.size() - 1).price;
        long held = me == null ? 0 : state.itemBalances().getBalance(me, itemId);
        return new ItemRow(itemId, buyNow, sellNow, last, marketPrice(trades), dayChange(trades, now),
                volume, held, mine);
    }

    /** How many recent trades the market price averages over. */
    public static final int MARKET_PRICE_TRADES = 20;

    /**
     * The average price of the last few trades, weighted by quantity and rounded to a
     * whole credit, or NONE when there are none. A trade of sixty counts sixty times as
     * much as a trade of one, so a single small odd trade barely moves it.
     */
    static long marketPrice(List<Trade> oldestFirst) {
        if (oldestFirst.isEmpty()) return NONE;
        int from = Math.max(0, oldestFirst.size() - MARKET_PRICE_TRADES);
        double value = 0, units = 0;
        for (int i = from; i < oldestFirst.size(); i++) {
            Trade t = oldestFirst.get(i);
            value += (double) t.price * t.quantity;
            units += t.quantity;
        }
        return units <= 0 ? NONE : Math.round(value / units);
    }

    // ─────────── the advanced view's figures ───────────

    /** Figures about an item's trading, for the item view's advanced side. */
    public static final class Stats {
        /** The trades they cover, oldest first: the last day's, or every remembered one if none are that recent. */
        public final List<Trade> trades;
        /** True when they are the last day's; false when the market has no trade that recent. */
        public final boolean lastDay;
        public final long low;
        public final long high;
        public final long volume;
        /** The gap between the lowest seller and the highest buyer, or NONE when a side is empty. */
        public final long spread;

        Stats(List<Trade> trades, boolean lastDay, long low, long high, long volume, long spread) {
            this.trades = trades;
            this.lastDay = lastDay;
            this.low = low;
            this.high = high;
            this.volume = volume;
            this.spread = spread;
        }
    }

    public static Stats stats(MarketState state, String itemId, long now) {
        List<Trade> all = state.trades().recentFor(itemId);
        List<Trade> day = new ArrayList<>();
        for (Trade t : all) if (t.timestamp > now - DAY_MS) day.add(t);
        boolean lastDay = !day.isEmpty();
        List<Trade> use = lastDay ? day : all;
        long low = NONE, high = NONE, volume = 0;
        for (Trade t : use) {
            low = low == NONE ? t.price : Math.min(low, t.price);
            high = high == NONE ? t.price : Math.max(high, t.price);
            volume = saturatingAdd(volume, t.quantity);
        }
        ItemRow row = row(state, null, itemId, now);
        long spread = row.buyNow == NONE || row.sellNow == NONE ? NONE : row.buyNow - row.sellNow;
        return new Stats(use, lastDay, low, high, volume, spread);
    }

    /**
     * The last price against the last price at least a day ago. Null when nothing in the
     * remembered trades is that old: a change measured against an arbitrary older trade
     * would be a number that looks like "today" and means something else.
     */
    static Double dayChange(List<Trade> oldestFirst, long now) {
        if (oldestFirst.size() < 2) return null;
        Trade newest = oldestFirst.get(oldestFirst.size() - 1);
        Trade before = null;
        for (Trade t : oldestFirst) {
            if (t.timestamp <= now - DAY_MS) before = t;
            else break;
        }
        if (before == null || before.price <= 0) return null;
        return (newest.price - before.price) * 100.0 / before.price;
    }

    // ─────────── the book, grouped by price ───────────

    /** Everything resting at one price on one side. */
    public static final class Level {
        public final long price;
        public final long volume;
        public final int orders;
        /** Whether one of those orders is yours. */
        public final boolean yours;

        Level(long price, long volume, int orders, boolean yours) {
            this.price = price;
            this.volume = volume;
            this.orders = orders;
            this.yours = yours;
        }
    }

    /** Both sides of an item's book, a price per line, best price first on each side. */
    public static final class Depth {
        /** Sellers, lowest price first. */
        public final List<Level> asks;
        /** Buyers, highest price first. */
        public final List<Level> bids;

        Depth(List<Level> asks, List<Level> bids) {
            this.asks = asks;
            this.bids = bids;
        }
    }

    /**
     * The book with orders at the same price added together.
     *
     * One line per order is what made the old order book unreadable on a busy item: forty
     * lines of "SELL 8 @ 3" say less than "3 · 320". What a buyer needs is how much is
     * available at each price; who placed each part of it is not something they can act on.
     */
    public static Depth depth(MarketState state, UUID me, String itemId, int maxLevels) {
        OrderBook book = state.peekBook(itemId);
        if (book == null) return new Depth(Collections.<Level>emptyList(), Collections.<Level>emptyList());
        return new Depth(group(book.restingAsks(), me, maxLevels), group(book.restingBids(), me, maxLevels));
    }

    private static List<Level> group(List<Order> bestFirst, UUID me, int maxLevels) {
        List<Level> out = new ArrayList<>();
        int i = 0;
        while (i < bestFirst.size() && out.size() < maxLevels) {
            long price = bestFirst.get(i).value();
            long volume = 0;
            int orders = 0;
            boolean yours = false;
            while (i < bestFirst.size() && bestFirst.get(i).value() == price) {
                Order o = bestFirst.get(i);
                volume = saturatingAdd(volume, o.volume());
                orders++;
                yours |= me != null && me.equals(o.userID());
                i++;
            }
            out.add(new Level(price, volume, orders, yours));
        }
        return out;
    }

    // ─────────── what an order would do ───────────

    /** What placing an order would cost and do, worked out before it is sent. */
    public static final class Quote {
        /** Price times amount: what a buy reserves, or what a sale is worth before the fee. */
        public final long total;
        /** Charged when the order is placed, kept even if it is cancelled. */
        public final long listingFee;
        /** On a sale, the trading fee taken from what it earns. Zero on a buy. */
        public final long tradingFee;
        /** How much of it would trade straight away against what is resting now. */
        public final long fillsNow;
        /** For a buy: credits it needs (total plus listing fee). For a sale: credits the listing fee needs. */
        public final long creditsNeeded;
        /** Whether the player has those credits. */
        public final boolean affordable;
        /** False when the arithmetic overflowed: a price or amount far beyond anything sensible. */
        public final boolean sensible;

        Quote(long total, long listingFee, long tradingFee, long fillsNow, long creditsNeeded,
              boolean affordable, boolean sensible) {
            this.total = total;
            this.listingFee = listingFee;
            this.tradingFee = tradingFee;
            this.fillsNow = fillsNow;
            this.creditsNeeded = creditsNeeded;
            this.affordable = affordable;
            this.sensible = sensible;
        }
    }

    /**
     * What an order of this size at this price would cost and how much of it would fill
     * now. A forecast from this copy of the market, which can be a moment behind the host;
     * the host still decides.
     */
    public static Quote quote(MarketState state, UUID me, String itemId, boolean isBid,
                              long price, long amount) {
        if (price <= 0 || amount <= 0) return new Quote(0, 0, 0, 0, 0, true, true);
        long listing = me == null ? state.listingFee() : state.listingFeeFor(me);
        long credits = me == null ? 0 : state.wallets().getBalance(me);
        try {
            long total = Math.multiplyExact(price, amount);
            long trading = isBid ? 0 : MarketState.taxOn(total, state.taxBps());
            long needed = isBid ? Math.addExact(total, listing) : listing;
            long fills = fillsNow(state, itemId, isBid, price, amount);
            return new Quote(total, listing, trading, fills, needed, credits >= needed, true);
        } catch (ArithmeticException tooBig) {
            return new Quote(Long.MAX_VALUE, listing, 0, 0, Long.MAX_VALUE, false, false);
        }
    }

    /** How much of an order would trade straight away against the other side as it rests now. */
    static long fillsNow(MarketState state, String itemId, boolean isBid, long price, long amount) {
        OrderBook book = state.peekBook(itemId);
        if (book == null) return 0;
        long filled = 0;
        for (Order o : isBid ? book.restingAsks() : book.restingBids()) {
            boolean crosses = isBid ? o.value() <= price : o.value() >= price;
            if (!crosses) break;
            filled = saturatingAdd(filled, o.volume());
            if (filled >= amount) return amount;
        }
        return filled;
    }

    // ─────────── your orders and your trades ───────────

    /** Your resting orders, grouped by item and then best price first, as the book lists them. */
    public static List<Order> yourOrders(MarketState state, UUID me) {
        if (me == null) return Collections.emptyList();
        List<Order> out = new ArrayList<>(state.restingOrdersOf(me));
        out.sort((a, b) -> {
            int byItem = a.itemID().compareTo(b.itemID());
            if (byItem != 0) return byItem;
            if (a.isBid() != b.isBid()) return a.isBid() ? 1 : -1;   // sells first, like the book
            return a.isBid() ? Long.compare(b.value(), a.value()) : Long.compare(a.value(), b.value());
        });
        return out;
    }

    /** One of your trades: which side you were on, and the trade itself. */
    public static final class YourTrade {
        public final Trade trade;
        /** True if you bought, false if you sold. */
        public final boolean bought;

        YourTrade(Trade trade, boolean bought) {
            this.trade = trade;
            this.bought = bought;
        }
    }

    /**
     * Your trades, newest first, at most limit of them.
     *
     * From the trades the market remembers per item (the last few hundred of each), so a
     * very old trade on a busy item may have dropped out. A trade with yourself is listed
     * once, as a purchase.
     */
    public static List<YourTrade> yourTrades(MarketState state, UUID me, int limit) {
        if (me == null) return Collections.emptyList();
        List<YourTrade> out = new ArrayList<>();
        for (String item : state.trades().tradedItems()) {
            for (Trade t : state.trades().recentFor(item)) {
                if (me.equals(t.buyerId)) out.add(new YourTrade(t, true));
                else if (me.equals(t.sellerId)) out.add(new YourTrade(t, false));
            }
        }
        out.sort((a, b) -> Long.compare(b.trade.seq, a.trade.seq));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    // ─────────── profit and loss ───────────

    /** Someone's profit and loss: what is locked in, and what what they hold is worth over its cost. */
    public static final class Pnl {
        public final UUID who;
        /** Locked in by selling, less listing fees. */
        public final long realized;
        /** What they hold, at the market price, less what it cost them. Items with no market price count at cost. */
        public final long open;

        Pnl(UUID who, long realized, long open) {
            this.who = who;
            this.realized = realized;
            this.open = open;
        }
    }

    public static Pnl pnl(MarketState state, UUID who, long now) {
        long open = 0;
        for (java.util.Map.Entry<String, long[]> e : state.positionsOf(who).entrySet()) {
            long units = e.getValue()[0], cost = e.getValue()[1];
            long price = marketPrice(state.trades().recentFor(e.getKey()));
            if (price == NONE || units <= 0) continue;
            double value = (double) units * price;
            open = saturatingAdd(open, value >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(value) - cost);
        }
        return new Pnl(who, state.realizedPnl(who), open);
    }

    /** Everyone registered here, best realised profit first. Bots included: they trade like anyone. */
    public static List<Pnl> leaderboard(MarketState state, long now) {
        List<Pnl> out = new ArrayList<>();
        for (UUID u : state.registeredUsers()) out.add(pnl(state, u, now));
        out.sort((a, b) -> {
            int byRealized = Long.compare(b.realized, a.realized);
            return byRealized != 0 ? byRealized : Long.compare(b.open, a.open);
        });
        return out;
    }

    /** "+120", "-35", "0". */
    public static String signed(long v) {
        return v > 0 ? "+" + v : Long.toString(v);
    }

    // ─────────── formatting shared by every screen ───────────

    /** "3", or "-" for NONE. */
    public static String price(long p) {
        return p == NONE ? "-" : Long.toString(p);
    }

    /** "+12%", "-8%", "0%", or "" when there is no figure. Whole percents: a day's move does not need decimals. */
    public static String change(Double percent) {
        if (percent == null) return "";
        long rounded = Math.round(percent);
        return (rounded > 0 ? "+" : "") + rounded + "%";
    }

    /** "just now", "5 min ago", "3 h ago", "2 days ago". */
    public static String ago(long then, long now) {
        long s = Math.max(0, (now - then) / 1000);
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        long d = s / 86400;
        return d + (d == 1 ? " day ago" : " days ago");
    }

    private static long saturatingAdd(long a, long b) {
        long r = a + b;
        return ((a ^ r) & (b ^ r)) < 0 ? Long.MAX_VALUE : r;
    }
}
