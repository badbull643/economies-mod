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
     *
     * Public because MarketScreen has to ask it too, and could not: it lives in core and
     * the screen lives in client, so the screen kept its own copy — the doubled one this
     * used to have. It advertised a ceiling four times the real one and then let the
     * engine do the refusing. Anything that wants to know this must call this.
     */
    public static String stipendOutpacesItsFees(long amount, long everyFills,
                                                long listingFee, int claimants) {
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

    /**
     * Settles one event, all of it or none of it as far as any reader can tell.
     *
     * The whole body runs under the state's write lock. Settling a single fill credits
     * the buyer's items and the seller's money in separate steps, and a reader landing
     * between them sees money gone and goods not yet arrived — a frame of a market that
     * never existed. Per-collection monitors cannot fix that; only holding one lock
     * across the whole event can.
     *
     * Nothing slow happens in here: no I/O, no network, no callbacks out. The log write
     * and the broadcast are the caller's, and both are outside this.
     */
    public static Result apply(MarketState state, SequencedEvent se) {
        if (se == null || se.event == null) return Result.reject("null event");
        state.writeLock().lock();
        try {
            return applyLocked(state, se);
        } finally {
            state.writeLock().unlock();
        }
    }

    private static Result applyLocked(MarketState state, SequencedEvent se) {
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

        if (e instanceof Event.HostDefaults) {
            // Recorded and nothing more. No rule anywhere reads this to decide whether
            // something is allowed — a host consults it when it starts and may disagree,
            // which is what keeps it defaults rather than enforcement and is why it
            // cannot fork a market however hosting rotates.
            state.setHostDefaults((Event.HostDefaults) e);
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

            // Asked before the deposit, and asked of the same function validate asks.
            // Refusing after depositing left the goods in the ledger on an event whose
            // author had just been told it failed — and the client answers a refusal by
            // handing the physical items back, so they existed twice.
            MarketState.SubmitResult listable =
                    state.canDepositAndList(d.userId, d.itemId, d.quantity, d.price);
            if (!listable.accepted) return Result.reject(listable.reason);

            state.deposit(d.userId, d.itemId, d.quantity);
            Order order = new Order(se.seq, d.price, d.itemId, d.quantity, false, d.userId);
            MarketState.SubmitResult sr = state.submitOrder(order);
            if (sr.accepted) state.recordTrades(se.seq, e.timestamp, sr.fills);

            return sr.accepted ? Result.ok(sr.fills) : Result.reject(sr.reason);
        }

        return Result.reject("unknown event type: " + e.getClass().getSimpleName());
    }

    /**
     * A replayed state together with where the replay actually finished.
     *
     * The two belong together and must be read together. EventLog caches lastSeq when it
     * is constructed while readFrom re-reads the file, so asking the log where it ends
     * after replaying it can give an answer from before the replay — see replayWithHead.
     */
    public static final class Replayed {
        public final MarketState state;
        public final long headSeq;
        public final String headHash;
        /**
         * Seq of the first entry that fails the chain check, or -1 if the chain is
         * whole. Only set by {@link #verifyAndReplay}; a plain replay does not check,
         * and says so by leaving this -1 rather than by claiming the chain is good.
         */
        public final long chainBrokenAt;

        /**
         * The snapshot this load started from, or 0 if it read the whole log.
         *
         * Here so the console can say which happened. A snapshot is invisible when it
         * works — the same line was printed either way — and this project has already
         * learned twice that a mechanism whose failure is quiet needs its success to be
         * loud, or nobody can tell the two apart from outside.
         */
        public final long restoredFrom;

        /**
         * Whether the log itself holds the events this state was built from.
         *
         * Normally yes, trivially: the state came from walking the log. It is false in
         * exactly one case — a snapshot restored on its own authority, by a replica that
         * keeps no history at all. That is a client of a dedicated market, and it is the
         * case the Host gate exists for: it would bind a port, advertise a market, and
         * have no lines to send anybody who joined.
         *
         * Here because there is no cheap way to ask afterwards, and the obvious way to
         * ask is wrong. {@code log.lastSeq()} is primed from the state after a load, so
         * a slot with a nought-byte log answers with the state's head — which is exactly
         * what the Host gate asked, and exactly why it let a replica with no history
         * offer to serve one.
         *
         * <b>Not a statement that the log can be read through.</b> It never was on the
         * ordinary path, where a walk that stops at a damaged line reports the state it
         * built and calls it covered; for one session the snapshot path answered a
         * stricter question by accident, and charged every load a full pass over the
         * file for it. Damage is {@link #chainBrokenAt}'s question, and "could I serve
         * this history to somebody" is asked at the moment somebody tries — see
         * {@code MarketStateHolder.startHosting}.
         */
        public final boolean logCoversHead;

        Replayed(MarketState state, long headSeq, String headHash) {
            this(state, headSeq, headHash, -1, 0, true);
        }

        Replayed(MarketState state, long headSeq, String headHash, long chainBrokenAt) {
            this(state, headSeq, headHash, chainBrokenAt, 0, true);
        }

        Replayed(MarketState state, long headSeq, String headHash, long chainBrokenAt,
                 long restoredFrom, boolean logCoversHead) {
            this.state = state;
            this.headSeq = headSeq;
            this.headHash = headHash;
            this.chainBrokenAt = chainBrokenAt;
            this.restoredFrom = restoredFrom;
            this.logCoversHead = logCoversHead;
        }

        /**
         * How this load happened, in words, for the console.
         *
         * One phrasing in one place, because two call sites print it and a market that
         * says "replayed 140 events" on one path and something else on the other is a
         * difference somebody will read as a bug.
         */
        public String describe() {
            if (restoredFrom <= 0) return "replayed " + headSeq + " events";
            long since = headSeq - restoredFrom;
            return "restored a snapshot at event " + restoredFrom + " and replayed "
                    + since + (since == 1 ? " event" : " events") + " since";
        }
    }

    /** Rebuilds market state from scratch by replaying an entire log. */
    public static MarketState replay(EventLog log) throws IOException {
        return replayWithHead(log).state;
    }

    /**
     * The same, saying which event the state actually ends at.
     *
     * For anyone who needs to know both, which is anyone about to apply more events on
     * top. Taking the state from here and the position from {@code log.lastSeq()} reads
     * as equivalent and is not: lastSeq is cached when the EventLog is constructed and
     * readFrom re-reads the file every call, so if anything appends between the two the
     * position lands behind the state built beside it.
     *
     * That is not hypothetical and not a rare race. Starting a host signals "bound"
     * before it issues its opening welcome grants, and the self-connect that follows
     * opens a second EventLog on the very file those grants are being appended to. The
     * client then believed it was at seq 4 while holding state through seq 5, told the
     * host so, was sent event 5 again, and applied a welcome grant to itself twice —
     * ending the session a grant richer than the host it was mirroring. Which is replica
     * divergence, the one thing this whole design exists to prevent, and it was silent
     * until the client started validating what the host sends.
     */
    public static Replayed replayWithHead(EventLog log) throws IOException {
        return walkLog(log, false);
    }

    /**
     * Verifies the chain and replays it in **one** pass over the file.
     *
     * For the load paths, which wanted both and asked for them separately —
     * {@code verifyChain()} then {@code replay()}, each parsing every line, and on the
     * world-load path {@code damageReason()} asking for a third. Parsing was 44% of a
     * load and half of it was this.
     *
     * Deliberately the same answers as asking separately, including the odd one: a
     * broken chain does **not** stop the replay. Everything parseable is applied and the
     * first bad seq is reported alongside, because that is what the callers already did
     * and what the UI expects — the market is shown, with a banner saying it is damaged
     * and a Reset button. Stopping the replay at the break would empty somebody's market
     * on the screen where they go to fix it.
     */
    public static Replayed verifyAndReplay(EventLog log) throws IOException {
        return walkLog(log, true);
    }

    /**
     * Loads a market the fast way when it can and the honest way when it cannot, and
     * keeps the snapshot beside the log up to date.
     *
     * This is what a load path should call. It is the only place that decides whether a
     * snapshot is used, and the decision is made on the length of the log and nothing
     * else — never on who is hosting. Both deployments reach the same lengths; a rule
     * that read {@code dedicated} here would be the fifth defect of that shape in this
     * codebase, and there is no sense in which a snapshot should mean something
     * different on a dedicated server.
     *
     * When a snapshot is used, the events below it are not re-verified. See
     * {@link MarketSnapshot} for why that is safe in both directions a log can be wrong.
     */
    public static Replayed load(EventLog log) throws IOException {
        MarketSnapshot.Restored snap = MarketSnapshot.loadIfValid(log);
        Replayed result = snap == null ? verifyAndReplay(log) : replayTail(log, snap);
        // Both walks above read through to the last entry, so the log need not go and
        // find its own head — which on the snapshot path would be the one full pass over
        // the file left, and would undo the whole point of having a snapshot.
        log.headIs(result.headSeq, result.headHash);
        maybeSnapshot(log, result, snap);
        return result;
    }

    /**
     * The same answer as {@link #load}, for somebody who is only looking.
     *
     * {@code load} is a load path, and does two things a question has no business
     * doing: it primes the log's head from the state it just built, and it writes a
     * snapshot once the log has run far enough. Both are right for a world opening.
     * Neither is right for the reset confirmation, which asks this from a button
     * handler about a branch that is usually about to be deleted — a primed head is
     * the exact shape of the bug that let a historyless replica offer to host, and a
     * snapshot written for a history being discarded is one more thing to delete.
     *
     * The plain path does not verify the chain, matching {@link #replay} rather than
     * {@code load}: a caller here is asking what this market holds, not whether the log
     * is sound, and {@link #verifyAndReplay} is where that second question lives. Below
     * a snapshot nothing is re-verified, exactly as in {@code load}.
     */
    public static Replayed inspect(EventLog log) throws IOException {
        MarketSnapshot.Restored snap = MarketSnapshot.loadIfValid(log);
        return snap == null ? replayWithHead(log) : replayTail(log, snap);
    }

    /** Carries on from a restored snapshot, verifying only what came after it. */
    private static Replayed replayTail(EventLog log, MarketSnapshot.Restored snap)
            throws IOException {
        MarketState state = snap.state;
        final long[] head = { snap.seq };
        final String[] hash = { snap.chainHash };
        final String[] expectedPrev = { snap.chainHash };
        final long[] expectedSeq = { snap.seq + 1 };
        final long[] broken = { -1 };

        log.forEachAfter(snap.seq, se -> {
            if (broken[0] == -1
                    && (se.seq != expectedSeq[0]
                        || !expectedPrev[0].equals(se.prevHash)
                        || !EventLog.recomputeHash(se).equals(se.hash))) {
                broken[0] = se.seq;
            }
            expectedPrev[0] = se.hash;
            expectedSeq[0] = se.seq + 1;

            apply(state, se);
            head[0] = se.seq;
            hash[0] = se.hash;
            return true;
        });

        long broke = broken[0];
        if (broke == -1 && log.unreadableAtSoFar() != -1) broke = log.unreadableAtSoFar();

        // Asked of the snapshot rather than of the file, and that is the whole cost of
        // it. A snapshot accepted against the log's own hash has a log under it by
        // definition — loadIfValid found the hash it names, at the sequence number it
        // names — so the only state a log does not account for is the logless kind, and
        // the snapshot already knows which it is. If the tail added anything on top,
        // there is a log here whatever the file said a moment ago.
        //
        // This was log.headSeqOnDisk(), which is forEach(0, …): a parse of every line in
        // the file, on the one path that exists to not do that. It cost the snapshot
        // three quarters of its benefit — 740 ms of a 900 ms load at 100,000 events —
        // and it was invisible because nothing in CI measures speed. What it bought was
        // never the question this field asks: it also went false for a log with an
        // unparseable line below the snapshot point, which is damage, and damage is
        // chainBrokenAt's job. See MarketStateHolder.startHosting, which asks about
        // readability once, when somebody is about to serve a history, rather than on
        // every load of every world.
        boolean covered = !snap.logless || head[0] > snap.seq;
        return new Replayed(state, head[0], hash[0], broke, snap.seq, covered);
    }

    /**
     * Writes a snapshot when the log has run far enough past the last one to be worth it.
     *
     * Never for a damaged chain, and never for a log with no market in it — a snapshot
     * of a state nobody should be using is a way to keep using it.
     */
    private static void maybeSnapshot(EventLog log, Replayed result,
                                      MarketSnapshot.Restored from) {
        if (result.chainBrokenAt != -1) return;
        if (result.state.marketId() == null) return;
        // When, and only when — MarketSnapshot owns the thresholds and how they combine.
        if (!MarketSnapshot.worthWriting(result.headSeq, from == null ? -1 : from.seq)) {
            return;
        }

        try {
            MarketSnapshot.save(log, result.state, result.headSeq, result.headHash);
            System.out.println("[economiesmod] wrote a snapshot at event "
                    + result.headSeq + " — the next load starts from there");
        } catch (Exception e) {
            // A snapshot is an optimisation. Failing to write one costs a slower load
            // next time and must never cost a market.
            System.err.println("[economiesmod] could not write snapshot: " + e);
        }
    }

    private static Replayed walkLog(EventLog log, boolean verify) throws IOException {
        MarketState state = new MarketState();
        final long[] head = { 0 };
        final String[] hash = { EventLog.GENESIS_HASH };
        final String[] expectedPrev = { EventLog.GENESIS_HASH };
        final long[] expectedSeq = { 1 };
        final long[] broken = { -1 };

        log.forEach(0, se -> {
            if (verify && broken[0] == -1
                    && (se.seq != expectedSeq[0]
                        || !expectedPrev[0].equals(se.prevHash)
                        || !EventLog.recomputeHash(se).equals(se.hash))) {
                broken[0] = se.seq;
            }
            expectedPrev[0] = se.hash;
            expectedSeq[0] = se.seq + 1;

            apply(state, se);
            // From the events actually replayed, never from the log's own idea of where
            // it ends. This line is the whole point of this method.
            head[0] = se.seq;
            hash[0] = se.hash;
            return true;
        });

        long headSeq = head[0];
        long chainBrokenAt = broken[0];
        // A line this build cannot parse is a damaged log too, and forEach stops at it
        // rather than carrying the chain across a gap. verifyChain answers that case
        // without walking, so asking it here costs nothing and keeps one definition of
        // what "broken at" means.
        if (verify && chainBrokenAt == -1 && log.unreadableAtSoFar() != -1) {
            chainBrokenAt = log.unreadableAtSoFar();
        }

        // A log written before market identity existed replays to nothing, because
        // every event fails the genesis check. Say so — silence here looks like an
        // empty market rather than an incompatible one.
        if (headSeq > 0 && state.marketId() == null) {
            System.err.println("[economiesmod] log has " + headSeq + " events but no"
                    + " MarketCreated genesis — it predates market identity and cannot be"
                    + " replayed. Reset the log and create or join a market.");
        }
        return new Replayed(state, headSeq, hash[0], chainBrokenAt);
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

        if (e instanceof Event.HostDefaults) {
            Event.HostDefaults hd = (Event.HostDefaults) e;

            // Creator-signed, exactly as policy is. Anybody being able to publish the
            // group's rules would make this a way to tell every future host to stop
            // capping deposits, which is the opposite of what it is for.
            if (state.creator() == null) {
                return Result.reject("this market has no creator recorded");
            }
            if (!state.creator().equals(e.userId)) {
                return Result.reject("only the market's creator can publish host rules");
            }

            // Bounded here even though nothing enforces them, because a published figure
            // is read by every future host and a nonsense one would be adopted by all of
            // them. Refusing at the gate every replica passes is the same reasoning as
            // MarketPolicy's, for a value that is advice rather than law.
            if (hd.maxDepositUnitsPerWindow != null && hd.maxDepositUnitsPerWindow < 0) {
                return Result.reject("deposit cap cannot be negative");
            }
            if (hd.depositWindowMinutes != null && hd.depositWindowMinutes < 1) {
                return Result.reject("a deposit window needs at least one minute");
            }
            if (hd.maxMigratedCredits != null && hd.maxMigratedCredits < 0) {
                return Result.reject("migrated-credit cap cannot be negative");
            }
            if (hd.maxWelcomeGrant != null && hd.maxWelcomeGrant < 0) {
                return Result.reject("welcome-grant ceiling cannot be negative");
            }
            if (hd.admission != null && !ServerConfig.isAdmissionMode(hd.admission)) {
                return Result.reject("unknown admission mode '" + hd.admission + "'");
            }
            if (hd.maxDepositUnitsPerPlayHour != null && hd.maxDepositUnitsPerPlayHour < 0) {
                return Result.reject("deposit-per-play-hour cap cannot be negative");
            }
            if (hd.maxDepositMultipleOfHandled != null && hd.maxDepositMultipleOfHandled < 0) {
                return Result.reject("deposit multiple cannot be negative");
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
            //
            // hasMigratedIn is the third of these and was missing, which meant the rule
            // described above did not hold against the attack it names. A migration
            // registers nobody and grants nobody, so neither of the other two tests is
            // ever true of somebody who has only migrated — and hasMigrated above is
            // keyed to the *source* market, which is a fresh random id every time
            // somebody creates one. So the same identity could create a market at the
            // grant ceiling, take it, migrate in, reset, and do it again, without limit
            // and without ever registering here. Measured at four million credits in
            // four passes against a market whose founder had fifty.
            //
            // It asks about this beneficiary and nobody else. isAccountedElsewhere was
            // tried here first and is wrong: it holds everyone who was registered in a
            // market somebody migrated out of, so the first arrival from a shared market
            // filed all their friends and the second was turned away as though they had
            // already been paid. Two people leaving one market together is the ordinary
            // case — see M6e, which exists because M6b only ever tested one person
            // arriving repeatedly and so had nothing to say about it.
            if (state.isRegistered(mb.beneficiary) || state.hasBeenGranted(mb.beneficiary)
                    || state.hasMigratedIn(mb.beneficiary)) {
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
            // The listing fee, which this used not to ask about at all — so a seller
            // who could not afford to list passed validate, was written into the log,
            // and was refused by apply after the deposit had already landed.
            MarketState.SubmitResult listable =
                    state.canDepositAndList(d.userId, d.itemId, d.quantity, d.price);
            return listable.accepted ? Result.ok(Collections.emptyList())
                    : Result.reject(listable.reason);
        }

        return Result.reject("unknown event type: " + e.getClass().getSimpleName());
    }




}
