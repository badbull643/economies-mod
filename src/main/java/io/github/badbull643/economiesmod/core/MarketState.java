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

    // ─────────── economic policy ───────────

    /**
     * Transaction tax on fills, in basis points. Zero until a MarketPolicy event says
     * otherwise, so every market created before policy existed behaves exactly as it did.
     */
    private volatile int taxBps = 0;

    public int taxBps() { return taxBps; }

    /** Called only by EventApplier, which has already checked the bounds and the author. */
    void setTaxBps(int bps) { this.taxBps = bps; }

    /**
     * What a new identity is granted, in credits.
     *
     * Defaults to the value every market used when this was a compiled-in constant, so
     * logs written before policy existed still validate — their grants were all for
     * exactly this amount.
     */
    private volatile long welcomeGrant = ServerConfig.DEFAULT_WELCOME_GRANT;

    public long welcomeGrant() { return welcomeGrant; }

    void setWelcomeGrant(long amount) { this.welcomeGrant = amount; }

    /**
     * The most a market may grant a newcomer.
     *
     * A ceiling on a fat finger, not a security boundary — the security is that grants
     * must equal the market's policy exactly, so the number a liar can give themselves
     * is the same one an honest host would have given them anyway.
     */
    public static final long MAX_WELCOME_GRANT = 1_000_000L;

    /** Basis points are per ten thousand. Named so the 10000 is never a loose literal. */
    public static final int BPS_DIVISOR = 10_000;

    /** The highest rate a market may set, in basis points. */
    public static final int MAX_TAX_BPS = 5_000;

    /**
     * The tax on one fill.
     *
     * Every replica computes this independently and must agree to the credit, so the
     * rule is fixed here rather than left to whoever calls it: multiply in long, divide
     * once, truncate. Amounts are always positive, so integer division is a floor —
     * true by accident of the inputs rather than by construction, which is why there is
     * a test pinning it rather than a comment hoping for it.
     *
     * Rounding down also means the tax can be zero on a small fill. That is deliberate:
     * the alternative, rounding up, takes a credit from a one-credit trade and makes the
     * effective rate on small trades wildly higher than the number the market advertises.
     */
    /**
     * Reads a fee written as a percentage and returns basis points, or -1 if it is not
     * one.
     *
     * Lives in core rather than beside the text field that calls it so it can be tested
     * without Minecraft — the conversion is the one place a human-entered decimal meets
     * a number every replica has to agree on.
     *
     * BigDecimal, not Double.parseDouble. Parsing "2.5" to a double and multiplying by
     * 100 can land on 249.99999999999997, and truncating that gives a market a 2.49%
     * fee nobody asked for. Two decimal places is exactly what basis points can hold,
     * so anything finer is refused rather than quietly rounded.
     */
    public static int bpsFromPercent(String text) {
        if (text == null) return -1;
        try {
            java.math.BigDecimal pct = new java.math.BigDecimal(text.trim());
            if (pct.scale() > 2) return -1;
            int bps = pct.movePointRight(2).intValueExact();
            return bps < 0 ? -1 : bps;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * The smallest sale this rate actually takes anything from, or 0 when there is no
     * rate.
     *
     * Rounding down means a percentage of a small enough sale is nothing, and with
     * integer credits that is unavoidable: a market whose items go for one or two
     * credits cannot express 2.5% of a sale at all. The rate is not broken when that
     * happens, but it is invisible, and somebody who set a fee and watched it take
     * nothing deserves to be told which of the two they are looking at.
     *
     * Ceiling division, because the fee bites at the first amount where
     * amount * bps reaches one whole ten-thousandth.
     */
    public static long smallestTaxableSale(int bps) {
        if (bps <= 0) return 0;
        return (BPS_DIVISOR + bps - 1) / bps;
    }

    public static long taxOn(long amount, int bps) {
        if (bps <= 0 || amount <= 0) return 0;
        long tax = Math.multiplyExact(amount, (long) bps) / BPS_DIVISOR;
        // Cannot exceed the trade it is levied on, whatever the rate.
        return Math.min(tax, amount);
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

            // Levied on the seller's proceeds, not the buyer's outlay. The buyer's side
            // already reserves at their limit price and refunds the difference below,
            // and threading a deduction through that is how refund arithmetic acquires
            // the double-spend bugs this project has already rejected once. Taking it
            // here changes one credit and leaves the reservation untouched.
            //
            // Burned, not paid to anyone. The welcome grant is currently an unbounded
            // source of money with no sink; a tax that credited the operator would just
            // move the unboundedness to them, and a treasury needs a way to spend that
            // does not exist. Destroying it is the only option that closes the loop.
            long tax = taxOn(f.amount(), taxBps);
            wallets.adjust(f.sellerId(), +(f.amount() - tax));

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
