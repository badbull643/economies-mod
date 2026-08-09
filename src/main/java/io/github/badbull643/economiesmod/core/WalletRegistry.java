package io.github.badbull643.economiesmod.core;
import java.util.UUID;

import java.util.*;

//this stores the  use balance baskically,
public class WalletRegistry {
    private final Map<UUID, Long> balances = new HashMap<>();

    public long getBalance(UUID userId) {
        return balances.getOrDefault(userId, 0L);
    }

    void setBalance(UUID userId, long amount) {
        balances.put(userId, amount);
    }

    public void adjust(UUID userId, long delta) {
        balances.put(userId, getBalance(userId) + delta);
    }

    // package-private — used by MarketState for serialization
    Map<UUID, Long> balances() { return balances; }
}
