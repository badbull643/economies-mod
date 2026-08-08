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

    // package-private, for MarketState's snapshot logic
    Map<UUID, Map<String, Long>> balances() { return balances; }
}