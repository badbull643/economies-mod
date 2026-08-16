package io.github.badbull643.economiesmod.core;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Everything one player owns in a market, with resting orders folded back in.
 *
 * An order on the book isn't spent — it's reserved. A resting bid holds credits, a
 * resting ask holds items, and both come back if the order is cancelled. So "what you
 * hold" has to count them, or abandoning a market with orders open would appear to cost
 * nothing while actually costing everything you'd listed.
 *
 * This is the same figure whether it's being framed as a loss ("Reset would cost you
 * this") or a gain ("migration brings this across") — one calculation, two readings.
 */
public class NetPosition {

    public final long credits;
    /** itemId → quantity. Sorted, so the canonical payload of a migration is stable. */
    public final Map<String, Long> items;

    private NetPosition(long credits, Map<String, Long> items) {
        this.credits = credits;
        this.items = items;
    }

    public boolean isEmpty() {
        return credits <= 0 && items.isEmpty();
    }

    public static NetPosition of(MarketState state, UUID userId) {
        Map<String, Long> items = new TreeMap<>();
        if (state == null || userId == null) {
            return new NetPosition(0, items);
        }

        long credits = state.wallets().getBalance(userId);

        // Free balances come from the ledger, which is the only complete record — an
        // item deposited but never listed has no order book, so enumerating from the
        // books would drop it silently.
        for (Map.Entry<String, Long> e : state.itemBalances().heldBy(userId).entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) items.put(e.getKey(), e.getValue());
        }

        // Then fold in what's reserved in resting orders, which only the books know.
        for (String itemId : state.activeItems()) {
            // Credits locked in this player's resting bids are still theirs.
            for (Order o : state.bookFor(itemId).restingBids()) {
                if (o.userID().equals(userId)) {
                    credits += o.volume() * o.value();
                }
            }

            long resting = 0;
            for (Order o : state.bookFor(itemId).restingAsks()) {
                if (o.userID().equals(userId)) resting += o.volume();
            }
            if (resting > 0) items.merge(itemId, resting, Long::sum);
        }

        return new NetPosition(credits, items);
    }

    /** "340 credits, 64 minecraft:iron_ingot", or "nothing". */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (credits > 0) sb.append(credits).append(" credits");
        for (Map.Entry<String, Long> e : items.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getValue()).append(" ").append(e.getKey());
        }
        return sb.length() > 0 ? sb.toString() : "nothing";
    }
}
