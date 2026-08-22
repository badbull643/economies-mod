package io.github.badbull643.economiesmod.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Who holds how much of what, inside the market.
 *
 * Synchronized throughout, for the reason WalletRegistry is: EventApplier writes this
 * from the sequencer or network reader thread while the render thread reads it every
 * frame. heldBy is the one that mattered most — it copies the inner map, and building
 * that copy while the writer merges into it is a ConcurrentModificationException in the
 * middle of drawing a screen.
 */
public class ItemBalanceRegistry {
    // userId -> (itemId -> quantity)
    private final Map<UUID, Map<String, Long>> balances = new HashMap<>();

    public synchronized long getBalance(UUID userId, String itemId) {
        Map<String, Long> userBalances = balances.get(userId);
        if (userBalances == null) return 0L;
        return userBalances.getOrDefault(itemId, 0L);
    }

    public synchronized void adjust(UUID userId, String itemId, long delta) {
        balances.computeIfAbsent(userId, k -> new HashMap<>())
                .merge(itemId, delta, Long::sum);
    }

    /**
     * Every item this user holds a balance in.
     *
     * The ledger is the only complete answer: an item deposited but never listed has
     * no order book, so anything enumerating from the books misses it.
     *
     * A copy, and taken under the monitor so it is a copy of one moment rather than of
     * a map being rewritten as it is read.
     */
    public synchronized Map<String, Long> heldBy(UUID userId) {
        Map<String, Long> userBalances = balances.get(userId);
        return userBalances == null ? new HashMap<>() : new HashMap<>(userBalances);
    }

    // package-private, for MarketState's snapshot logic
    Map<UUID, Map<String, Long>> balances() { return balances; }
}