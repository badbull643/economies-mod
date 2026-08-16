package io.github.badbull643.economiesmod.core;

import io.github.badbull643.economiesmod.core.net.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

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
                .append(e.marketId).append('|')
                .append(e.clientEventId).append('|')
                .append(e.timestamp);

        if (e instanceof Event.MarketCreated) {
            Event.MarketCreated mc = (Event.MarketCreated) e;
            sb.append('|').append(mc.marketName)
                    .append('|').append(mc.creatorPublicKey);
        } else if (e instanceof Event.KeyRegistered) {
            Event.KeyRegistered kr = (Event.KeyRegistered) e;
            sb.append('|').append(kr.publicKey);
        } else if (e instanceof Event.WelcomeGrant) {
            Event.WelcomeGrant wg = (Event.WelcomeGrant) e;
            sb.append('|').append(wg.targetUserId).append('|').append(wg.amount);
        } else if (e instanceof Event.MigrateBalance) {
            Event.MigrateBalance mb = (Event.MigrateBalance) e;
            sb.append('|').append(mb.fromMarketId)
                    .append('|').append(mb.fromMarketName)
                    .append('|').append(mb.fromHeadSeq)
                    .append('|').append(mb.fromHeadHash)
                    .append('|').append(mb.beneficiary)
                    .append('|').append(mb.credits);
            // Sorted, not iteration order: a HashMap or a differently-ordered list would
            // produce a different payload for the same event, and the signature would
            // fail to reproduce on the verifier's side.
            sb.append('|');
            if (mb.items != null) {
                for (Map.Entry<String, Long> entry : new TreeMap<>(mb.items).entrySet()) {
                    sb.append(entry.getKey()).append('=').append(entry.getValue()).append(',');
                }
            }
            sb.append('|');
            if (mb.foreignParticipants != null) {
                List<String> ids = new ArrayList<>();
                for (UUID u : mb.foreignParticipants) ids.add(String.valueOf(u));
                Collections.sort(ids);
                for (String id : ids) sb.append(id).append(',');
            }
        } else if (e instanceof Event.Deposit) {
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
        } else if (e instanceof Event.DepositAndList) {
            Event.DepositAndList d = (Event.DepositAndList) e;
            sb.append('|').append(d.itemId).append('|').append(d.quantity)
                    .append('|').append(d.price);
        }

        return sb.toString();
    }

}