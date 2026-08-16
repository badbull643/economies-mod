package io.github.badbull643.economiesmod.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What one branch of a market has that another does not.
 *
 * Built for the question a fork asks: if this log is discarded and the host's history
 * adopted instead, what does the player actually lose? Everything up to the divergence
 * point is shared and comes back on reconnecting. Only what was written afterwards, on
 * this replica's own branch, is gone — and orders are the part worth handing back,
 * because balances are restored by the shared history while a limit price is a decision
 * somebody made and nothing else records.
 *
 * Kept in core so the arithmetic can be tested without Minecraft. The caller supplies
 * the identity; working out whose keyboard this is belongs to the client.
 */
public final class BranchDiff {

    private BranchDiff() {}

    /**
     * Orders of {@code userId} resting now that were not resting at
     * {@code sharedThroughSeq}.
     *
     * The boundary is inclusive: an event at exactly {@code sharedThroughSeq} is the
     * last one both branches agree on, so an order placed by it is safe. Getting that
     * off by one either offers back an order the host still holds — inviting a
     * duplicate — or silently drops the first order the fork cost.
     *
     * Compared by order id rather than by contents, so two identical orders at the same
     * price are told apart. Matching on price and volume would treat a pre-fork order
     * as covering a post-fork one that happened to look the same, and quietly lose it.
     */
    public static List<Order> ordersOnlyAfter(EventLog log, long sharedThroughSeq,
                                              UUID userId) throws IOException {
        List<Order> out = new ArrayList<>();
        if (log == null || userId == null) return out;

        MarketState shared = new MarketState();
        for (SequencedEvent se : log.readFrom(0)) {
            if (se.seq > sharedThroughSeq) break;
            EventApplier.apply(shared, se);
        }

        MarketState now = EventApplier.replay(log);

        Set<Long> beforeTheSplit = restingIds(shared, userId);
        for (String itemId : now.activeItems()) {
            OrderBook book = now.peekBook(itemId);
            if (book == null) continue;

            for (Order o : book.restingAsks()) {
                if (o.userID().equals(userId) && !beforeTheSplit.contains(o.orderId())) {
                    out.add(o);
                }
            }
            for (Order o : book.restingBids()) {
                if (o.userID().equals(userId) && !beforeTheSplit.contains(o.orderId())) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    /** Ids of every order this user has resting, across every book. */
    private static Set<Long> restingIds(MarketState state, UUID userId) {
        Set<Long> ids = new HashSet<>();
        for (String itemId : state.activeItems()) {
            OrderBook book = state.peekBook(itemId);
            if (book == null) continue;
            for (Order o : book.restingAsks()) {
                if (o.userID().equals(userId)) ids.add(o.orderId());
            }
            for (Order o : book.restingBids()) {
                if (o.userID().equals(userId)) ids.add(o.orderId());
            }
        }
        return ids;
    }
}
