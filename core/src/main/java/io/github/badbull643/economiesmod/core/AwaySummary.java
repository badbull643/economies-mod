package io.github.badbull643.economiesmod.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What one player's orders did while their copy of the market was out of date.
 *
 * Fills are announced as they happen, but only live ones: a reconnect replays history,
 * and acting on replayed events is how withdrawals once paid out twice. So the fills a
 * player missed while offline arrived in silence, and the only way to learn that an order
 * had traded was to open History and work it out. This is the other half of that rule:
 * it does nothing but count, so it is safe to feed replayed events, and the client says
 * the total once when the catch-up is over.
 *
 * Counted from the events as they are applied, not read from TradeHistory afterwards,
 * because TradeHistory keeps only the newest trades of each item. A long absence from a
 * busy market is exactly when it would have dropped some.
 *
 * Only events after {@code afterSeq} count — the head of the copy the player already had.
 * Anything at or before it was either seen live or already reported.
 */
public final class AwaySummary {

    /** One item, one direction: everything of it this player bought, or sold. */
    public static final class ItemTotal {
        public final String itemId;
        public final boolean bought;
        private long quantity;
        private long credits;

        ItemTotal(String itemId, boolean bought) {
            this.itemId = itemId;
            this.bought = bought;
        }

        public long quantity() { return quantity; }

        /** What changed hands, before the trading fee. Always positive. */
        public long credits() { return credits; }
    }

    private final UUID me;
    private final long afterSeq;
    private final Map<String, ItemTotal> totals = new LinkedHashMap<>();
    private int fills;
    private long net;

    public AwaySummary(UUID me, long afterSeq) {
        this.me = me;
        this.afterSeq = afterSeq;
    }

    /** Counts this event's fills of ours, if it is a replayed one newer than our old head. */
    public synchronized void accept(AppliedEvent applied) {
        if (applied == null || applied.live) return;
        if (applied.event == null || applied.event.seq <= afterSeq) return;
        if (applied.result == null || applied.result.fills == null) return;

        for (Fill f : applied.result.fills) {
            boolean bought = me.equals(f.buyerId());
            boolean sold = me.equals(f.sellerId());
            // Trading with yourself moves nothing but the fee, and a summary that said
            // "bought 5, sold 5" for it would read as two trades that never happened.
            if (bought == sold) continue;

            ItemTotal t = totals.computeIfAbsent(f.itemId() + (bought ? "|b" : "|s"),
                    k -> new ItemTotal(f.itemId(), bought));
            t.quantity += f.quantity();
            t.credits += f.amount();
            net += bought ? -f.amount() : f.amount();
            fills++;
        }
    }

    public synchronized boolean isEmpty() { return fills == 0; }

    /** How many fills of ours there were. */
    public synchronized int fills() { return fills; }

    /** Credits in from selling minus credits out on buying, before fees. */
    public synchronized long net() { return net; }

    /** Per item and direction, the most credits first. */
    public synchronized List<ItemTotal> totals() {
        List<ItemTotal> out = new ArrayList<>(totals.values());
        Collections.sort(out, (a, b) -> Long.compare(b.credits, a.credits));
        return out;
    }
}
