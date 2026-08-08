package io.github.badbull643.economiesmod.core;

import sun.reflect.generics.tree.Tree;

import java.util.*;
import java.util.UUID;

//this is the single item book version so change from the multi item book take this into account
public class OrderBook {
    //eventually change too an ordered map for clean iteration
    private final TreeMap<Long, Deque<Order>> asks = new TreeMap<>();
    private final TreeMap<Long, Deque<Order>> bids = new TreeMap<>();


    //readd the comments so this makes sense pretty much
    public List<Fill> submit(Order order) {
        List<Fill> fills = new ArrayList<>();

        if (order.isBid()) {
            //BID ORDERS LOGIC
            while (!asks.isEmpty() && order.volume() > 0) {
                Map.Entry<Long, Deque<Order>> it = asks.firstEntry();   // lowest ask
                long bestPrice = it.getKey();

                // crossing check
                if (bestPrice > order.value()) break;

                Deque<Order> pricelevelQueue = it.getValue();
                while (!pricelevelQueue.isEmpty() && order.volume() > 0) {
                    Order oldestOrder = pricelevelQueue.peekFirst();

                    long tradeQty   = Math.min(order.volume(), oldestOrder.volume());
                    long tradePrice = oldestOrder.value();
                    // The fill queue so a queue of all the current orders to be processed pretty much
                    fills.add(new Fill(
                            order.userID(), oldestOrder.userID(),
                            tradeQty, tradePrice, order.itemID()));

                    order.reduceVolume(tradeQty);
                    oldestOrder.reduceVolume(tradeQty);

                    if (oldestOrder.volume() == 0) {
                        pricelevelQueue.pollFirst();
                    }
                }

                if (pricelevelQueue.isEmpty()) {
                    asks.remove(it.getKey());
                }
            }

            if (order.volume() > 0) {
                bids.computeIfAbsent(order.value(), k -> new ArrayDeque<>()).addLast(order);
            }

        } else {
            //ASK ORDERS LOGIC
            while (!bids.isEmpty() && order.volume() > 0) {
                Map.Entry<Long, Deque<Order>> it = bids.lastEntry();   // highest bid
                long bestPrice = it.getKey();

                if (bestPrice < order.value()) break;

                Deque<Order> pricelevelQueue = it.getValue();
                while (!pricelevelQueue.isEmpty() && order.volume() > 0) {
                    Order oldestOrder = pricelevelQueue.peekFirst();

                    long tradeQty   = Math.min(order.volume(), oldestOrder.volume());
                    long tradePrice = oldestOrder.value();

                    fills.add(new Fill(
                            oldestOrder.userID(), order.userID(),
                            tradeQty, tradePrice, order.itemID()));

                    order.reduceVolume(tradeQty);
                    oldestOrder.reduceVolume(tradeQty);

                    if (oldestOrder.volume() == 0) {
                        pricelevelQueue.pollFirst();
                    }
                }

                if (pricelevelQueue.isEmpty()) {
                    bids.remove(it.getKey());
                }
            }

            if (order.volume() > 0) {
                asks.computeIfAbsent(order.value(), k -> new ArrayDeque<>()).addLast(order);
            }
        }

        return fills;
    }


    public Order cancel(long orderId, boolean isBid, UUID expectedUserId) {
        TreeMap<Long, Deque<Order>> side = isBid ? bids : asks;
        for (Map.Entry<Long, Deque<Order>> entry : side.entrySet()) {
            Deque<Order> queue = entry.getValue();
            for (Iterator<Order> it = queue.iterator(); it.hasNext(); ) {
                Order o = it.next();
                if (o.orderId() == orderId) {
                    if (!o.userID().equals(expectedUserId)) return null;
                    it.remove();
                    if (queue.isEmpty()) side.remove(entry.getKey());
                    return o;
                }
            }
        }
        return null;
    }

    public List<Order> restingAsks() {
        List<Order> out = new ArrayList<>();
        for (Deque<Order> q : asks.values()) {
            out.addAll(q);
        }
        return out;
    }

    /** Snapshot of resting buy orders, highest price first. */
    public List<Order> restingBids() {
        List<Order> out = new ArrayList<>();
        for (Deque<Order> q : bids.descendingMap().values()) {
            out.addAll(q);
        }
        return out;
    }

    // package-private — used by MarketState to snapshot the resting orders
    TreeMap<Long, Deque<Order>> asks() { return asks; }
    TreeMap<Long, Deque<Order>> bids() { return bids; }





}
