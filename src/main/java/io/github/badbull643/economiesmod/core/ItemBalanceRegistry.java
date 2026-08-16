package io.github.badbull643.economiesmod.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ItemBalanceRegistry {
    // userId -> (itemId -> quantity)
    private final Map<UUID, Map<String, Long>> balances = new HashMap<>();

    public long getBalance(UUID userId, String itemId) {
        Map<String, Long> userBalances = balances.get(userId);
        if (userBalances == null) return 0L;
        return userBalances.getOrDefault(itemId, 0L);
    }

    public void adjust(UUID userId, String itemId, long delta) {
        balances.computeIfAbsent(userId, k -> new HashMap<>())
                .merge(itemId, delta, Long::sum);
    }

    /**
     * Every item this user holds a balance in.
     *
     * The ledger is the only complete answer: an item deposited but never listed has
     * no order book, so anything enumerating from the books misses it.
     */
    public Map<String, Long> heldBy(UUID userId) {
        Map<String, Long> userBalances = balances.get(userId);
        return userBalances == null ? new HashMap<>() : new HashMap<>(userBalances);
    }

    // package-private, for MarketState's snapshot logic
    Map<UUID, Map<String, Long>> balances() { return balances; }
}