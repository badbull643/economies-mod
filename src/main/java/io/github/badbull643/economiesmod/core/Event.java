package io.github.badbull643.economiesmod.core;

import java.util.List;
import java.util.Map;
import java.util.UUID;


/*
* this write up is more so for my understanding so ,
*
*
*
*
* */

public abstract class Event {
    public UUID userId;
    public String clientEventId;
    public long timestamp;

    /**
     * Which market this event was authored for. Signed, so a signature proves not
     * just who wrote an event but where they meant it to go.
     *
     * Without this, a signed event lifted out of one market's log verifies perfectly
     * in another — so replaying someone's old deposits into a different market would
     * be indistinguishable from them making those deposits there.
     *
     * On MarketCreated this is the market being created; everywhere else it must
     * match the market the event is being applied to.
     */
    public UUID marketId;

    /**
     * A market's birth certificate. Legal only as seq 1, and seq 1 must be one of
     * these — so every market has an identity from its first line, and "different
     * market" stops being indistinguishable from "diverged history".
     */
    public static class MarketCreated extends Event {
        // marketId is inherited — for genesis it names the market being created.
        public String marketName;
        /** Registers the creator's key, so seq 1 is self-verifying with no prior state. */
        public String creatorPublicKey;
    }

    /**
     * Binds a userId to a public key, in the log itself.
     *
     * Self-certifying: signed by the private key matching the publicKey carried here,
     * so a replayer can verify the binding without trusting whoever recorded it. That
     * is what lets an exported log be checked by someone who was never present — the
     * keys travel with the history instead of in a separate file taken on faith.
     */
    public static class KeyRegistered extends Event {
        public String publicKey;
    }

    /**
     * Starting balance, issued by the host the first time an identity appears in this
     * market. Replaces ad-hoc credit injection: it is logged, replayable, visible to
     * everyone, and can only happen once per user per market.
     */

    //the welcome grant
    public static class WelcomeGrant extends Event {
        public UUID targetUserId;
        public long amount;
    }

    /**
     * The market's economic policy. Currently one number: the transaction tax.
     *
     * An event rather than a host setting, and that is not a stylistic choice. Every
     * client replays the log independently and must reach the same balances; a rate
     * that lived in a host's config would be invisible to replayers, so the moment a
     * host applied it the market would fork. Policy that changes settlement has to be
     * ordered in the log alongside the trades it changes.
     *
     * Basis points, not a percentage. A double would be one decimal-representation
     * difference away from two replicas computing different balances, which is the
     * cheapest imaginable way to fork a market. 100 bps = 1%.
     *
     * Non-retroactive for free: replay applies events in order, so fills sequenced
     * before this event settle at whatever rate was in force then. That falls out of
     * the design rather than being implemented, which is exactly why it is worth a
     * test — nothing in the code says it, so nothing protects it.
     */
    public static class MarketPolicy extends Event {
        /** Transaction tax on fills, in basis points. 0 means no tax. */
        public int taxBps;

        /**
         * What a new identity is granted on first registering.
         *
         * Here rather than in a host's config, and that is a correctness requirement
         * rather than tidiness. WelcomeGrant carries its own amount and is validated by
         * every replica; with nothing in the log to compare against, "is this the right
         * amount" was unanswerable and any signed grant for any sum validated. It also
         * stops two hosts of one market handing out different amounts depending on who
         * happened to be online.
         */
        public long grantAmount;

        /**
         * Flat credits charged for placing an order, whichever way it goes.
         *
         * Flat and not a percentage, because what it exists to discourage is the number
         * of orders rather than their size — a percentage would let somebody paper the
         * book with hundreds of one-credit orders for almost nothing, which is the
         * behaviour being priced.
         *
         * Not refunded when an order is cancelled. A refundable fee deters nothing. The
         * cost of that is real and is the reason this should stay small: cancelling and
         * relisting at a better price pays it twice, and repricing is something a
         * healthy market wants people doing.
         */
        public long listingFee;

        /**
         * How many orders an identity may hold open before the listing fee climbs.
         *
         * The fee it modifies is what makes producing market activity cost something,
         * which is the only reason paying a stipend per fill is not a mint. Zero means
         * every order pays the base fee, which is safe; a large allowance paired with a
         * generous stipend is not, and validate refuses that pairing.
         */
        public int listingFreeOrders;

