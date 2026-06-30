package io.github.badbull643.economiesmod.core;


import java.util.*;

//this stores the  use balance baskically,
public class WalletRegistry {
    private final Map<Long, Long> balances = new HashMap<>();

    public long getBalance(long userId) {
        return balances.getOrDefault(userId, 0L);
    }

    public void setBalance(long userId, long amount) {
        balances.put(userId, amount);
    }

    public void adjust(long userId, long delta) {
        balances.put(userId, getBalance(userId) + delta);
    }

    // package-private — used by MarketState for serialization
    Map<Long, Long> balances() { return balances; }
}
