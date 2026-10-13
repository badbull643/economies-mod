package io.github.badbull643.economiesmod.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.Lock;

/**
 * The whole economy at a glance, for whoever runs a server: how much money there is and who
 * holds it, how much the fees have destroyed, and how much of each item exists.
 *
 * Worked out from the state, in one moment, under its read lock — so a fill landing halfway
 * through cannot leave money counted on neither side or both. It decides nothing.
 *
 * Money resting in a bid counts as held: it left the bidder's wallet when the order was
 * placed, and it is still theirs until the order fills or is cancelled. Leaving it out would
 * make the money supply drop every time somebody placed a bid. Goods resting in an ask count
 * the same way.
 */
public final class MarketStats {

    /** One identity's credits, free and reserved together. */
    public static final class Holder {
        public final UUID id;
        public final boolean bot;
        public final long credits;

        Holder(UUID id, boolean bot, long credits) {
            this.id = id;
            this.bot = bot;
            this.credits = credits;
        }
    }

    /** How much of one item exists in the market, and what it trades at. */
    public static final class ItemTotal {
        public final String itemId;
        public final long units;
        /** Held by bots, out of units. */
        public final long botUnits;
        /** The market price players see, or -1 if it has never traded. */
        public final long marketPrice;

        ItemTotal(String itemId, long units, long botUnits, long marketPrice) {
            this.itemId = itemId;
            this.units = units;
            this.botUnits = botUnits;
            this.marketPrice = marketPrice;
        }
    }

    public long playerCredits;
    public long botCredits;
    /** Destroyed by the trading fee and listing fees, all time. */
    public long burned;
    public int players;
    public int bots;
    public long fills;
    public int openOrders;
    /** Everybody, richest first. */
    public final List<Holder> holders = new ArrayList<>();
    /** Every item anybody holds or has resting, most units first. */
    public final List<ItemTotal> items = new ArrayList<>();

    /** Credits held by anybody, players and bots. */
    public long circulating() { return playerCredits + botCredits; }

    /**
     * Everything ever issued here: what is held now plus what the fees destroyed. Credits
     * only ever enter through grants, stipends, migrations and bots' starting money, and only
     * ever leave through the fees, so the two halves account for all of it.
     */
    public long issued() { return circulating() + burned; }

    private MarketStats() {}

    public static MarketStats of(MarketState state) {
        MarketStats s = new MarketStats();
        Lock read = state.readLock();
        read.lock();
        try {
            Map<UUID, Long> credits = new HashMap<>(state.wallets().all());
            Map<String, Long> units = new HashMap<>();
            Map<String, Long> botUnits = new HashMap<>();

            for (Map.Entry<UUID, Map<String, Long>> e : state.itemBalances().all().entrySet()) {
                boolean bot = state.isBot(e.getKey());
                for (Map.Entry<String, Long> held : e.getValue().entrySet()) {
                    long n = held.getValue() == null ? 0 : held.getValue();
                    if (n <= 0) continue;
                    units.merge(held.getKey(), n, MarketStats::add);
                    if (bot) botUnits.merge(held.getKey(), n, MarketStats::add);
                }
            }

            // What is on the book still belongs to whoever placed it.
            for (String itemId : state.activeItems()) {
                OrderBook book = state.peekBook(itemId);
                if (book == null) continue;
                for (Order o : book.restingBids()) {
                    s.openOrders++;
                    credits.merge(o.userID(), mul(o.volume(), o.value()), MarketStats::add);
                }
                for (Order o : book.restingAsks()) {
                    s.openOrders++;
                    units.merge(itemId, o.volume(), MarketStats::add);
                    if (state.isBot(o.userID())) botUnits.merge(itemId, o.volume(), MarketStats::add);
                }
            }

            for (Map.Entry<UUID, Long> e : credits.entrySet()) {
                long c = e.getValue() == null ? 0 : e.getValue();
                boolean bot = state.isBot(e.getKey());
                if (bot) s.botCredits = add(s.botCredits, c);
                else s.playerCredits = add(s.playerCredits, c);
                if (c != 0) s.holders.add(new Holder(e.getKey(), bot, c));
            }
            s.holders.sort((a, b) -> Long.compare(b.credits, a.credits));

            for (Map.Entry<String, Long> e : units.entrySet()) {
                if (e.getValue() <= 0) continue;
                s.items.add(new ItemTotal(e.getKey(), e.getValue(),
                        botUnits.getOrDefault(e.getKey(), 0L),
                        MarketView.marketPrice(state.trades().recentFor(e.getKey()))));
            }
            s.items.sort((a, b) -> Long.compare(b.units, a.units));

            s.burned = state.creditsBurned();
            s.bots = state.bots().size();
            s.players = state.stipendClaimants();
            s.fills = state.fillsEver();
        } finally {
            read.unlock();
        }
        return s;
    }

    /**
     * The console's report. Names come from the caller, which knows who connected; null
     * for anybody it does not, who is shown by the start of their id.
     */
    public String describe(java.util.function.Function<UUID, String> nameOf, int topHolders, int topItems) {
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "credits held      %,d  (players %,d, bots %,d)%n",
                circulating(), playerCredits, botCredits));
        out.append(String.format(Locale.ROOT, "burned by fees    %,d%n", burned));
        out.append(String.format(Locale.ROOT, "issued, all time  %,d%n", issued()));
        out.append(String.format(Locale.ROOT, "people %d, bots %d, open orders %,d, trades %,d%n",
                players, bots, openOrders, fills));

        out.append("richest:");
        int shown = 0;
        for (Holder h : holders) {
            if (shown++ >= topHolders) break;
            String name = nameOf == null ? null : nameOf.apply(h.id);
            if (name == null) name = h.id.toString().substring(0, 8);
            out.append(String.format(Locale.ROOT, "%n  %-24s %,d%s", name, h.credits, h.bot ? "  (bot)" : ""));
        }
        if (shown == 0) out.append(" nobody holds anything yet");
        out.append(String.format(Locale.ROOT, "%n"));

        out.append("most held items:");
        shown = 0;
        for (ItemTotal t : items) {
            if (shown++ >= topItems) break;
            String id = t.itemId.startsWith("minecraft:") ? t.itemId.substring(10) : t.itemId;
            out.append(String.format(Locale.ROOT, "%n  %-24s %,d%s  %s", id, t.units,
                    t.botUnits > 0 ? String.format(Locale.ROOT, " (%,d with bots)", t.botUnits) : "",
                    t.marketPrice > 0 ? "at " + t.marketPrice : "never traded"));
        }
        if (shown == 0) out.append(" none yet");
        return out.toString();
    }

    private static long add(long a, long b) {
        long r = a + b;
        if (((a ^ r) & (b ^ r)) < 0) return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        return r;
    }

    private static long mul(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
