package io.github.badbull643.economiesmod.core;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

    /**
     * Enforces the genesis rule in both directions: a MarketCreated event is legal
     * only as the first event, and no other event applies until one has been seen.
     *
     * The second half is what actually prevents fragmentation — without it a market
     * can still be born silently, by anyone, just by hosting an empty log.
     */
    private static Result checkGenesis(MarketState state, SequencedEvent se) {
        boolean isGenesis = se.event instanceof Event.MarketCreated;

        if (isGenesis) {
            if (se.seq != 1) {
                return Result.reject("a market can only be created once, at seq 1");
            }
            if (state.marketId() != null) {
                return Result.reject("this state already belongs to a market");
            }
            Event.MarketCreated mc = (Event.MarketCreated) se.event;
            if (mc.marketId == null) return Result.reject("missing marketId");
            if (mc.marketName == null || mc.marketName.trim().isEmpty()) {
                return Result.reject("missing marketName");
            }
            if (mc.creatorPublicKey == null || mc.creatorPublicKey.isEmpty()) {
                return Result.reject("missing creatorPublicKey");
            }
            return null;
        }

        // The test is on the state, not the sequence number. Checking only seq 1 would
        // reject a market-less log's first event and then happily apply every event
        // after it, rebuilding most of the state it was supposed to refuse.
        if (state.marketId() == null) {
            return Result.reject("no market — create one or join a host first");
        }

        // The signature covers marketId, so an event lifted from another market's log
        // fails here rather than being applied as if it had always belonged to this one.
        if (!state.marketId().equals(se.event.marketId)) {
            return Result.reject("event belongs to a different market ("
                    + se.event.marketId + ")");
        }

        // Registration must precede authorship. Without this an event could exist whose
        // author has no key anywhere in the log, and verification of an imported log
        // could not be total — one unverifiable event is all it takes.
        if (se.event instanceof Event.KeyRegistered) {
            Event.KeyRegistered kr = (Event.KeyRegistered) se.event;
            if (kr.publicKey == null || kr.publicKey.isEmpty()) {
                return Result.reject("missing publicKey");
            }
            if (state.isRegistered(se.event.userId)) {
                return Result.reject("identity already registered in this market");
            }
            return null;
        }

        if (!state.isRegistered(se.event.userId)) {
            return Result.reject("author not registered in this market");
        }
        return null;
    }

    public static Result apply(MarketState state, SequencedEvent se) {
        if (se == null || se.event == null) return Result.reject("null event");

        Event e = se.event;
        if (e.userId == null) return Result.reject("missing userId");

        Result genesisCheck = checkGenesis(state, se);
        if (genesisCheck != null) return genesisCheck;

        if (e instanceof Event.MarketCreated) {
            Event.MarketCreated mc = (Event.MarketCreated) e;
            state.setMarketIdentity(mc.marketId, mc.marketName, mc.userId);
            state.registerKey(mc.userId, mc.creatorPublicKey);
            return Result.ok(Collections.emptyList());
        }

        if (e instanceof Event.KeyRegistered) {
            Event.KeyRegistered kr = (Event.KeyRegistered) e;
            state.registerKey(kr.userId, kr.publicKey);
            return Result.ok(Collections.emptyList());
        }

        if (e instanceof Event.WelcomeGrant) {
            Event.WelcomeGrant wg = (Event.WelcomeGrant) e;
            state.wallets().adjust(wg.targetUserId, wg.amount);
            state.markGranted(wg.targetUserId);
            return Result.ok(Collections.emptyList());
        }

        if (e instanceof Event.MigrateBalance) {
            Event.MigrateBalance mb = (Event.MigrateBalance) e;
            if (mb.credits > 0) state.wallets().adjust(mb.beneficiary, mb.credits);
            if (mb.items != null) {
                for (Map.Entry<String, Long> entry : mb.items.entrySet()) {
                    if (entry.getValue() != null && entry.getValue() > 0) {
                        state.deposit(mb.beneficiary, entry.getKey(), entry.getValue());
                    }
                }
            }
            state.recordMigration(mb.fromMarketId, mb.beneficiary, mb.foreignParticipants);
            return Result.ok(Collections.emptyList());
        }

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

        if (e instanceof Event.DepositAndList) {
            Event.DepositAndList d = (Event.DepositAndList) e;
            if (d.quantity <= 0 || d.price <= 0) return Result.reject("invalid quantity or price");
            if (d.itemId == null || d.itemId.isEmpty()) return Result.reject("missing itemId");

            state.deposit(d.userId, d.itemId, d.quantity);
            Order order = new Order(se.seq, d.price, d.itemId, d.quantity, false, d.userId);
            MarketState.SubmitResult sr = state.submitOrder(order);

            return sr.accepted ? Result.ok(sr.fills) : Result.reject(sr.reason);
        }

        return Result.reject("unknown event type: " + e.getClass().getSimpleName());
    }

    /** Rebuilds market state from scratch by replaying an entire log. */
    public static MarketState replay(EventLog log) throws IOException {
        MarketState state = new MarketState();
        for (SequencedEvent se : log.readFrom(0)) {
            apply(state, se);
        }
        // A log written before market identity existed replays to nothing, because
        // every event fails the genesis check. Say so — silence here looks like an
        // empty market rather than an incompatible one.
        if (log.lastSeq() > 0 && state.marketId() == null) {
            System.err.println("[economiesmod] log has " + log.lastSeq() + " events but no"
                    + " MarketCreated genesis — it predates market identity and cannot be"
                    + " replayed. Reset the log and create or join a market.");
        }
        return state;
    }
    /** Checks whether an event would be accepted, without mutating state. */
    public static Result validate(MarketState state, SequencedEvent se) {
        if (se == null || se.event == null) return Result.reject("null event");

        Event e = se.event;
        if (e.userId == null) return Result.reject("missing userId");

        Result genesisCheck = checkGenesis(state, se);
        if (genesisCheck != null) return genesisCheck;

        if (e instanceof Event.MarketCreated || e instanceof Event.KeyRegistered) {
            return Result.ok(Collections.emptyList());
        }

        if (e instanceof Event.WelcomeGrant) {
            Event.WelcomeGrant wg = (Event.WelcomeGrant) e;
            if (wg.amount <= 0) return Result.reject("amount must be positive");
            if (wg.targetUserId == null) return Result.reject("missing targetUserId");
            if (state.hasBeenGranted(wg.targetUserId)) {
                return Result.reject("already granted in this market");
            }
            if (!state.isRegistered(wg.targetUserId)) {
                return Result.reject("cannot grant to an unregistered identity");
            }
            // Someone who held a position in a market that has been migrated from
            // already has a claim recorded here. Granting on top would let a group
            // concentrate its grants into one migrant and collect a second set.
            if (state.isAccountedElsewhere(wg.targetUserId)) {
                return Result.reject("already accounted for by a migration");
            }
            return Result.ok(Collections.emptyList());
        }

        if (e instanceof Event.MigrateBalance) {
            Event.MigrateBalance mb = (Event.MigrateBalance) e;
            if (mb.fromMarketId == null) return Result.reject("missing fromMarketId");
            if (mb.beneficiary == null) return Result.reject("missing beneficiary");
            if (mb.fromMarketId.equals(state.marketId())) {
                return Result.reject("cannot migrate a market into itself");
            }
            if (mb.credits < 0) return Result.reject("credits cannot be negative");
            if (mb.items != null) {
                for (Map.Entry<String, Long> entry : mb.items.entrySet()) {
                    if (entry.getKey() == null || entry.getKey().isEmpty()) {
                        return Result.reject("missing itemId in migration");
                    }
                    if (entry.getValue() == null || entry.getValue() < 0) {
                        return Result.reject("negative quantity in migration");
                    }
                }
            }
            if (state.hasMigrated(mb.fromMarketId, mb.beneficiary)) {
                return Result.reject("that market has already been migrated for this player");
            }
            // Migration is for someone arriving from outside. Anyone already holding a
            // position here has had their allowance from this market, and carrying a
            // second one in is how you mint: join, take the grant, reset, create your
            // own market, take that grant too, migrate it back, repeat.
            if (state.isRegistered(mb.beneficiary) || state.hasBeenGranted(mb.beneficiary)) {
                return Result.reject("you already hold a position in this market"
                        + " — migration is for joining from outside it");
            }
            return Result.ok(Collections.emptyList());
        }

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

        if (e instanceof Event.DepositAndList) {
            Event.DepositAndList d = (Event.DepositAndList) e;
            if (d.quantity <= 0) return Result.reject("quantity must be positive");
            if (d.price <= 0) return Result.reject("price must be positive");
            if (d.itemId == null || d.itemId.isEmpty()) return Result.reject("missing itemId");
            return Result.ok(Collections.emptyList());
        }

        return Result.reject("unknown event type: " + e.getClass().getSimpleName());
    }




}