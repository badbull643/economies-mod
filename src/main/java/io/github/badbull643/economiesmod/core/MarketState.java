package io.github.badbull643.economiesmod.core;
//imports
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.*;
import java.nio.file.Paths;
import java.util.UUID;

/*

so the idea is that now im on the snap shot part which takes a snapshot fo the current state of the economy and
saves that state in a file,
so the next

so far we have the order book some stuff set up,
but the next part we want is,



*/




public class MarketState {
    // The CommodityMarketRegistry from sketch a map for timebeing
    private final Map<String, OrderBook> markets = new HashMap<>();
    private final WalletRegistry wallets = new WalletRegistry();

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

    // Get-or-create the order book for a given item
    public OrderBook bookFor(String itemId) {
        return markets.computeIfAbsent(itemId, k -> new OrderBook());
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

    //all old code below still may be useful
    ///////////////////////////////////////////////////////////////////////////////

        /*public void restoreOrder(Order order) {
        bookFor(order.itemID()).restoreResting(order);
    }*/

    //testing space below
    //////////////////////////////////////////////////////
   /* static class Snapshot {
        List<WalletEntry> balances;
        List<ItemBalanceEntry> itemBalances;
        List<RestingOrder> restingOrders;


        static class WalletEntry {
            String userId;
            long amount;
        }

        static class ItemBalanceEntry {
            String userId;
            String itemId;
            long quantity;
        }

        static class RestingOrder {
            long orderId;        // keep this
            long price;
            String itemId;
            long volume;
            boolean isBid;
            String userId;
        }
    }*/

    /*public void saveTo(Path file) throws IOException {
        Snapshot snap = new Snapshot();

        // Wallets → list of entries
        snap.balances = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : wallets.balances().entrySet()) {
            Snapshot.WalletEntry w = new Snapshot.WalletEntry();
            w.userId = e.getKey().toString();
            w.amount = e.getValue();
            snap.balances.add(w);
        }

        // Item balances → flattened list of entries
        snap.itemBalances = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, Long>> userEntry : itemBalances.balances().entrySet()) {
            String userId = userEntry.getKey().toString();
            for (Map.Entry<String, Long> itemEntry : userEntry.getValue().entrySet()) {
                Snapshot.ItemBalanceEntry ib = new Snapshot.ItemBalanceEntry();
                ib.userId = userId;
                ib.itemId = itemEntry.getKey();
                ib.quantity = itemEntry.getValue();
                snap.itemBalances.add(ib);
            }
        }



        // Resting orders
        snap.restingOrders = new ArrayList<>();
        for (OrderBook book : markets.values()) {
            addBookToSnapshot(book.asks(), snap.restingOrders);
            addBookToSnapshot(book.bids(), snap.restingOrders);
        }

        Files.createDirectories(file.getParent() != null ? file.getParent() : Paths.get("."));
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (Writer w = Files.newBufferedWriter(file)) {
            gson.toJson(snap, w);
        }
    }*/

    /*private static void addBookToSnapshot(TreeMap<Long, Deque<Order>> book,
                                          List<Snapshot.RestingOrder> out) {
        //double check this later could casue issues
        for (Deque<Order> queue : book.values()) {
            for (Order o : queue) {
                Snapshot.RestingOrder r = new Snapshot.RestingOrder();
                r.orderId = o.orderId();
                r.price = o.value();
                r.itemId = o.itemID();
                r.volume = o.volume();
                r.isBid = o.isBid();
                r.userId = o.userID().toString();
                out.add(r);
            }
        }
    }*/

    //need explaination of this one aswell
    /*public static MarketState loadFrom(Path file) throws IOException {
        MarketState state = new MarketState();
        if (!Files.exists(file)) return state;

        Gson gson = new Gson();
        Snapshot snap;
        try (Reader r = Files.newBufferedReader(file)) {
            snap = gson.fromJson(r, Snapshot.class);
        }
        if (snap == null) return state;

        if (snap.balances != null) {
            for (Snapshot.WalletEntry w : snap.balances) {
                state.wallets().setBalance(UUID.fromString(w.userId), w.amount);
            }
        }

        if (snap.itemBalances != null) {
            for (Snapshot.ItemBalanceEntry ib : snap.itemBalances) {
                state.itemBalances().adjust(UUID.fromString(ib.userId), ib.itemId, ib.quantity);
            }
        }

        if (snap.restingOrders != null) {
            for (Snapshot.RestingOrder r : snap.restingOrders) {
                Order o = new Order(r.orderId, r.price, r.itemId, r.volume, r.isBid,
                        UUID.fromString(r.userId));
                state.restoreOrder(o);
            }
        }

        return state;
    }*/

    //testing main function so put test vectors here
    /*public static void main(String[] args) throws Exception {

        // ══════════════════════════════════════════════════════════════
        // GROUP A — Reservation basics, both sides
        // ══════════════════════════════════════════════════════════════
        System.out.println("GROUP A — reservation basics");

        section("A1: sell reserves items");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            m.submitOrder(new Order(m.allocateOrderId(), 10, 1, 60, false, 1L));
            check("unlisted after sell reservation", m.itemBalances().getBalance(1L, 1), 40);
        }

        section("A2: buy reserves currency at worst case");
        {
            MarketState m = new MarketState();
            m.wallets().setBalance(2L, 1000L);
            m.submitOrder(new Order(m.allocateOrderId(), 10, 1, 60, true, 2L));
            check("wallet after reserving 60x10", m.wallets().getBalance(2L), 400);
        }

        section("A3: sell rejected when items insufficient");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 50);
            List<Fill> f = m.submitOrder(new Order(m.allocateOrderId(), 10, 1, 60, false, 1L));
            check("oversell rejected", f.size(), 0);
            check("balance untouched by rejection", m.itemBalances().getBalance(1L, 1), 50);
        }

        section("A4: buy rejected when currency insufficient");
        {
            MarketState m = new MarketState();
            m.wallets().setBalance(2L, 500L);
            List<Fill> f = m.submitOrder(new Order(m.allocateOrderId(), 10, 1, 60, true, 2L));
            check("overbuy rejected", f.size(), 0);
            check("wallet untouched by rejection", m.wallets().getBalance(2L), 500);
        }

        section("A5: exact-boundary reservation succeeds");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 60);
            m.wallets().setBalance(2L, 600L);
            m.submitOrder(new Order(m.allocateOrderId(), 10, 1, 60, false, 1L));
            check("sell exact boundary, items to zero", m.itemBalances().getBalance(1L, 1), 0);
            m.submitOrder(new Order(m.allocateOrderId(), 10, 1, 60, true, 2L));
            check("buy exact boundary consumed wallet", m.wallets().getBalance(2L), 0);
            check("trade completed", m.itemBalances().getBalance(2L, 1), 60);
        }

        section("A6: zero-balance user cannot list anything");
        {
            MarketState m = new MarketState();
            check("sell with no deposit", m.submitOrder(
                    new Order(m.allocateOrderId(), 10, 1, 1, false, 9L)).size(), 0);
            check("buy with no currency", m.submitOrder(
                    new Order(m.allocateOrderId(), 10, 1, 1, true, 9L)).size(), 0);
            check("no phantom item balance", m.itemBalances().getBalance(9L, 1), 0);
            check("no phantom wallet", m.wallets().getBalance(9L), 0);
        }

        // ══════════════════════════════════════════════════════════════
        // GROUP B — Price improvement (buy side only)
        // ══════════════════════════════════════════════════════════════
        System.out.println("\nGROUP B — price improvement");

        section("B1: aggressive buyer gets refund for unspent reservation");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            m.wallets().setBalance(2L, 1000L);
            m.submitOrder(new Order(m.allocateOrderId(), 8, 1, 60, false, 1L));   // ask @8
            List<Fill> f = m.submitOrder(new Order(m.allocateOrderId(), 10, 1, 60, true, 2L));
            check("fill at resting price", f.get(0).price(), 8);
            check("seller received 480", m.wallets().getBalance(1L), 480);
            check("buyer paid 480 not 600", m.wallets().getBalance(2L), 520);
        }

        section("B2: buyer walking levels, refund accumulates correctly");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            m.deposit(3L, 1, 100);
            m.wallets().setBalance(2L, 2000L);
            m.submitOrder(new Order(m.allocateOrderId(), 8, 1, 30, false, 1L));   // 30 @ 8
            m.submitOrder(new Order(m.allocateOrderId(), 9, 1, 30, false, 3L));   // 30 @ 9
            List<Fill> f = m.submitOrder(new Order(m.allocateOrderId(), 10, 1, 60, true, 2L));
            check("two fills", f.size(), 2);
            // reserved 600, spent 30*8 + 30*9 = 240+270 = 510, refund 90
            check("buyer spent 510", m.wallets().getBalance(2L), 2000 - 510);
            check("seller 1 got 240", m.wallets().getBalance(1L), 240);
            check("seller 3 got 270", m.wallets().getBalance(3L), 270);
        }

        section("B3: resting buyer gets NO improvement (sell aggresses into bid)");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            m.wallets().setBalance(2L, 1000L);
            m.submitOrder(new Order(m.allocateOrderId(), 10, 1, 60, true, 2L));   // bid @10 rests
            check("buyer reserved 600", m.wallets().getBalance(2L), 400);
            m.submitOrder(new Order(m.allocateOrderId(), 8, 1, 60, false, 1L));   // sell @8 hits it
            // trade executes at resting bid price 10 — buyer pays what they reserved
            check("buyer wallet still 400 (no refund)", m.wallets().getBalance(2L), 400);
            check("seller received 600", m.wallets().getBalance(1L), 600);
        }

        section("B4: partial fill with improvement, then cancel");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            m.wallets().setBalance(2L, 1000L);
            m.submitOrder(new Order(m.allocateOrderId(), 8, 1, 25, false, 1L));   // only 25 @8
            long bidId = m.allocateOrderId();
            m.submitOrder(new Order(bidId, 10, 1, 60, true, 2L));                  // want 60
            // reserved 600; filled 25 @8 = 200; refund 25*(10-8)=50; remaining 35 reserved @10=350
            check("wallet after partial", m.wallets().getBalance(2L), 1000 - 600 + 50);
            m.cancelOrder(bidId, 1, true, 2L);
            check("wallet after cancelling remainder", m.wallets().getBalance(2L), 800);  // 1000-200
        }

        // ══════════════════════════════════════════════════════════════
        // GROUP C — Cancellation
        // ══════════════════════════════════════════════════════════════
        System.out.println("\nGROUP C — cancellation");

        section("C1: cancel unfilled sell refunds all items");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            long id = m.allocateOrderId();
            m.submitOrder(new Order(id, 10, 1, 60, false, 1L));
            m.cancelOrder(id, 1, false, 1L);
            check("full item refund", m.itemBalances().getBalance(1L, 1), 100);
        }

        section("C2: cancel unfilled buy refunds all currency");
        {
            MarketState m = new MarketState();
            m.wallets().setBalance(2L, 1000L);
            long id = m.allocateOrderId();
            m.submitOrder(new Order(id, 10, 1, 60, true, 2L));
            m.cancelOrder(id, 1, true, 2L);
            check("full currency refund", m.wallets().getBalance(2L), 1000);
        }

        section("C3: cancel partially-filled sell refunds remainder only");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            m.wallets().setBalance(2L, 10000L);
            long id = m.allocateOrderId();
            m.submitOrder(new Order(id, 10, 1, 60, false, 1L));
            m.submitOrder(new Order(m.allocateOrderId(), 10, 1, 25, true, 2L));
            m.cancelOrder(id, 1, false, 1L);
            check("refund only unfilled 35", m.itemBalances().getBalance(1L, 1), 75);
        }

        section("C4: foreign cancel rejected, both sides");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            m.wallets().setBalance(2L, 1000L);
            long sellId = m.allocateOrderId();
            long buyId = m.allocateOrderId();
            m.submitOrder(new Order(sellId, 20, 1, 60, false, 1L));
            m.submitOrder(new Order(buyId, 10, 1, 60, true, 2L));

            check("cannot cancel other's sell", m.cancelOrder(sellId, 1, false, 99L) ? 1 : 0, 0);
            check("cannot cancel other's buy", m.cancelOrder(buyId, 1, true, 99L) ? 1 : 0, 0);
            check("thief gained no items", m.itemBalances().getBalance(99L, 1), 0);
            check("thief gained no currency", m.wallets().getBalance(99L), 0);
            check("owner items intact", m.itemBalances().getBalance(1L, 1), 40);
            check("owner currency intact", m.wallets().getBalance(2L), 400);
        }

        section("C5: double cancel is idempotent, both sides");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            m.wallets().setBalance(2L, 1000L);
            long sellId = m.allocateOrderId();
            long buyId = m.allocateOrderId();
            m.submitOrder(new Order(sellId, 20, 1, 60, false, 1L));
            m.submitOrder(new Order(buyId, 10, 1, 60, true, 2L));

            m.cancelOrder(sellId, 1, false, 1L);
            check("second sell cancel rejected", m.cancelOrder(sellId, 1, false, 1L) ? 1 : 0, 0);
            check("no double item refund", m.itemBalances().getBalance(1L, 1), 100);

            m.cancelOrder(buyId, 1, true, 2L);
            check("second buy cancel rejected", m.cancelOrder(buyId, 1, true, 2L) ? 1 : 0, 0);
            check("no double currency refund", m.wallets().getBalance(2L), 1000);
        }

        section("C6: cancel a fully-filled order fails cleanly");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            m.wallets().setBalance(2L, 10000L);
            long id = m.allocateOrderId();
            m.submitOrder(new Order(id, 10, 1, 60, false, 1L));
            m.submitOrder(new Order(m.allocateOrderId(), 10, 1, 60, true, 2L));  // fully fills it
            check("cancel filled order rejected", m.cancelOrder(id, 1, false, 1L) ? 1 : 0, 0);
            check("no phantom refund", m.itemBalances().getBalance(1L, 1), 40);
        }

        section("C7: cancel nonexistent id fails cleanly");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            check("cancel unknown id", m.cancelOrder(999999L, 1, false, 1L) ? 1 : 0, 0);
            check("balance untouched", m.itemBalances().getBalance(1L, 1), 100);
        }

        section("C8: cancel with wrong side flag fails cleanly");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            long id = m.allocateOrderId();
            m.submitOrder(new Order(id, 10, 1, 60, false, 1L));   // it's a SELL
            check("cancel as buy fails", m.cancelOrder(id, 1, true, 1L) ? 1 : 0, 0);
            check("no refund from wrong-side cancel", m.itemBalances().getBalance(1L, 1), 40);
        }

        section("C9: cancel with wrong item id fails cleanly");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            long id = m.allocateOrderId();
            m.submitOrder(new Order(id, 10, 1, 60, false, 1L));   // item 1
            check("cancel on item 2 fails", m.cancelOrder(id, 2, false, 1L) ? 1 : 0, 0);
            check("item 1 balance untouched", m.itemBalances().getBalance(1L, 1), 40);
            check("no item 2 created", m.itemBalances().getBalance(1L, 2), 0);
        }

        // ══════════════════════════════════════════════════════════════
        // GROUP D — Item separation and multi-item integrity
        // ══════════════════════════════════════════════════════════════
        System.out.println("\nGROUP D — item separation");

        section("D1: balances are per-item, not shared");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);   // iron
            m.deposit(1L, 2, 50);    // diamond
            check("iron balance", m.itemBalances().getBalance(1L, 1), 100);
            check("diamond balance", m.itemBalances().getBalance(1L, 2), 50);
            m.submitOrder(new Order(m.allocateOrderId(), 10, 1, 60, false, 1L));
            check("iron reserved", m.itemBalances().getBalance(1L, 1), 40);
            check("diamond untouched", m.itemBalances().getBalance(1L, 2), 50);
        }

        section("D2: cannot sell item A backed by item B's balance");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);   // only iron
            List<Fill> f = m.submitOrder(new Order(m.allocateOrderId(), 10, 2, 60, false, 1L));
            check("sell diamond with iron balance rejected", f.size(), 0);
            check("iron untouched", m.itemBalances().getBalance(1L, 1), 100);
        }

        section("D3: orders for different items never cross");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 2, 100);
            m.wallets().setBalance(2L, 10000L);
            m.submitOrder(new Order(m.allocateOrderId(), 10, 2, 60, false, 1L));  // diamond ask @10
            List<Fill> f = m.submitOrder(new Order(m.allocateOrderId(), 10, 1, 60, true, 2L)); // iron bid @10
            check("no cross-item match", f.size(), 0);
            check("diamond seller still reserved", m.itemBalances().getBalance(1L, 2), 40);
            check("iron buyer got nothing", m.itemBalances().getBalance(2L, 1), 0);
        }

        // ══════════════════════════════════════════════════════════════
        // GROUP E — Conservation invariants (the important ones)
        // ══════════════════════════════════════════════════════════════
        System.out.println("\nGROUP E — conservation");

        section("E1: item conservation through a complex sequence");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            m.deposit(3L, 1, 200);
            m.wallets().setBalance(2L, 100000L);
            m.wallets().setBalance(4L, 100000L);

            long a = m.allocateOrderId();
            m.submitOrder(new Order(a, 10, 1, 80, false, 1L));
            m.submitOrder(new Order(m.allocateOrderId(), 12, 1, 150, false, 3L));
            m.submitOrder(new Order(m.allocateOrderId(), 11, 1, 50, true, 2L));    // takes 50 @10
            m.submitOrder(new Order(m.allocateOrderId(), 13, 1, 100, true, 4L));   // takes 30@10, 70@12
            m.cancelOrder(a, 1, false, 1L);                                         // a is exhausted

            long totalItems = m.itemBalances().getBalance(1L, 1)
                    + m.itemBalances().getBalance(2L, 1)
                    + m.itemBalances().getBalance(3L, 1)
                    + m.itemBalances().getBalance(4L, 1)
                    + restingSellVolume(m, 1);
            check("total items conserved", totalItems, 300);
        }

        section("E2: currency conservation through the same sequence");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            m.wallets().setBalance(2L, 5000L);
            long injected = 5000L;

            long bid = m.allocateOrderId();
            m.submitOrder(new Order(m.allocateOrderId(), 8, 1, 40, false, 1L));
            m.submitOrder(new Order(bid, 10, 1, 60, true, 2L));   // fills 40@8, rests 20@10
            m.cancelOrder(bid, 1, true, 2L);

            long totalCurrency = m.wallets().getBalance(1L) + m.wallets().getBalance(2L)
                    + restingBidReservation(m, 1);
            check("total currency conserved", totalCurrency, injected);
        }

        // ══════════════════════════════════════════════════════════════
        // GROUP F — Persistence round trips
        // ══════════════════════════════════════════════════════════════
        System.out.println("\nGROUP F — persistence");

        section("F1: sell reservation survives save/load");
        {
            Path file = Paths.get("./test-f1.json");
            MarketState m1 = new MarketState();
            m1.deposit(1L, 1, 100);
            m1.submitOrder(new Order(m1.allocateOrderId(), 10, 1, 60, false, 1L));
            m1.saveTo(file);

            MarketState m2 = MarketState.loadFrom(file);
            check("unlisted balance restored", m2.itemBalances().getBalance(1L, 1), 40);
            m2.wallets().setBalance(2L, 10000L);
            List<Fill> f = m2.submitOrder(new Order(m2.allocateOrderId(), 10, 1, 60, true, 2L));
            check("resting sell matched after reload", f.size(), 1);
        }

        section("F2: buy reservation survives save/load");
        {
            Path file = Paths.get("./test-f2.json");
            MarketState m1 = new MarketState();
            m1.wallets().setBalance(2L, 1000L);
            m1.submitOrder(new Order(m1.allocateOrderId(), 10, 1, 60, true, 2L));
            m1.saveTo(file);

            MarketState m2 = MarketState.loadFrom(file);
            check("wallet reservation restored", m2.wallets().getBalance(2L), 400);
            m2.deposit(1L, 1, 100);
            List<Fill> f = m2.submitOrder(new Order(m2.allocateOrderId(), 10, 1, 60, false, 1L));
            check("resting bid matched after reload", f.size(), 1);
            check("seller paid from reservation", m2.wallets().getBalance(1L), 600);
        }

        section("F3: FIFO order preserved across save/load");
        {
            Path file = Paths.get("./test-f3.json");
            MarketState m1 = new MarketState();
            m1.deposit(1L, 1, 100);
            m1.deposit(3L, 1, 100);
            m1.deposit(5L, 1, 100);
            m1.submitOrder(new Order(m1.allocateOrderId(), 10, 1, 20, false, 1L));  // first
            m1.submitOrder(new Order(m1.allocateOrderId(), 10, 1, 20, false, 3L));  // second
            m1.submitOrder(new Order(m1.allocateOrderId(), 10, 1, 20, false, 5L));  // third
            m1.saveTo(file);

            MarketState m2 = MarketState.loadFrom(file);
            m2.wallets().setBalance(2L, 10000L);
            List<Fill> f = m2.submitOrder(new Order(m2.allocateOrderId(), 10, 1, 20, true, 2L));
            check("oldest order filled first after reload", f.get(0).sellerId(), 1);
        }

        section("F4: cancellation works after reload");
        {
            Path file = Paths.get("./test-f4.json");
            MarketState m1 = new MarketState();
            m1.deposit(1L, 1, 100);
            long id = m1.allocateOrderId();
            m1.submitOrder(new Order(id, 10, 1, 60, false, 1L));
            m1.saveTo(file);

            MarketState m2 = MarketState.loadFrom(file);
            check("cancel by id after reload", m2.cancelOrder(id, 1, false, 1L) ? 1 : 0, 1);
            check("refund correct after reload", m2.itemBalances().getBalance(1L, 1), 100);
        }

        section("F5: order id counter survives, no collisions");
        {
            Path file = Paths.get("./test-f5.json");
            MarketState m1 = new MarketState();
            m1.deposit(1L, 1, 100);
            long lastId = 0;
            for (int i = 0; i < 5; i++) {
                lastId = m1.allocateOrderId();
                m1.submitOrder(new Order(lastId, 10 + i, 1, 10, false, 1L));
            }
            m1.saveTo(file);

            MarketState m2 = MarketState.loadFrom(file);
            long freshId = m2.allocateOrderId();
            check("fresh id exceeds all saved ids", freshId > lastId ? 1 : 0, 1);
        }

        section("F6: multi-item state survives save/load");
        {
            Path file = Paths.get("./test-f6.json");
            MarketState m1 = new MarketState();
            m1.deposit(1L, 1, 100);
            m1.deposit(1L, 2, 50);
            m1.deposit(1L, 3, 25);
            m1.submitOrder(new Order(m1.allocateOrderId(), 10, 1, 60, false, 1L));
            m1.submitOrder(new Order(m1.allocateOrderId(), 99, 2, 20, false, 1L));
            m1.saveTo(file);

            MarketState m2 = MarketState.loadFrom(file);
            check("item 1 unlisted", m2.itemBalances().getBalance(1L, 1), 40);
            check("item 2 unlisted", m2.itemBalances().getBalance(1L, 2), 30);
            check("item 3 untouched", m2.itemBalances().getBalance(1L, 3), 25);
        }

        // ══════════════════════════════════════════════════════════════
        // GROUP G — Degenerate and adversarial inputs
        // ══════════════════════════════════════════════════════════════
        System.out.println("\nGROUP G — degenerate inputs");

        section("G1: zero-volume order");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            List<Fill> f = m.submitOrder(new Order(m.allocateOrderId(), 10, 1, 0, false, 1L));
            check("zero volume produced no fills", f.size(), 0);
            check("zero volume reserved nothing", m.itemBalances().getBalance(1L, 1), 100);
        }

        section("G2: negative volume must not credit the user");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            m.submitOrder(new Order(m.allocateOrderId(), 10, 1, -50, false, 1L));
            check("negative volume did not inflate balance",
                    m.itemBalances().getBalance(1L, 1) <= 100 ? 1 : 0, 1);
        }

        section("G3: zero price order");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            m.wallets().setBalance(2L, 1000L);
            m.submitOrder(new Order(m.allocateOrderId(), 0, 1, 60, false, 1L));
            List<Fill> f = m.submitOrder(new Order(m.allocateOrderId(), 0, 1, 60, true, 2L));
            check("zero-price trade moved no currency", m.wallets().getBalance(2L), 1000);
        }

        section("G4: self-trade — user matches their own order");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            m.wallets().setBalance(1L, 10000L);
            m.submitOrder(new Order(m.allocateOrderId(), 10, 1, 60, false, 1L));
            List<Fill> f = m.submitOrder(new Order(m.allocateOrderId(), 10, 1, 60, true, 1L));
            long itemsAfter = m.itemBalances().getBalance(1L, 1);
            check("self-trade conserved items", itemsAfter, 100);
            check("self-trade conserved currency", m.wallets().getBalance(1L), 10000);
        }

        section("G5: very large values do not overflow");
        {
            MarketState m = new MarketState();
            long big = 1_000_000_000L;
            m.deposit(1L, 1, big);
            m.wallets().setBalance(2L, Long.MAX_VALUE / 2);
            m.submitOrder(new Order(m.allocateOrderId(), 1000, 1, big, false, 1L));
            check("large reservation did not wrap",
                    m.itemBalances().getBalance(1L, 1) >= 0 ? 1 : 0, 1);
        }

        section("G6: repeated deposit accumulates correctly");
        {
            MarketState m = new MarketState();
            for (int i = 0; i < 100; i++) m.deposit(1L, 1, 7);
            check("100 deposits of 7", m.itemBalances().getBalance(1L, 1), 700);
        }

        section("G7: many small orders against one large");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 1000);
            m.wallets().setBalance(2L, 100000L);
            m.submitOrder(new Order(m.allocateOrderId(), 10, 1, 1000, false, 1L));
            for (int i = 0; i < 50; i++) {
                m.submitOrder(new Order(m.allocateOrderId(), 10, 1, 20, true, 2L));
            }
            check("buyer accumulated 1000", m.itemBalances().getBalance(2L, 1), 1000);
            check("seller wallet", m.wallets().getBalance(1L), 10000);
            long total = m.itemBalances().getBalance(1L, 1) + m.itemBalances().getBalance(2L, 1);
            check("conservation over 50 fills", total, 1000);
        }
        section("G8: negative price rejected");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, 100);
            List<Fill> f = m.submitOrder(new Order(m.allocateOrderId(), -10, 1, 60, false, 1L));
            check("negative price rejected", f.size(), 0);
            check("balance untouched", m.itemBalances().getBalance(1L, 1), 100);
        }

        section("G9: negative-volume buy cannot mint currency");
        {
            MarketState m = new MarketState();
            m.wallets().setBalance(2L, 1000L);
            m.submitOrder(new Order(m.allocateOrderId(), 10, 1, -50, true, 2L));
            check("no currency minted", m.wallets().getBalance(2L), 1000);
        }

        section("H1: overflow in maxCost cannot mint currency");
        {
            MarketState m = new MarketState();
            m.wallets().setBalance(2L, 1000L);
            long huge = 4_000_000_000L;   // huge * huge overflows a long
            m.submitOrder(new Order(m.allocateOrderId(), huge, 1, huge, true, 2L));
            check("no currency minted by overflow", m.wallets().getBalance(2L), 1000);
        }

        section("H2: deposit near overflow");
        {
            MarketState m = new MarketState();
            m.deposit(1L, 1, Long.MAX_VALUE - 10);
            m.deposit(1L, 1, 100);
            check("balance did not wrap negative",
                    m.itemBalances().getBalance(1L, 1) > 0 ? 1 : 0, 1);
        }

        section("H3: randomised conservation fuzz");
        {
            MarketState m = new MarketState();
            java.util.Random rng = new java.util.Random(42);
            long deposited = 0;
            long injected = 0;
            for (long u = 1; u <= 5; u++) {
                long amt = 100 + rng.nextInt(500);
                m.deposit(u, 1, amt);
                deposited += amt;
                m.wallets().setBalance(u, 1_000_000L);
                injected += 1_000_000L;
            }

            java.util.List<long[]> live = new java.util.ArrayList<>();
            for (int i = 0; i < 5000; i++) {          // bumped from 500
                int action = rng.nextInt(10);
                long user = 1 + rng.nextInt(5);
                if (action < 6) {
                    boolean bid = rng.nextBoolean();
                    long id = m.allocateOrderId();
                    long price = 5 + rng.nextInt(20);
                    long vol = 1 + rng.nextInt(50);
                    m.submitOrder(new Order(id, price, 1, vol, bid, user));
                    live.add(new long[]{id, user, bid ? 1 : 0});
                } else if (!live.isEmpty()) {
                    long[] pick = live.remove(rng.nextInt(live.size()));
                    m.cancelOrder(pick[0], 1, pick[2] == 1, pick[1]);
                }
            }

            long totalItems = 0;
            long totalCurrency = 0;
            for (long u = 1; u <= 5; u++) {
                totalItems += m.itemBalances().getBalance(u, 1);
                totalCurrency += m.wallets().getBalance(u);
            }
            totalItems += restingSellVolume(m, 1);
            totalCurrency += restingBidReservation(m, 1);

            check("items conserved over 5000 random ops", totalItems, deposited);
            check("currency conserved over 5000 random ops", totalCurrency, injected);
        }

        System.out.println("\n" + (failures == 0
                ? "ALL " + checksRun + " CHECKS PASSED."
                : failures + " of " + checksRun + " checks FAILED."));
    }

// ── helpers ──

    private static int failures = 0;
    private static int checksRun = 0;

    private static void section(String name) {
        System.out.println("  [" + name + "]");
    }

    private static void check(String label, long actual, long expected) {
        checksRun++;
        boolean ok = actual == expected;
        if (!ok) failures++;
        System.out.println((ok ? "    ok   " : "    FAIL ") + label
                + " — expected " + expected + ", got " + actual);
    }

    // Sum of all remaining volume on resting sell orders for an item.
    private static long restingSellVolume(MarketState m, int itemId) {
        long total = 0;
        for (Deque<Order> q : m.bookFor(itemId).asks().values()) {
            for (Order o : q) total += o.volume();
        }
        return total;
    }

    // Sum of currency still reserved by resting bids for an item.
    private static long restingBidReservation(MarketState m, int itemId) {
        long total = 0;
        for (Deque<Order> q : m.bookFor(itemId).bids().values()) {
            for (Order o : q) total += o.volume() * o.value();
        }
        return total;
    }*/
}
