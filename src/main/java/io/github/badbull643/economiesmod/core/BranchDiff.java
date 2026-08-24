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
 *
 * <h2>How this reads the log, and the one rule both methods obey</h2>
 *
 * Each question needs two things: the state at the head, and one part of the log walked.
 * The head comes from {@link EventApplier#inspect}, so a market with a snapshot pays for
 * the tail rather than for its whole history; the walks hold one event at a time rather
 * than materialising the log into a list. Both matter here more than in most places,
 * because all of it runs from a button handler on the client thread while somebody is
 * waiting to read a confirmation dialog.
 *
 * <b>The rule that buys is this: the two must describe the same history.</b> Taking the
 * head from a snapshot and the rest from the log is two sources where there was one, and
 * a snapshot stays valid in two cases where the log is not the whole story — a replica
 * that keeps no history at all, and a log with a line below the snapshot point that this
 * build cannot parse, which the hash check never looks at. Either one leaves "resting
 * now" knowing about events that "resting at the split" never saw, and every order in
 * between would be offered back for re-placing while the host still holds it. That is
 * the duplicate this class exists to avoid.
 *
 * The two cases are asked separately, because they are two questions and only one of
 * them is anybody else's to answer. {@link EventApplier.Replayed#logCoversHead} is the
 * historyless replica, and it is free. The unreadable prefix is asked here, of the walk
 * this class was going to do anyway: if it did not reach the split, and the head knows
 * about events beyond where it stopped, the two halves are describing different amounts
 * of history and neither answer is worth giving.
 *
 * Both refusals answer nothing, and nothing is the safe answer — this can under-offer
 * and must never over-offer. {@code depositsOnlyAfter} needs only the first of the two,
 * and says at its own walk why.
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

        // Asked first, because its answer decides whether the walk below means anything.
        // A client of a dedicated market keeps a snapshot and no history: it has orders
        // resting and no record of when they were placed, so all of them would look like
        // they arrived after the split. See the rule on this class.
        EventApplier.Replayed head = EventApplier.inspect(log);
        if (!head.logCoversHead) return out;
        MarketState now = head.state;

        // Stops as soon as it is past the split rather than reading to the end — and
        // stopping is what makes it partial. Reading the log into a list first, as this
        // did, meant the whole file was parsed and held before the boundary was even
        // looked at, so the early exit saved nothing at all.
        MarketState shared = new MarketState();
        final long[] reached = { 0 };
        log.forEach(0, se -> {
            if (se.seq > sharedThroughSeq) return false;
            EventApplier.apply(shared, se);
            reached[0] = se.seq;
            return true;
        });

        // The second half of the rule, and the half that costs nothing to ask because
        // this walk has just answered it. A walk stops at the first line it cannot
        // parse; a snapshot is checked by the hash at its own sequence number and never
        // looks below it, so one damaged line early in the log invalidates neither. Then
        // this walk ends at the damage and the head state does not, and the orders in
        // the gap are exactly the ones somebody would be invited to place a second time.
        // Stopping short because the log simply ends earlier is ordinary and fine, which
        // is what the min() is for.
        if (reached[0] < Math.min(sharedThroughSeq, head.headSeq)) return out;

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

        EventApplier.Replayed head = EventApplier.inspect(log);

        // No guard on logCoversHead here, and its absence is deliberate rather than
        // forgotten. A replica with no history has nothing for the walk below to read, so
        // this answers nothing on its own; adding the check would be a guard that cannot
        // fire, which this file's suite treats as worse than no guard because it is
        // counted. The sibling above needs it for one input this cannot reach — a fork
        // with nothing shared — and the check that pins that says so.
        //
        // Stepped over as lines rather than parsed as events: everything at or below the
        // split belongs to the shared history and is not this branch's to hand back.
        // That skips without looking, which is safe for this question and is not for
        // every question — see forEachAfter, where the callers that can live with it are
        // named.
        //
        // And it is why there is no second guard here to match the one in
        // ordersOnlyAfter. A damaged line below the split is a part of the file this walk
        // was never going to read, so it cannot make the netting wrong; both bounds below
        // only ever shrink what comes back; and the state the second bound reads is the
        // snapshot's, which is complete whether or not the prefix parses. On a damaged
        // log this now hands back what somebody is actually owed, where before it handed
        // back less because the replay stopped where the parsing did.
        final Map<String, Long> net = new TreeMap<>();
        log.forEachAfter(sharedThroughSeq, se -> {
            Event e = se.event;
            if (e == null || !userId.equals(e.userId)) return true;

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
            return true;
        });

        MarketState now = head.state;
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
