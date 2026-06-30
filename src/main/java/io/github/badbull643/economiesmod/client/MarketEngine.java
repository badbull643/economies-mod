package io.github.badbull643.economiesmod.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/*
use two maps an order one and a buy map bascially,
so first we want too handle insertion logic map will almost never be empty,
so dont really have too worry about this case tbh,
so an order is inserted into the  if not empty map we search from the back too see
what happens,


so first indetify order type,

for the precision issue, since we are using longs  just multiply
//ill think about precision later tbh not really a problem atm just get a working system up and running,

using longs here is better too avoid float precision errors

so value of money will just have too not include decimals pretty much which is fine as that can be implmented lated with the packages

also will be ignoreing the log for the time being since i just want working order matching first pretty much

///plan

so since i want to save the game state i need 3 things first i need a submit order function/ class or whatever and
so submits the



*/

public class MarketEngine {

    static void Transfer(long buyerID, long sellerID, long amount,
                         long quantity, int itemID) {
        System.out.println("TRADE: buyer=" + buyerID + " seller=" + sellerID
                + " item=" + itemID + " qty=" + quantity
                + " amount=" + amount);
    }

    public static void main(String[] args) {
        //so differentiate via item bascially

        //eventually change too an ordered map for clean iteration
        HashMap<Integer, TreeMap<Long, Deque<Order>>> AskMapPerItem = new HashMap<>();
        HashMap<Integer, TreeMap<Long, Deque<Order>>> BidMapPerItem = new HashMap<>();

        //List<Order> inputOrders;
        //for testing purposes


        List<Order> inputOrders = new ArrayList<>();
        //testing orders
        //////////////////////////////////////////////////////////////////////////


        //matching logic
        //match the itemID too the required map pretty much
        for (Order order : inputOrders) {

            if (order.isBid()) {
                //BID ORDERS LOGIC
                ///////////////////////////////////////////
                //add the item safeguard in the order object
                //so gets polluted with defunct entry so unirered map[] creates an empty entry so fix it bruh
                TreeMap<Long, Deque<Order>> asksForItem = AskMapPerItem.get(order.itemID());
                if (asksForItem != null) {
                    //create a refrence too item map
                    //(already a reference in Java — assignment above is the reference)
                    //the matching loop here
                    while (!asksForItem.isEmpty() && order.volume() > 0) {
                        Map.Entry<Long, Deque<Order>> it = asksForItem.firstEntry();     // lowest ask
                        long bestPrice = it.getKey();

                        // Crossing check bid is willing to pay >= the ask?
                        //does the opposite logic
                        if (bestPrice > order.value()) {
                            break;   // chepeast ask is too expensive so break the loop
                        }

                        // Drain this price level
                        Deque<Order> pricelevelQueue = it.getValue();
                        while (!pricelevelQueue.isEmpty() && order.volume() > 0) {
                            Order oldestOrder = pricelevelQueue.peekFirst();

                            long tradeQty = Math.min(order.volume(), oldestOrder.volume());
                            long tradePrice = oldestOrder.value();
                            long amount = tradeQty * tradePrice;


                            //for now just prints a statement for testing here the seller is the oldest order
                            Transfer(order.userID(), oldestOrder.userID(), amount, tradeQty, order.itemID());


                            order.reduceVolume(tradeQty);
                            oldestOrder.reduceVolume(tradeQty);

                            if (oldestOrder.volume() == 0) {
                                pricelevelQueue.pollFirst();
                            }
                        }

                        if (pricelevelQueue.isEmpty()) {
                            asksForItem.remove(it.getKey());
                        }
                    }

                    //erase if the map is empty
                    if (asksForItem.isEmpty()) {
                        AskMapPerItem.remove(order.itemID());
                    }
                }

                // Anything left over rests as a new bid
                if (order.volume() > 0) {
                    BidMapPerItem
                            .computeIfAbsent(order.itemID(), k -> new TreeMap<>())
                            .computeIfAbsent(order.value(), k -> new ArrayDeque<>())
                            .addLast(order);
                }

            } else {
                //ASK ORDERS LOGIC
                ///////////////////////////////////////////////
                TreeMap<Long, Deque<Order>> BidsForItem = BidMapPerItem.get(order.itemID());
                if (BidsForItem != null) {
                    //refrence to item map
                    //(already a reference in Java — assignment above is the reference)

                    while (!BidsForItem.isEmpty() && order.volume() > 0) {
                        Map.Entry<Long, Deque<Order>> it = BidsForItem.lastEntry();     // highest bid
                        long bestPrice = it.getKey();

                        // Crossing check bid is willing to pay >= the ask?
                        //does the opposite logic
                        if (bestPrice < order.value()) {
                            break;   // best bid is too low; stop
                        }

                        // Drain this price level
                        Deque<Order> pricelevelQueue = it.getValue();
                        while (!pricelevelQueue.isEmpty() && order.volume() > 0) {
                            Order oldestOrder = pricelevelQueue.peekFirst();

                            long tradeQty = Math.min(order.volume(), oldestOrder.volume());
                            long tradePrice = oldestOrder.value();
                            long amount = tradeQty * tradePrice;

                            Transfer(oldestOrder.userID(), order.userID(), amount, tradeQty, order.itemID());

                            order.reduceVolume(tradeQty);
                            oldestOrder.reduceVolume(tradeQty);

                            if (oldestOrder.volume() == 0) {
                                pricelevelQueue.pollFirst();
                            }
                        }

                        if (pricelevelQueue.isEmpty()) {
                            BidsForItem.remove(it.getKey());
                        }
                    }
                    //erase if the map is empty
                    if (BidsForItem.isEmpty()) {
                        BidMapPerItem.remove(order.itemID());
                    }
                }

                // Anything left over rests as a new ask
                if (order.volume() > 0) {
                    AskMapPerItem
                            .computeIfAbsent(order.itemID(), k -> new TreeMap<>())
                            .computeIfAbsent(order.value(), k -> new ArrayDeque<>())
                            .addLast(order);
                }
            }

        }

    }
}