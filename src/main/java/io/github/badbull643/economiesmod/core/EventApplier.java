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

    /**
     * Why this stipend would pay out more than the fills to earn it cost, or null.
     *
     * The rule the stipend's safety rests on, and it was wrong twice over.
     *
     * It assumed a fill costs two listing fees, on the reasoning that two orders have to
     * cross. One order crossing a stacked book produces a fill per resting order it
     * consumes — twenty fills for one fee, measured — so the floor is one fee per fill,
     * paid by the resting side. Half what was assumed.
     *
     * And it counted one claimant. Every registered identity claims once per interval,
     * so the payout is multiplied by however many people are in the market while the
     * fees are not. Two colluders halved the real margin; ten wiped it out.
     *
     * Both corrected: the fees an interval can collect are listingFee per fill, and they
     * have to cover a payment to everyone entitled to one.
     */
    static String stipendOutpacesItsFees(long amount, long everyFills, long listingFee,
                                         int claimants) {
        if (amount <= 0) return null;
        if (listingFee <= 0) {
            return "a stipend needs a listing fee — without one, fills cost nothing to"
                    + " produce and anyone could trade with themselves for it";
        }
        long heads = Math.max(1, claimants);

        long collected;
        long paid;
        try {
            collected = Math.multiplyExact(listingFee, everyFills);
            paid = Math.multiplyExact(amount, heads);
        } catch (ArithmeticException overflow) {
            return "those figures are too large to compare safely";
        }

        if (paid >= collected) {
            return "a stipend of " + amount + " every " + everyFills + " trades pays "
                    + paid + " across " + heads + " registered "
                    + (heads == 1 ? "identity" : "identities")
                    + ", and " + everyFills + " trades collect at most " + collected
                    + " in listing fees — so producing the trades costs less than the"
                    + " stipend pays, and anyone could trade with themselves for it."
                    + " Raise the listing fee, lengthen the interval, or lower the"
                    + " stipend";
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

        if (e instanceof Event.Stipend) {
            Event.Stipend st = (Event.Stipend) e;
            state.wallets().adjust(st.userId, st.amount);
            state.markStipended(st.userId, state.fillsEver());
            return Result.ok(Collections.emptyList());
        }

        if (e instanceof Event.WelcomeGrant) {
            Event.WelcomeGrant wg = (Event.WelcomeGrant) e;
            state.wallets().adjust(wg.targetUserId, wg.amount);
            state.markGranted(wg.targetUserId);
            return Result.ok(Collections.emptyList());
        }

        if (e instanceof Event.MarketPolicy) {
            // Takes effect from here forward only. Fills already sequenced settled at
            // the rate in force when they were applied, which replay reproduces without
            // anything here having to know about it.
            Event.MarketPolicy applied = (Event.MarketPolicy) e;
            state.setTaxBps(applied.taxBps);
            state.setWelcomeGrant(applied.grantAmount);
            state.setListingFee(applied.listingFee);
            state.setListingFreeOrders(applied.listingFreeOrders);
            state.setStipend(applied.stipendAmount, applied.stipendEveryFills);
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
            if (sr.accepted) state.recordTrades(se.seq, e.timestamp, sr.fills);

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
            if (sr.accepted) state.recordTrades(se.seq, e.timestamp, sr.fills);

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

            // The amount must be the one this market publishes, not merely positive.
            //
            // Nothing checks who authors a grant, and nothing can: hosting rotates, so
            // a replica reading the log later cannot know who was sequencing at that
            // point. Without this line the author did not matter because the amount did
            // not either — any identity could sign itself a grant for any sum and every
            // replica would accept it. Two ways in: a server configured with a zero
            // grant never marks anyone granted, so hasBeenGranted below never fires; and
            // a grant authored in one's own local world migrates in at full value.
            //
            // Pinning the amount makes authorship moot. The most a liar can give
            // themselves is what an honest host would have given them anyway, once.
            if (wg.amount != state.welcomeGrant()) {
                return Result.reject("grant must be exactly this market's "
                        + state.welcomeGrant() + ", not " + wg.amount);
            }
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

        if (e instanceof Event.Stipend) {
            Event.Stipend st = (Event.Stipend) e;

            // Self-claimed, so there is no author question to answer — but the amount is
            // still the market's, for the same reason the welcome grant's is: nothing
            // can check who was sequencing, so the figure has to come from the log.
            // Asked first, because a market that has just switched the stipend off is
            // exactly when a client is holding the old figure — and being told the
            // amount is wrong sends somebody looking for the right one when there is
            // none.
            if (state.stipendAmount() <= 0) {
                return Result.reject("this market pays no stipend");
            }
            if (st.amount != state.stipendAmount()) {
                return Result.reject("stipend must be exactly this market's "
                        + state.stipendAmount() + ", not " + st.amount);
            }
            if (!state.isRegistered(st.userId)) {
                return Result.reject("only a registered identity can claim a stipend");
            }
            long since = state.fillsEver() - state.stipendedAtFill(st.userId);
            if (since < state.stipendEveryFills()) {
                return Result.reject("this market has settled " + since + " fills since"
                        + " your last claim, and pays every "
                        + state.stipendEveryFills());
            }

            // Asked again here, not only when the policy was written. The payout is per
            // identity, so it grows every time somebody joins while the fees an interval
            // collects do not — a stipend that was affordable for three people is a mint
            // at thirty. Checked from the log like everything else, so every replica
            // refuses the same claim.
            //
            // Refusing is the right failure: the alternative is paying out of a market
            // that cannot cover it, which is the thing the whole rule exists to stop.
            String unsafe = stipendOutpacesItsFees(state.stipendAmount(),
                    state.stipendEveryFills(), state.listingFee(),
                    state.registeredCount());
            if (unsafe != null) {
                return Result.reject("this market has outgrown its stipend — " + unsafe);
            }
            return Result.ok(Collections.emptyList());
        }

        if (e instanceof Event.MarketPolicy) {
            Event.MarketPolicy mp = (Event.MarketPolicy) e;

            // Bounds are checked here, not at the UI that offers the control, because
            // this is the gate every replica passes through. A fat-fingered 10000% must
            // be rejected identically by everyone rather than faithfully replayed into
            // a market where selling costs more than it earns.
            if (mp.taxBps < 0) {
                return Result.reject("tax cannot be negative");
            }
            if (mp.taxBps > MarketState.MAX_TAX_BPS) {
                return Result.reject("tax may not exceed "
                        + (MarketState.MAX_TAX_BPS / 100) + "%");
            }
            if (mp.grantAmount < 0) {
                return Result.reject("welcome grant cannot be negative");
            }
            if (mp.grantAmount > MarketState.MAX_WELCOME_GRANT) {
                return Result.reject("welcome grant may not exceed "
                        + MarketState.MAX_WELCOME_GRANT);
            }
            if (mp.listingFee < 0) {
                return Result.reject("listing fee cannot be negative");
            }
            if (mp.listingFreeOrders < 0) {
                return Result.reject("free order allowance cannot be negative");
            }
            if (mp.stipendAmount < 0) {
                return Result.reject("stipend cannot be negative");
            }
            if (mp.stipendAmount > MarketState.MAX_STIPEND) {
                return Result.reject("stipend may not exceed " + MarketState.MAX_STIPEND);
            }
            if (mp.stipendAmount > 0 && mp.stipendEveryFills < 1) {
                return Result.reject("a stipend needs an interval of at least one fill");
            }
            // The interlock, and the reason a stipend is not a mint.
            //
            // Credits paid per fill must cost more to earn than they are worth, or
            // anybody can trade with themselves indefinitely and print money. Producing
            // one fill means two orders crossing, so at least two listing fees at the
            // base rate — the rate a lone order pays, since that is the cheapest any
            // order can ever be.
            //
            // Checked here rather than left to whoever writes the config, because a
            // market whose policy is only safe when set carefully is a market that mints
            // the first time somebody is careless. Every replica reaches this verdict
            // from the log alone.
            if (mp.stipendAmount > 0) {
                String unsafe = stipendOutpacesItsFees(mp.stipendAmount,
                        mp.stipendEveryFills, mp.listingFee, state.registeredCount());
                if (unsafe != null) return Result.reject(unsafe);
            }
            if (mp.listingFee > MarketState.MAX_LISTING_FEE) {
                return Result.reject("listing fee may not exceed "
                        + MarketState.MAX_LISTING_FEE);
            }

            // Creator-signed. The market's own genesis names who may set its policy,
            // which is why bootstrapping with --creator-key records the operator rather
            // than the server: compromising a host then buys no authority over the rate.
            if (state.creator() == null) {
                return Result.reject("this market has no creator recorded");
            }
            if (!state.creator().equals(e.userId)) {
                return Result.reject("only the market's creator can set policy");
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