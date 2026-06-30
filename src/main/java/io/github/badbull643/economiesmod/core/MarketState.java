package io.github.badbull643.economiesmod.core;
//imports
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.util.*;
import java.nio.file.*;
import java.nio.file.Paths;

public class MarketState {
    // The CommodityMarketRegistry from sketch a map for timebeing
    private final Map<Integer, OrderBook> markets = new HashMap<>();
    private final WalletRegistry wallets = new WalletRegistry();

    public WalletRegistry wallets() { return wallets; }

    // Get-or-create the order book for a given item
    public OrderBook bookFor(int itemId) {
        return markets.computeIfAbsent(itemId, k -> new OrderBook());
    }

    // Single entry point: submit an order, match it, settle the fills.
    public List<Fill> submitOrder(Order order) {
        OrderBook book = bookFor(order.itemID());
        List<Fill> fills = book.submit(order);

        for (Fill f : fills) {
            wallets.adjust(f.buyerId(),  -f.amount());
            wallets.adjust(f.sellerId(), +f.amount());
        }
        return fills;
    }

    Map<Integer, OrderBook> markets() { return markets; }

    //testing space below
    //////////////////////////////////////////////////////
    static class Snapshot {
        Map<Long, Long> balances;
        List<RestingOrder> restingOrders;

        static class RestingOrder {
            long price;
            int itemId;
            long volume;
            boolean isBid;
            long userId;
        }
    }

    public void saveTo(Path file) throws IOException {
        Snapshot snap = new Snapshot();
        //copies the current balances
        snap.balances = new HashMap<>(wallets.balances());
        snap.restingOrders = new ArrayList<>();

        //weird java thingy for the market state thing
        for (OrderBook book : markets.values()) {
            //so add the ask orders too the resting orders
            addBookToSnapshot(book.asks(), snap.restingOrders);
            //add the buy orders too the resting orders
            addBookToSnapshot(book.bids(), snap.restingOrders);
        }




        //need explaination of this one
        Files.createDirectories(file.getParent() != null ? file.getParent() : Paths.get("."));
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (Writer w = Files.newBufferedWriter(file)) {
            gson.toJson(snap, w);
        }
    }

    private static void addBookToSnapshot(TreeMap<Long, Deque<Order>> book,
                                          List<Snapshot.RestingOrder> out) {
        //double check this as it could cuase issues down the line again
        // TreeMap iterates by key in order; Deque iterates front-to-back.
        // Both deterministic — saving and loading preserves FIFO order at each price.
        for (Deque<Order> queue : book.values()) {
            for (Order o : queue) {
                Snapshot.RestingOrder r = new Snapshot.RestingOrder();
                r.price = o.value();
                r.itemId = o.itemID();
                r.volume = o.volume();
                r.isBid = o.isBid();
                r.userId = o.userID();
                out.add(r);
            }
        }
    }

    //need explaination of this one aswell
    public static MarketState loadFrom(Path file) throws IOException {
        MarketState state = new MarketState();
        if (!Files.exists(file)) return state;   // first run; empty state

        Gson gson = new Gson();
        Snapshot snap;
        try (Reader r = Files.newBufferedReader(file)) {
            snap = gson.fromJson(r, Snapshot.class);
        }
        if (snap == null) return state;

        if (snap.balances != null) {
            for (Map.Entry<Long, Long> e : snap.balances.entrySet()) {
                state.wallets().setBalance(e.getKey(), e.getValue());
            }
        }

        if (snap.restingOrders != null) {
            for (Snapshot.RestingOrder r : snap.restingOrders) {
                Order o = new Order(r.price, r.itemId, r.volume, r.isBid, r.userId);
                // Since state is empty on load, submitOrder will simply rest each
                // order in the right book — no matching happens.
                state.submitOrder(o);
            }
        }

        return state;
    }

    //testing main function
    public static void main(String[] args) throws Exception {
        Path file = Paths.get("./market-test.json");

        // Build some state
        MarketState m1 = new MarketState();
        m1.wallets().setBalance(1L, 5000L);
        m1.wallets().setBalance(2L, 5000L);
        m1.submitOrder(new Order(10, 1, 50, false, 1));    // sell at 10
        m1.submitOrder(new Order(12, 1, 30, false, 2));    // sell at 12
        m1.submitOrder(new Order(9, 1, 100, true, 3));     // bid below ask — rests
        m1.saveTo(file);
        System.out.println("Saved. Inspect " + file.toAbsolutePath());

        // Load fresh
        MarketState m2 = MarketState.loadFrom(file);
        System.out.println("User 1 balance after reload: " + m2.wallets().getBalance(1L));
        System.out.println("User 3 balance after reload: " + m2.wallets().getBalance(3L));

        // Submit a crossing buy — should match the loaded sell at 10
        List<Fill> fills = m2.submitOrder(new Order(11, 1, 40, true, 4));
        System.out.println("Trades after reload: " + fills.size());
        for (Fill f : fills) {
            System.out.println("  buyer=" + f.buyerId() + " seller=" + f.sellerId()
                    + " qty=" + f.quantity() + " price=" + f.price());
        }
    }
}