        /**
         * Credits an identity may claim once per stipendEveryFills fills. Zero disables.
         *
         * The welcome grant solves a cold start and pays once. This solves supply. The
         * grant is otherwise the only source of credits, so money enters only when new
         * people do, while goods accrue for every hour anybody plays — and item prices
         * fall until they reach the integer floor of 1, where the price signal dies.
         *
         * Keyed to fills so supply tracks trade rather than noise, and because a fill is
         * the one thing that cannot be produced for free.
         */
        public long stipendAmount;

        /** Fills between claims. Ignored when stipendAmount is zero. */

        //comment here was confusing essentially every N (where N = stipendeverFIll) fills you get a stipend
        public long stipendEveryFills;
    }

    /**
     * One identity claiming the market's stipend.
     *
     * Self-authored, unlike WelcomeGrant. A grant is issued to somebody arriving, so it
     * has to be written by whoever is already here; a stipend is claimed by somebody who
     * already holds a key in this market and can sign for themselves. That removes the
     * question of who was sequencing, which is the same problem the grant amount had to
     * be moved into policy to escape.
     */
    public static class Stipend extends Event {
        public long amount;
    }

    /**
     * Carries a player's holdings across from a market they're abandoning.
     *
     * Authored by the host, which verified the incoming branch and recomputed the
     * position rather than trusting the claim. Balances only — never orders: a limit
     * price encodes information about the market it was set in, so re-listing is left
     * to the owner once they can see the destination's prices.
     *
     * foreignParticipants records everyone who held an identity in the abandoned
     * market. They aren't credited by this event; the list exists so that anyone in it
     * receives their own verified balance rather than a fresh welcome grant, which is
     * what stops a group concentrating grants into one migrant and collecting again on
     * the way in.
     */
    public static class MigrateBalance extends Event {
        public UUID fromMarketId;
        public String fromMarketName;
        public long fromHeadSeq;
        public String fromHeadHash;   // so the claim stays auditable by anyone holding that branch

        public UUID beneficiary;
        public long credits;
        public Map<String, Long> items;

        public List<UUID> foreignParticipants;
    }

    /**
     * Host rules a group has agreed once, published by the creator.
     *
     * <b>Defaults, never enforcement.</b> Nothing in {@code EventApplier.validate} reads
     * this, no replica has to agree about what it means, and a host that ignores it
     * produces a perfectly valid market. It exists because a group's economy is
     * otherwise only as protected as its most permissive host: a deposit cap that
     * applied on Tuesday and not Wednesday capped nothing, since Wednesday's goods are
     * in the ledger for good, and rotating to somebody who never opened
     * {@code host-config.json} was enough to do it.
     *
     * Host rules cannot <i>travel</i> — be replicated and enforced — for three separate
     * reasons the backlog records, any one of which is fatal. What they can do is be
     * written down where the next host will find them. That is all this is.
     *
     * <b>Every field is boxed, and that is the design.</b> A {@code MarketPolicy} event
     * is the whole policy, so anything it does not restate is set to zero — which once
     * silently wiped the stipend. Here null means "the group has never said", which is a
     * different thing from "the group said nought", and the two have to stay
     * distinguishable or publishing an admission list would quietly remove a deposit
     * cap. The publisher still builds from what is already published, so a partial
     * event is not expressible from the UI either; the boxing is the belt to that
     * braces.
     *
     * <b>The subset is deliberate.</b> Deposit caps, migration limits, the welcome-grant
     * ceiling and admission are things a group can sensibly agree. Attestation, the
     * world checks and bans are not here and should not be: "I do not want this person
     * on my machine" is a different decision from "this group excludes them", and making
     * the first mean the second is heavier than it looks.
     */
    public static class HostDefaults extends Event {
        public Long maxDepositUnitsPerWindow;
        public Integer depositWindowMinutes;
        public Long maxMigratedCredits;
        public Long maxWelcomeGrant;
        public Boolean acceptsMigration;
        public String admission;
        public List<String> allow;
        public List<String> deny;
    }

    public static class Deposit extends Event {
        public String itemId;
        public long quantity;
    }

    public static class Withdraw extends Event {
        public String itemId;
        public long quantity;
    }

    public static class PlaceOrder extends Event {
        public String itemId;
        public long price;
        public long volume;
        public boolean isBid;
    }

    public static class CancelOrder extends Event {
        public long orderId;
        public String itemId;
        public boolean isBid;
    }

    public static class DepositAndList extends Event {
        public String itemId;
        public long quantity;
        public long price;
    }

}