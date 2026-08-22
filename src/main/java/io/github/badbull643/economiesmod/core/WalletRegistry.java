package io.github.badbull643.economiesmod.core;
import java.util.UUID;

import java.util.*;

/**
 * Who holds how many credits.
 *
 * Every method is synchronized, because this is written by one thread and read by
 * another. EventApplier is the only writer and runs on the sequencer thread (hosting)
 * or the network reader thread (connected); the render thread reads balances every
 * frame. A plain HashMap read during another thread's resize can return the wrong
 * answer or worse — the same reasoning MarketState's key directory already carries,
 * which was applied there and not here.
 *
 * The monitor is this object's own and nothing else is ever held while taking it, so
 * there is no lock order to get wrong.
 */
public class WalletRegistry {
    private final Map<UUID, Long> balances = new HashMap<>();

    public synchronized long getBalance(UUID userId) {
        return balances.getOrDefault(userId, 0L);
    }

    synchronized void setBalance(UUID userId, long amount) {
        balances.put(userId, amount);
    }

    public synchronized void adjust(UUID userId, long delta) {
        balances.put(userId, balances.getOrDefault(userId, 0L) + delta);
    }

    // package-private — used by MarketState for serialization
    Map<UUID, Long> balances() { return balances; }
}
