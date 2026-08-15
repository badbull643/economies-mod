package io.github.badbull643.economiesmod.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What actually traded, per item, most recent last.
 *
 * Rebuilt by replay like everything else — it is not persisted separately and has no
 * events of its own. That is deliberate: a derived view that had its own storage could
 * disagree with the log, and the log is the only thing anyone verifies.
 *
 * Bounded per item. An unbounded history would grow with the log forever, and the two
 * things anyone wants from it — a recent price chart, and "what did this last go for"
 * — only ever look at the tail. Replaying a long log therefore yields the most recent
 * {@value #MAX_PER_ITEM} trades per item, which is what a chart wants anyway. If full
 * history is ever needed, it is still in the log; read it from there rather than
 * growing this.
 */
public class TradeHistory {

    public static final int MAX_PER_ITEM = 512;

    private final Map<String, Deque<Trade>> byItem = new HashMap<>();

    /** Called only by MarketState, which is called only by EventApplier. */
    void record(Trade trade) {
        Deque<Trade> q = byItem.computeIfAbsent(trade.itemId, k -> new ArrayDeque<>());
        q.addLast(trade);
        while (q.size() > MAX_PER_ITEM) q.removeFirst();
    }

    /** Every trade held for an item, oldest first. Empty if the item never traded. */
    public List<Trade> recentFor(String itemId) {
        Deque<Trade> q = byItem.get(itemId);
        if (q == null || q.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(q);
    }

    /** The most recent {@code limit} trades for an item, oldest first. */
    public List<Trade> recentFor(String itemId, int limit) {
        if (limit <= 0) return Collections.emptyList();
        List<Trade> all = recentFor(itemId);
        if (all.size() <= limit) return all;
        return new ArrayList<>(all.subList(all.size() - limit, all.size()));
    }

    /**
     * What this item last actually changed hands for, or -1 if it never has.
     *
     * The honest answer to "what is this worth" — unlike the best bid or ask, which is
     * only what someone is currently asking for, and may be nowhere near a real price.
     */
    public long lastPrice(String itemId) {
        Deque<Trade> q = byItem.get(itemId);
        return (q == null || q.isEmpty()) ? -1 : q.peekLast().price;
    }

    /** How many trades are held for an item. */
    public int countFor(String itemId) {
        Deque<Trade> q = byItem.get(itemId);
        return q == null ? 0 : q.size();
    }

    /** Items that have traded at least once. */
    public Set<String> tradedItems() {
        return Collections.unmodifiableSet(byItem.keySet());
    }
}
