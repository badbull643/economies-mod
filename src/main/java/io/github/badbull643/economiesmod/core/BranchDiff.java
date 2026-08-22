package io.github.badbull643.economiesmod.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

    /**
     * Items {@code userId} put into this market after {@code sharedThroughSeq} and still
     * holds — what a reset destroys that nothing can give back.
     *
     * The one loss a reset causes outside the ledger. Credits and balances from before
     * the split return when the shared history is adopted again; orders are handed back
     * as a checklist by {@link #ordersOnlyAfter}. Items deposited *since* the split are
     * different in kind: they physically left a Minecraft inventory, the branch that
     * recorded them is about to be deleted, and no history anywhere says they exist.
     *
     * <h2>Two bounds, and both are needed</h2>
     *
     * <b>What they put in since the split</b>, netted against what they took back out.
     * Deposits from before it are in the host's copy too and come back on reconnecting;
     * refunding those would hand over items the market still says are theirs. And
     * somebody who deposited and then withdrew already has them.
     *
     * <b>What the ledger still says they hold</b>, counting goods reserved in resting
     * sell orders — those are still theirs. Somebody who deposited and then *sold* is
     * holding credits, not goods, and the credits are the fork's cost. Refunding there
     * would create items the buyer also holds.
     *
     * The smaller of the two. It can under-refund and cannot over-refund, which is the
     * only acceptable direction for something that ends in items appearing in a world.
     *
     * MigrateBalance deposits items too and is deliberately not counted: those never
     * came out of anybody's inventory, and they are authored by the host rather than the
     * beneficiary, so the author test below excludes them on its own. Said out loud
     * because "deposits items" would otherwise look like the thing to match on.
     */
    public static Map<String, Long> depositsOnlyAfter(EventLog log, long sharedThroughSeq,
                                                      UUID userId) throws IOException {
        Map<String, Long> out = new TreeMap<>();
        if (log == null || userId == null) return out;

        Map<String, Long> net = new TreeMap<>();
        for (SequencedEvent se : log.readFrom(0)) {
            if (se.seq <= sharedThroughSeq) continue;
            Event e = se.event;
            if (e == null || !userId.equals(e.userId)) continue;

            if (e instanceof Event.Deposit) {
                Event.Deposit d = (Event.Deposit) e;
                net.merge(d.itemId, d.quantity, Long::sum);
            } else if (e instanceof Event.DepositAndList) {
                Event.DepositAndList d = (Event.DepositAndList) e;
                net.merge(d.itemId, d.quantity, Long::sum);
            } else if (e instanceof Event.Withdraw) {
                Event.Withdraw w = (Event.Withdraw) e;
                net.merge(w.itemId, -w.quantity, Long::sum);
            }
        }

        MarketState now = EventApplier.replay(log);
        for (Map.Entry<String, Long> entry : net.entrySet()) {
            long putIn = entry.getValue();
            if (putIn <= 0) continue;                   // took more back out than in
            long give = Math.min(putIn, heldNow(now, userId, entry.getKey()));
            if (give > 0) out.put(entry.getKey(), give);
        }
        return out;
    }

    /** Free balance plus goods reserved in this user's resting sells — all still theirs. */
    private static long heldNow(MarketState state, UUID userId, String itemId) {
        long held = state.itemBalances().getBalance(userId, itemId);
        OrderBook book = state.peekBook(itemId);
        if (book != null) {
            for (Order o : book.restingAsks()) {
                if (o.userID().equals(userId)) held += o.volume();
            }
        }
        return held;
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
