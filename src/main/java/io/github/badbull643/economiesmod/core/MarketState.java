package io.github.badbull643.economiesmod.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;




public class MarketState {
    // The CommodityMarketRegistry from sketch a map for timebeing
    private final Map<String, OrderBook> markets = new HashMap<>();
    private final WalletRegistry wallets = new WalletRegistry();

    // What has actually traded. Derived from replay like everything else, never stored
    // separately — a view with its own persistence could disagree with the log.
    private final TradeHistory trades = new TradeHistory();

    // Set once, by the MarketCreated genesis event. Null means this state was built
    // from an empty log (or one predating market identity).
    // Set once, at genesis, then read from handshake threads and the render thread.
    private volatile UUID marketId;
    private volatile String marketName;
    // Who wrote the genesis event. Named in the log, so every replica agrees on it
    // without anyone having to be told.
    private volatile UUID creator;

    // userId → public key, built from MarketCreated and KeyRegistered events. This is
    // the directory that lets a log be verified by someone who wasn't there when it
    // was written; it must come from the log, never from a side file.
    //
    // Concurrent, unlike the rest of this class: the sequencer thread writes it while
    // per-connection handshake threads read it to decide admission. A plain HashMap
    // read during another thread's resize can spin forever on Java 8.
    private final Map<UUID, String> keyDirectory = new ConcurrentHashMap<>();

    // Who has already had a welcome grant in this market. Per-market by construction:
    // it is derived from this log and no other. Concurrent for the same reason.
    private final Set<UUID> granted = ConcurrentHashMap.newKeySet();

    public UUID marketId() { return marketId; }
    public String marketName() { return marketName; }
    public UUID creator() { return creator; }

    public String publicKeyOf(UUID userId) { return keyDirectory.get(userId); }
    public boolean isRegistered(UUID userId) { return keyDirectory.containsKey(userId); }
    public int registeredCount() { return keyDirectory.size(); }
    public Set<UUID> registeredUsers() { return new HashSet<>(keyDirectory.keySet()); }
    public boolean hasBeenGranted(UUID userId) { return granted.contains(userId); }

    // "<fromMarketId>:<beneficiary>" for every migration already honoured, so the same
    // branch can't be cashed in twice.
    private final Set<String> migrationsDone = ConcurrentHashMap.newKeySet();

    // Identities that held a position in a market someone migrated away from. They get
    // their own verified balance if they turn up, but never a fresh welcome grant on
    // top — that combination is the one way migration could mint currency.
    private final Set<UUID> accountedElsewhere = ConcurrentHashMap.newKeySet();

    public boolean hasMigrated(UUID fromMarketId, UUID beneficiary) {
        return migrationsDone.contains(fromMarketId + ":" + beneficiary);
    }

    public boolean isAccountedElsewhere(UUID userId) {
        return accountedElsewhere.contains(userId);
    }

    void recordMigration(UUID fromMarketId, UUID beneficiary, List<UUID> participants) {
        migrationsDone.add(fromMarketId + ":" + beneficiary);
        if (participants != null) accountedElsewhere.addAll(participants);
    }

    /** Called only by EventApplier. First registration for a userId wins. */
    void registerKey(UUID userId, String publicKey) {
        keyDirectory.put(userId, publicKey);
    }

    void markGranted(UUID userId) { granted.add(userId); }

    /** Called only by EventApplier when it applies the genesis event. */
    void setMarketIdentity(UUID id, String name, UUID creator) {
        this.marketId = id;
        this.marketName = name;
        this.creator = creator;
    }

    public WalletRegistry wallets() { return wallets; }

    public TradeHistory trades() { return trades; }

    /**
     * Files fills into the trade history. Called only by EventApplier, which is the
     * only place that knows both the fills and the event that caused them — the
     * matching engine has no clock and no sequence number of its own.
     */
    void recordTrades(long seq, long timestamp, List<Fill> fills) {
        if (fills == null) return;
        for (Fill f : fills) {
            trades.record(new Trade(seq, timestamp, f));
        }
    }

    // Get-or-create the order book for a given item
    public OrderBook bookFor(String itemId) {
        return markets.computeIfAbsent(itemId, k -> new OrderBook());
    }

    /**
     * The order book for an item, or null if it has never had one.
     *
     * For readers. {@link #bookFor} creates on read, which is correct when an order is
     * about to be placed and wrong everywhere else — a UI listing every item a player
     * holds would quietly fill the map with empty books for items nobody has ever
     * traded, and anything iterating {@link #activeItems()} afterwards would see them.
     */
    public OrderBook peekBook(String itemId) {
        return markets.get(itemId);
    }

