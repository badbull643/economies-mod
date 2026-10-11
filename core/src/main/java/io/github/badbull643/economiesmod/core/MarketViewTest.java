package io.github.badbull643.economiesmod.core;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * What the market screen shows: the browse rows, the book grouped by price, the quote an
 * order gets before it is sent, and a player's own orders and trades. Built straight on a
 * MarketState, since none of it depends on how a market came to be in that state.
 */
public class MarketViewTest {

    private static int failures = 0;
    private static int checksRun = 0;

    private static void check(String label, boolean ok) {
        checksRun++;
        if (!ok) failures++;
        System.out.println((ok ? "    ok   " : "    FAIL ") + label);
    }

    private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final UUID BUYER = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
    private static final String IRON = "minecraft:iron_ingot";
    private static final String WOOD = "minecraft:oak_log";
    private static final String COAL = "minecraft:coal";
    private static final long NOW = 2_000_000_000_000L;
    private static final long DAY = MarketView.DAY_MS;

    private static long seq = 1;

    public static void main(String[] args) {
        System.out.println("  [V1: browse lists every item worth seeing, busiest first]");
        {
            MarketState s = new MarketState();
            fund(s);
            sell(s, SELLER, IRON, 9, 10);
            sell(s, SELLER, IRON, 11, 5);
            buy(s, BUYER, IRON, 7, 4);
            buy(s, BUYER, IRON, 8, 4);
            trade(s, IRON, 8, 30, NOW - 2 * DAY);
            trade(s, IRON, 10, 30, NOW - 60_000);
            trade(s, WOOD, 2, 5, NOW - 60_000);
            s.deposit(ME, "minecraft:emerald", 3);   // held by me, never traded or listed

            List<MarketView.ItemRow> rows = MarketView.browse(s, ME, Collections.singleton("minecraft:diamond"), NOW);
            check("every item: on the book, traded, held, and asked for",
                    rows.size() == 5 && find(rows, "minecraft:diamond") != null
                            && find(rows, "minecraft:emerald") != null && find(rows, COAL) != null);
            check("busiest first", rows.get(0).itemId.equals(IRON) && rows.get(1).itemId.equals(WOOD));
            MarketView.ItemRow iron = find(rows, IRON);
            check("buy now is the lowest ask", iron.buyNow == 9);
            check("sell now is the highest bid", iron.sellNow == 8);
            check("last is the last trade", iron.last == 10);
            check("day change compares with the last trade a day or more ago (8 to 10 is +25%)",
                    iron.dayChangePercent != null && Math.round(iron.dayChangePercent) == 25);
            check("recent volume adds up the remembered trades", iron.recentVolume == 60);
            MarketView.ItemRow wood = find(rows, WOOD);
            check("no day change without a trade a day old", wood.dayChangePercent == null);
            check("an empty side shows as none", wood.buyNow == MarketView.NONE && wood.sellNow == MarketView.NONE);
            check("what you hold in the market is on your rows", find(rows, "minecraft:emerald").youHold == 3);
            check("formatting: none is a dash, a change is signed",
                    MarketView.price(MarketView.NONE).equals("-") && MarketView.change(25.0).equals("+25%")
                            && MarketView.change(-8.4).equals("-8%") && MarketView.change(null).isEmpty());
        }

        System.out.println("  [V5: the market price and the advanced figures]");
        {
            MarketState s = new MarketState();
            fund(s);
            trade(s, IRON, 10, 60, NOW - 3 * DAY);
            trade(s, IRON, 8, 30, NOW - 60_000);
            trade(s, IRON, 40, 1, NOW - 30_000);   // one odd trade
            check("the market price weights by quantity (10x60, 8x30, 40x1 averages 9.67, so 10)",
                    MarketView.row(s, ME, IRON, NOW).marketPrice == 10);
            check("so one odd trade barely moves it, unlike the last price",
                    MarketView.row(s, ME, IRON, NOW).last == 40);
            check("no trades, no market price", MarketView.row(s, ME, WOOD, NOW).marketPrice == MarketView.NONE);
            for (int i = 0; i < 30; i++) trade(s, COAL, i < 10 ? 100 : 5, 1, NOW - 1000);
            check("it averages the last twenty trades only", MarketView.row(s, ME, COAL, NOW).marketPrice == 5);

            MarketView.Stats st = MarketView.stats(s, IRON, NOW);
            check("the advanced figures cover the last day when there is one", st.lastDay && st.trades.size() == 2);
            check("with its low, high and volume", st.low == 8 && st.high == 40 && st.volume == 31);
            sell(s, SELLER, IRON, 12, 5);
            buy(s, BUYER, IRON, 9, 5);
            check("and the gap between the best seller and buyer", MarketView.stats(s, IRON, NOW).spread == 3);
            MarketView.Stats old = MarketView.stats(s, IRON, NOW + 10 * DAY);
            check("with nothing that recent, every remembered trade", !old.lastDay && old.trades.size() == 3);
            check("one side empty, no spread", MarketView.stats(s, WOOD, NOW).spread == MarketView.NONE);
        }

        System.out.println("  [V6: profit and loss]");
        {
            MarketState s = new MarketState();
            s.wallets().adjust(ME, 10_000);
            s.wallets().adjust(BUYER, 10_000);
            s.deposit(SELLER, IRON, 100);
            // I buy 10 iron at 5 from the seller.
            sell(s, SELLER, IRON, 5, 10);
            buy(s, ME, IRON, 5, 10);
            check("buying realises nothing", s.realizedPnl(ME) == 0);
            long[] pos = s.positionsOf(ME).get(IRON);
            check("and holds 10 at a cost of 50", pos != null && pos[0] == 10 && pos[1] == 50);
            // The seller deposited theirs, so the 50 they got is all profit.
            check("selling deposited goods is all profit", s.realizedPnl(SELLER) == 50);
            // I sell 4 of mine to the buyer at 8: 32 earned, 20 of cost, 12 profit.
            buy(s, BUYER, IRON, 8, 4);
            sell(s, ME, IRON, 8, 4);
            check("selling bought goods realises the difference (32 - 20 = 12)", s.realizedPnl(ME) == 12);
            pos = s.positionsOf(ME).get(IRON);
            check("and the rest keep their share of the cost (6 at 30)", pos[0] == 6 && pos[1] == 30);
            // Open: the market price is the weighted average of trades, recorded here by hand.
            tradeBetween(s, IRON, 10, 1, BUYER, SELLER, NOW);
            MarketView.Pnl p = MarketView.pnl(s, ME, NOW);
            check("open is what you hold at the market price less its cost", p.realized == 12 && p.open == 6 * 10 - 30);
            // Withdrawing takes the cost with the goods, and realises nothing.
            s.withdraw(ME, IRON, 6);
            check("withdrawing is neither profit nor loss", s.realizedPnl(ME) == 12 && s.positionsOf(ME).get(IRON) == null);
            // A listing fee is a realised loss.
            s.setListingFee(3);
            s.deposit(ME, WOOD, 5);
            sell(s, ME, WOOD, 50, 5);
            check("a listing fee is a realised loss", s.realizedPnl(ME) == 9);

            List<MarketView.Pnl> board = MarketView.leaderboard(registered(s, ME, SELLER, BUYER), NOW);
            check("the leaderboard ranks by realised profit", board.get(0).who.equals(SELLER) && board.get(1).who.equals(ME));
            check("signed numbers read as gains and losses",
                    MarketView.signed(12).equals("+12") && MarketView.signed(-3).equals("-3") && MarketView.signed(0).equals("0"));
        }

        System.out.println("  [V2: the book is grouped by price, best first, yours marked]");
        {
            MarketState s = new MarketState();
            fund(s);
            sell(s, SELLER, IRON, 9, 10);
            sell(s, SELLER, IRON, 9, 30);
            sell(s, ME, IRON, 10, 5);
            sell(s, SELLER, IRON, 12, 1);
            buy(s, BUYER, IRON, 7, 4);
            buy(s, ME, IRON, 7, 2);
            buy(s, BUYER, IRON, 5, 8);
            MarketView.Depth d = MarketView.depth(s, ME, IRON, 10);
            check("three ask levels, lowest first", d.asks.size() == 3 && d.asks.get(0).price == 9 && d.asks.get(2).price == 12);
            check("orders at one price are added together", d.asks.get(0).volume == 40 && d.asks.get(0).orders == 2);
            check("a level with your order is marked", d.asks.get(1).yours && !d.asks.get(0).yours);
            check("bids highest first, yours marked", d.bids.get(0).price == 7 && d.bids.get(0).volume == 6 && d.bids.get(0).yours);
            check("the number of levels can be capped", MarketView.depth(s, ME, IRON, 2).asks.size() == 2);
            check("an item with no book has an empty depth", MarketView.depth(s, ME, "minecraft:stone", 5).asks.isEmpty());
        }

        System.out.println("  [V3: a quote says what an order costs and how much fills now]");
        {
            MarketState s = new MarketState();
            fund(s);
            s.wallets().adjust(ME, 500);
            sell(s, SELLER, IRON, 9, 10);
            sell(s, SELLER, IRON, 10, 10);
            buy(s, BUYER, IRON, 7, 4);
            s.setTaxBps(1000);   // 10%

            MarketView.Quote q = MarketView.quote(s, ME, IRON, true, 9, 16);
            check("a buy at 9 fills the 10 offered at 9", q.fillsNow == 10);
            check("its total is price times amount, and it needs that in credits", q.total == 144 && q.creditsNeeded == 144);
            check("a buy pays no trading fee", q.tradingFee == 0);
            check("and you can afford it", q.affordable);
            check("a buy at 10 fills everything up to 10", MarketView.quote(s, ME, IRON, true, 10, 25).fillsNow == 20);
            check("a buy below the asks fills nothing", MarketView.quote(s, ME, IRON, true, 8, 5).fillsNow == 0);
            check("one you cannot afford says so", !MarketView.quote(s, ME, IRON, true, 10, 60).affordable);
            MarketView.Quote sale = MarketView.quote(s, ME, IRON, false, 7, 6);
            check("a sale at 7 fills the 4 bid at 7", sale.fillsNow == 4);
            check("a sale shows the trading fee on its value (10% of 42 is 4)", sale.tradingFee == 4);
            check("a sale at an absurd size is flagged rather than overflowing",
                    !MarketView.quote(s, ME, IRON, false, Long.MAX_VALUE, 3).sensible);
            check("a blank quote for no amount", MarketView.quote(s, ME, IRON, true, 9, 0).total == 0);
        }

        System.out.println("  [V4: your orders and your trades]");
        {
            MarketState s = new MarketState();
            fund(s);
            buy(s, ME, WOOD, 2, 10);
            sell(s, ME, IRON, 12, 3);
            sell(s, ME, IRON, 11, 2);
            buy(s, BUYER, IRON, 5, 1);
            List<Order> mine = MarketView.yourOrders(s, ME);
            check("only your orders", mine.size() == 3);
            check("grouped by item, best price first", mine.get(0).itemID().equals(IRON) && mine.get(0).value() == 11
                    && mine.get(2).itemID().equals(WOOD));

            tradeBetween(s, IRON, 9, 2, BUYER, ME, NOW - 5000);
            tradeBetween(s, WOOD, 2, 7, ME, SELLER, NOW - 1000);
            tradeBetween(s, COAL, 3, 1, BUYER, SELLER, NOW);
            List<MarketView.YourTrade> trades = MarketView.yourTrades(s, ME, 10);
            check("only trades you were in", trades.size() == 2);
            check("newest first", trades.get(0).trade.itemId.equals(WOOD));
            check("and which side you were on", trades.get(0).bought && !trades.get(1).bought);
            check("capped at the limit", MarketView.yourTrades(s, ME, 1).size() == 1);
            check("ago reads in words", MarketView.ago(NOW - 5 * 60_000, NOW).equals("5 min ago")
                    && MarketView.ago(NOW - 1000, NOW).equals("just now")
                    && MarketView.ago(NOW - 2 * DAY, NOW).equals("2 days ago"));
        }

        System.out.println();
        System.out.println(failures == 0
                ? "ALL " + checksRun + " CHECKS PASSED"
                : failures + " OF " + checksRun + " CHECKS FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void fund(MarketState s) {
        for (UUID u : new UUID[]{SELLER, BUYER, ME}) {
            s.wallets().adjust(u, 1_000_000);
            for (String item : new String[]{IRON, WOOD, COAL}) s.deposit(u, item, 1_000);
        }
        // Back to a modest balance for ME, so affordability is testable.
        s.wallets().adjust(ME, -1_000_000);
    }

    private static void sell(MarketState s, UUID who, String item, long price, long volume) {
        place(s, who, item, price, volume, false);
    }

    private static void buy(MarketState s, UUID who, String item, long price, long volume) {
        if (who.equals(ME)) s.wallets().adjust(ME, price * volume);
        place(s, who, item, price, volume, true);
    }

    private static void place(MarketState s, UUID who, String item, long price, long volume, boolean bid) {
        MarketState.SubmitResult r = s.submitOrder(new Order(seq++, price, item, volume, bid, who));
        if (!r.accepted) throw new IllegalStateException("order refused: " + r.reason);
    }

    private static void trade(MarketState s, String item, long price, long qty, long at) {
        tradeBetween(s, item, price, qty, BUYER, SELLER, at);
    }

    private static void tradeBetween(MarketState s, String item, long price, long qty, UUID buyer, UUID seller, long at) {
        s.recordTrades(seq++, at, Collections.singletonList(new Fill(buyer, seller, qty, price, item)));
    }

    /** Registers identities so the leaderboard sees them; the keys are never checked here. */
    private static MarketState registered(MarketState s, UUID... who) {
        for (UUID u : who) s.registerKey(u, "test-key-" + u);
        return s;
    }

    private static MarketView.ItemRow find(List<MarketView.ItemRow> rows, String id) {
        for (MarketView.ItemRow r : rows) if (r.itemId.equals(id)) return r;
        return null;
    }
}
