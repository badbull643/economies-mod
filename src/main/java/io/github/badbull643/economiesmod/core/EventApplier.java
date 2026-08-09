package io.github.badbull643.economiesmod.core;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * The single gateway through which MarketState is mutated.
 *
 * Nothing else should call submitOrder / deposit / withdraw / cancelOrder
 * directly — routing everything through here guarantees that replaying the
 * event log reproduces exactly the state that was built live.
 *
 * Takes a SequencedEvent rather than a bare Event because order IDs are
 * derived from the sequence number, which only exists once logged.
 */
public class EventApplier {

    public static class Result {
        public final boolean accepted;
        public final String reason;
        public final List<Fill> fills;

        private Result(boolean accepted, String reason, List<Fill> fills) {
            this.accepted = accepted;
            this.reason = reason;
            this.fills = fills;
        }

        public static Result ok(List<Fill> fills) {
            return new Result(true, null, fills);
        }

        public static Result reject(String reason) {
            return new Result(false, reason, Collections.emptyList());
        }
    }

    public static Result apply(MarketState state, SequencedEvent se) {
        if (se == null || se.event == null) return Result.reject("null event");

        Event e = se.event;
        if (e.userId == null) return Result.reject("missing userId");

        if (e instanceof Event.Deposit) {
            Event.Deposit d = (Event.Deposit) e;
            if (d.quantity <= 0) return Result.reject("quantity must be positive");
            if (d.itemId == null || d.itemId.isEmpty()) return Result.reject("missing itemId");
            state.deposit(d.userId, d.itemId, d.quantity);
            return Result.ok(Collections.emptyList());
        }

        if (e instanceof Event.Withdraw) {
            Event.Withdraw w = (Event.Withdraw) e;
            if (w.quantity <= 0) return Result.reject("quantity must be positive");
            if (w.itemId == null || w.itemId.isEmpty()) return Result.reject("missing itemId");
            if (!state.withdraw(w.userId, w.itemId, w.quantity)) {
                return Result.reject("insufficient item balance");
            }
            return Result.ok(Collections.emptyList());
        }

        if (e instanceof Event.PlaceOrder) {
            Event.PlaceOrder p = (Event.PlaceOrder) e;
            if (p.itemId == null || p.itemId.isEmpty()) return Result.reject("missing itemId");

            Order order = new Order(se.seq, p.price, p.itemId, p.volume, p.isBid, p.userId);
            MarketState.SubmitResult sr = state.submitOrder(order);

            return sr.accepted ? Result.ok(sr.fills) : Result.reject(sr.reason);
        }


        if (e instanceof Event.CancelOrder) {
            Event.CancelOrder c = (Event.CancelOrder) e;
            if (c.itemId == null || c.itemId.isEmpty()) return Result.reject("missing itemId");
            if (!state.cancelOrder(c.orderId, c.itemId, c.isBid, c.userId)) {
                return Result.reject("order not found or not owned");
            }
            return Result.ok(Collections.emptyList());
        }

        if (e instanceof Event.InjectCredits) {
            Event.InjectCredits ic = (Event.InjectCredits) e;
            if (ic.amount <= 0) return Result.reject("amount must be positive");
            if (ic.targetUserId == null) return Result.reject("missing targetUserId");
            state.wallets().adjust(ic.targetUserId, ic.amount);
            return Result.ok(Collections.emptyList());
        }

        return Result.reject("unknown event type: " + e.getClass().getSimpleName());
    }

    /** Rebuilds market state from scratch by replaying an entire log. */
    public static MarketState replay(EventLog log) throws IOException {
        MarketState state = new MarketState();
        for (SequencedEvent se : log.readFrom(0)) {
            apply(state, se);
        }
        return state;
    }
    /** Checks whether an event would be accepted, without mutating state. */
    public static Result validate(MarketState state, SequencedEvent se) {
        if (se == null || se.event == null) return Result.reject("null event");

        Event e = se.event;
        if (e.userId == null) return Result.reject("missing userId");

        if (e instanceof Event.Deposit) {
            Event.Deposit d = (Event.Deposit) e;
            if (d.quantity <= 0) return Result.reject("quantity must be positive");
            if (d.itemId == null || d.itemId.isEmpty()) return Result.reject("missing itemId");
            return Result.ok(Collections.emptyList());
        }

        if (e instanceof Event.Withdraw) {
            Event.Withdraw w = (Event.Withdraw) e;
            if (w.itemId == null || w.itemId.isEmpty()) return Result.reject("missing itemId");
            if (!state.canWithdraw(w.userId, w.itemId, w.quantity)) {
                return Result.reject("insufficient item balance");
            }
            return Result.ok(Collections.emptyList());
        }

        if (e instanceof Event.PlaceOrder) {
            Event.PlaceOrder p = (Event.PlaceOrder) e;
            if (p.itemId == null || p.itemId.isEmpty()) return Result.reject("missing itemId");
            Order probe = new Order(se.seq, p.price, p.itemId, p.volume, p.isBid, p.userId);
            MarketState.SubmitResult sr = state.canSubmit(probe);
            return sr.accepted ? Result.ok(Collections.emptyList()) : Result.reject(sr.reason);
        }

        if (e instanceof Event.CancelOrder) {
            Event.CancelOrder c = (Event.CancelOrder) e;
            if (c.itemId == null || c.itemId.isEmpty()) return Result.reject("missing itemId");
            if (!state.canCancel(c.orderId, c.itemId, c.isBid, c.userId)) {
                return Result.reject("order not found or not owned");
            }
            return Result.ok(Collections.emptyList());
        }

        if (e instanceof Event.InjectCredits) {
            Event.InjectCredits ic = (Event.InjectCredits) e;
            if (ic.amount <= 0) return Result.reject("amount must be positive");
            if (ic.targetUserId == null) return Result.reject("missing targetUserId");
            return Result.ok(Collections.emptyList());
        }

        return Result.reject("unknown event type: " + e.getClass().getSimpleName());
    }




}