    /** Whether this item has a book at all, without creating one. */
    public boolean hasBook(String itemId) {
        return markets.containsKey(itemId);
    }

    //item balance section
    ///////////////////////
    private final ItemBalanceRegistry itemBalances = new ItemBalanceRegistry();

    public ItemBalanceRegistry itemBalances() { return itemBalances; }



    /** Called after the client physically removes the item from a player's inventory. */
    public void deposit(UUID  userId, String itemId, long qty) {
        if (qty <= 0) return;
        long current = itemBalances.getBalance(userId, itemId);
        if (current > Long.MAX_VALUE - qty) return;   // would overflow — reject
        itemBalances.adjust(userId, itemId, +qty);
    }
    /////////////////////


    // Single entry point: submit an order, match it, settle the fills.
    public SubmitResult submitOrder(Order order) {
        SubmitResult check = canSubmit(order);
        if (!check.accepted) return check;

        // Reserve
        if (!order.isBid()) {
            itemBalances.adjust(order.userID(), order.itemID(), -order.volume());
        } else {
            wallets.adjust(order.userID(), -Math.multiplyExact(order.volume(), order.value()));
        }

        OrderBook book = bookFor(order.itemID());
        List<Fill> fills = book.submit(order);

        for (Fill f : fills) {
            itemBalances.adjust(f.buyerId(), f.itemId(), +f.quantity());
            wallets.adjust(f.sellerId(), +f.amount());

            long reservedForThisFill = f.quantity() * buyerOrderPrice(order, f);
            long actuallySpent = f.amount();
            if (reservedForThisFill > actuallySpent) {
                wallets.adjust(f.buyerId(), reservedForThisFill - actuallySpent);
            }
        }

        return SubmitResult.ok(fills);
    }


    public boolean canWithdraw(UUID userId, String itemId, long qty) {
        return qty > 0 && itemBalances.getBalance(userId, itemId) >= qty;
    }

    public boolean canCancel(long orderId, String itemId, boolean isBid, UUID userId) {
        Order o = bookFor(itemId).find(orderId, isBid);
        return o != null && o.userID().equals(userId);
    }
    /** Checks whether an order would be accepted, without mutating anything. */
    public SubmitResult canSubmit(Order order) {
        if (order.volume() <= 0 || order.value() <= 0) {
            return SubmitResult.reject("volume and price must be positive");
        }

        if (!order.isBid()) {
            long available = itemBalances.getBalance(order.userID(), order.itemID());
            if (available < order.volume()) {
                return SubmitResult.reject("insufficient item balance");
            }
        } else {
            long maxCost;
            try {
                maxCost = Math.multiplyExact(order.volume(), order.value());
            } catch (ArithmeticException e) {
                return SubmitResult.reject("order value too large");
            }
            if (wallets.getBalance(order.userID()) < maxCost) {
                return SubmitResult.reject("insufficient credits");
            }
        }

        return SubmitResult.ok(Collections.emptyList());
    }

    public boolean withdraw(UUID userId, String itemId, long qty) {
        if (qty <= 0) return false;
        long available = itemBalances.getBalance(userId, itemId);
        if (available < qty) return false;
        itemBalances.adjust(userId, itemId, -qty);
        return true;
    }

    private long buyerOrderPrice(Order incoming, Fill f) {
        // If the incoming order is the buyer, use its price.
        // Otherwise the buyer is a resting bid, whose price IS the fill price
        // (trades execute at the resting order's price), so no improvement applies.
        return incoming.isBid() ? incoming.value() : f.price();
    }

    public boolean cancelOrder(long orderId, String itemId, boolean isBid, UUID userId) {
        Order o = bookFor(itemId).cancel(orderId, isBid, userId);
        if (o == null) return false;

        if (o.isBid()) {
            wallets.adjust(userId, Math.multiplyExact(o.volume(), o.value()));
        } else {
            itemBalances.adjust(userId, itemId, o.volume());
        }
        return true;
    }


    public Set<String> activeItems() {
        return markets.keySet();
    }

    Map<String, OrderBook> markets() { return markets; }

    public static class SubmitResult {
        public final boolean accepted;
        public final String reason;
        public final List<Fill> fills;

        private SubmitResult(boolean accepted, String reason, List<Fill> fills) {
            this.accepted = accepted;
            this.reason = reason;
            this.fills = fills;
        }

        public static SubmitResult ok(List<Fill> fills) {
            return new SubmitResult(true, null, fills);
        }

        public static SubmitResult reject(String reason) {
            return new SubmitResult(false, reason, Collections.emptyList());
        }
    }

}
