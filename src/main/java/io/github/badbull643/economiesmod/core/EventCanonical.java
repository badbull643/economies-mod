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
 *
 * That warning was here, correct, and insufficient: {@code HostDefaults} was added as a
 * whole type and never appeared below, so all eight of its fields travelled unsigned for
 * a day. A note telling somebody to remember is not a mechanism. Two now back it up —
 * this chain throws on a type it does not know, and {@code coreTests} walks every
 * declared field of every {@code Event} subclass and fails if changing one does not
 * change the payload.
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
        } else if (e instanceof Event.MarketPolicy) {
            // Signed, or a host could rewrite the rate in flight and every replica would
            // adopt it — this file's whole point is that an omitted field is tamperable.
            Event.MarketPolicy mp = (Event.MarketPolicy) e;
            sb.append('|').append(mp.taxBps).append('|').append(mp.grantAmount)
                    .append('|').append(mp.listingFee)
                    .append('|').append(mp.listingFreeOrders)
                    .append('|').append(mp.stipendAmount)
                    .append('|').append(mp.stipendEveryFills);
        } else if (e instanceof Event.Stipend) {
            Event.Stipend st = (Event.Stipend) e;
            sb.append('|').append(st.amount);
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
        } else if (e instanceof Event.HostDefaults) {
            // Missing entirely until 2026-08-25, which meant the eight fields below were
            // unsigned: the payload was the base fields alone, so anybody relaying one —
            // including the host sequencing it — could rewrite the creator's published
            // rules and the signature would still verify, because the bytes they changed
            // were never in it. Flip acceptsMigration, raise maxWelcomeGrant, swap an
            // allowlist for "open", and every host adopts the altered version as the
            // creator's genuine instruction.
            //
            // Advisory rules, so this could not move a credit directly. What it could do
            // is lower the floor those rules exist to raise, on exactly the hosts that
            // had not set anything themselves — which is the whole population the
            // mechanism was built for.
            Event.HostDefaults hd = (Event.HostDefaults) e;
            sb.append('|').append(hd.maxDepositUnitsPerWindow)
                    .append('|').append(hd.depositWindowMinutes)
                    .append('|').append(hd.maxMigratedCredits)
                    .append('|').append(hd.maxWelcomeGrant)
                    .append('|').append(hd.acceptsMigration)
                    .append('|').append(hd.admission);
            appendSorted(sb, hd.allow);
            appendSorted(sb, hd.deny);
            sb.append('|').append(hd.requireAttestation)
                    .append('|').append(hd.refuseCreativeWorlds)
                    .append('|').append(hd.refuseCheatWorlds)
                    .append('|').append(hd.banOnWorldChange)
                    .append('|').append(hd.maxDepositUnitsPerPlayHour)
                    .append('|').append(hd.maxDepositMultipleOfHandled);
        } else {
            // Not a guess about the future: every Event subclass is declared in one file
            // and this chain is the only place they are signed, so a type reaching here
            // is a type somebody added without signing its contents. HostDefaults did
            // exactly that and went a day unnoticed, because falling through silently
            // produces a payload that verifies perfectly and covers nothing.
            //
            // Throwing turns "quietly unsigned forever" into "fails the first time
            // anybody runs it". The reflection check in coreTests catches the same
            // mistake earlier and at a finer grain — per field, not per type — but this
            // is the one that holds when a test is not what runs.
            throw new IllegalStateException("no canonical form for event type "
                    + e.getClass().getSimpleName() + " — add it to EventCanonical before"
                    + " it can be signed, or its contents travel unprotected");
        }

        return sb.toString();
    }

    /**
     * A list of strings, ordered, so the same set always produces the same bytes.
     *
     * Sorted for the reason {@code MigrateBalance}'s maps and lists are: signer and
     * verifier build this independently, and a list that arrived in a different order
     * would produce a different payload for an identical event. The signature would then
     * fail to reproduce and a legitimate event would be refused.
     */
    private static void appendSorted(StringBuilder sb, List<String> values) {
        sb.append('|');
        if (values == null) return;
        List<String> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        for (String v : sorted) sb.append(v).append(',');
    }

}