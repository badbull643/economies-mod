package io.github.badbull643.economiesmod.core;

import io.github.badbull643.economiesmod.core.net.Message;

/**
 * Produces the exact string that gets signed for an event.
 *
 * Signer and verifier must produce byte-identical output, so this deliberately
 * builds the string field by field rather than relying on JSON serialisation,
 * which could reorder fields or include nulls differently.
 *
 * IMPORTANT: if you add a field to an event type, add it here too — anything
 * omitted is unsigned and therefore tamperable in transit.
 */
public class EventCanonical {

    public static String canonicalPayload(Event e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getSimpleName()).append('|')
                .append(e.userId).append('|')
                .append(e.clientEventId).append('|')
                .append(e.timestamp);

        if (e instanceof Event.Deposit) {
            Event.Deposit d = (Event.Deposit) e;
            sb.append('|').append(d.itemId).append('|').append(d.quantity);
        } else if (e instanceof Event.Withdraw) {
            Event.Withdraw w = (Event.Withdraw) e;
            sb.append('|').append(w.itemId).append('|').append(w.quantity);
        } else if (e instanceof Event.PlaceOrder) {
            Event.PlaceOrder p = (Event.PlaceOrder) e;
            sb.append('|').append(p.itemId).append('|').append(p.price)
                    .append('|').append(p.volume).append('|').append(p.isBid);
        } else if (e instanceof Event.CancelOrder) {
            Event.CancelOrder c = (Event.CancelOrder) e;
            sb.append('|').append(c.itemId).append('|').append(c.orderId)
                    .append('|').append(c.isBid);
        } else if (e instanceof Event.InjectCredits) {
            Event.InjectCredits ic = (Event.InjectCredits) e;
            sb.append('|').append(ic.targetUserId).append('|').append(ic.amount);
        } else if (e instanceof Event.DepositAndList) {
            Event.DepositAndList d = (Event.DepositAndList) e;
            sb.append('|').append(d.itemId).append('|').append(d.quantity)
                    .append('|').append(d.price);
        }

        return sb.toString();
    }

}