package io.github.badbull643.economiesmod.core;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.UUID;

/**
 * Creates a market — that is, writes the MarketCreated genesis event that gives a
 * log its identity.
 *
 * This exists as its own step, separate from hosting, on purpose. A market that can
 * be born as a side effect of clicking Host is a market that gets born by accident,
 * and two accidentally-born markets can never be merged (their histories share no
 * common ancestor, and interleaving them would invent trades that never happened).
 * Making creation explicit is the cheap fix for that; everything else is cleanup
 * after the fact.
 */
public class MarketBootstrap {

    /**
     * Writes the genesis event into an empty log.
     *
     * @throws IOException if the log already has history — a market cannot be
     *                     created twice, and an existing log already has an identity.
     */
    public static Event.MarketCreated createMarket(EventLog log, UUID creator,
                                                   String marketName, PlayerKeys keys)
            throws IOException {
        return createMarket(log, creator, marketName, keys,
                ServerConfig.DEFAULT_WELCOME_GRANT);
    }

    /**
     * As above, with the welcome grant this market will hand newcomers.
     *
     * The amount is written into the log as policy rather than left to whoever happens
     * to be hosting, and it is written whatever the figure is — including the default.
     *
     * Recording it unconditionally is the point. It used to be written only when it
     * differed from the default, so a market created on the default carried no policy
     * at all and fell back to a constant. That reads as the same thing and is not: a
     * fallback cannot be compared against, so a server later configured for some other
     * amount would sign grants that every replica refused, with nothing in the log to
     * say which of the two was the market's. An explicit event costs one line at
     * genesis and makes the figure a decision somebody made rather than one nobody did.
     */
    public static Event.MarketCreated createMarket(EventLog log, UUID creator,
                                                   String marketName, PlayerKeys keys,
                                                   long welcomeGrant)
            throws IOException {
        return createMarket(log, creator, marketName, keys, welcomeGrant, 0, 0);
    }

    /**
     * As above, with the opening listing fee and stipend as well.
     *
     * These exist for the server that has no creator behind it. A dedicated server
     * bootstrapping the ordinary way is its own creator, and a server has no screen to
     * set policy from later — so without carrying them here, a market it created could
     * never charge a listing fee or pay a stipend at all.
     *
     * Genesis is applied to a scratch state on the way out, so a market cannot be
     * written in a shape that would be refused on replay. Getting that wrong would leave
     * a log that is invalid from its first two events, which nothing downstream can
     * recover from — and the stipend interlock is exactly the kind of rule a
     * misconfigured server would trip.
     */
    public static Event.MarketCreated createMarket(EventLog log, UUID creator,
                                                   String marketName, PlayerKeys keys,
                                                   long welcomeGrant, long listingFee,
                                                   long stipendAmount)
            throws IOException {

        if (log.lastSeq() != 0) {
            throw new IOException("log already holds a market — reset it first");
        }
        if (marketName == null || marketName.trim().isEmpty()) {
            throw new IOException("a market needs a name");
        }

        Event.MarketCreated mc = new Event.MarketCreated();
        mc.marketId = UUID.randomUUID();
        mc.marketName = marketName.trim();
        mc.userId = creator;
        // Genesis registers its own author, so seq 1 verifies against nothing but itself.
        mc.creatorPublicKey = keys.publicKeyString();
        mc.clientEventId = UUID.randomUUID().toString();
        mc.timestamp = System.currentTimeMillis();

        String signature;
        try {
            signature = keys.sign(EventCanonical.canonicalPayload(mc));
        } catch (GeneralSecurityException e) {
            throw new IOException("could not sign genesis event: " + e.getMessage(), e);
        }

        log.append(mc, signature);

        // Straight after genesis, so the market never exists in a state where its own
        // grant is unstated. Signed by the creator because policy is creator-gated, and
        // at this point the creator is the only identity the log knows.
        Event.MarketPolicy mp = new Event.MarketPolicy();
        mp.userId = creator;
        mp.marketId = mc.marketId;
        mp.taxBps = 0;
        mp.grantAmount = welcomeGrant;
        mp.listingFee = listingFee;
        mp.stipendAmount = stipendAmount;
        mp.stipendEveryFills = stipendAmount > 0
                ? MarketState.DEFAULT_STIPEND_EVERY_FILLS : 0;
        mp.clientEventId = UUID.randomUUID().toString();
        mp.timestamp = System.currentTimeMillis();

        // Replayed against a scratch state before it is trusted. Both events are already
        // written by the time the second is checked, so a refusal here means deleting
        // the log rather than leaving half a market behind.
        MarketState scratch = new MarketState();
        SequencedEvent probeGenesis = new SequencedEvent();
        probeGenesis.seq = 1;
        probeGenesis.event = mc;
        EventApplier.apply(scratch, probeGenesis);

        SequencedEvent probePolicy = new SequencedEvent();
        probePolicy.seq = 2;
        probePolicy.event = mp;
        EventApplier.Result opening = EventApplier.validate(scratch, probePolicy);
        if (!opening.accepted) {
            throw new IOException("this market's opening policy would be refused by"
                    + " every replica, including its own: " + opening.reason);
        }

        try {
            log.append(mp, keys.sign(EventCanonical.canonicalPayload(mp)));
        } catch (GeneralSecurityException e) {
            throw new IOException("could not sign the market's opening policy: "
                    + e.getMessage(), e);
        }

        System.out.println("[economiesmod] created market '" + mc.marketName
                + "' (" + mc.marketId + ") — welcome grant " + welcomeGrant
                + (listingFee > 0 ? ", listing fee " + listingFee : "")
                + (stipendAmount > 0 ? ", stipend " + stipendAmount + " every "
                        + mp.stipendEveryFills + " trades" : ""));
        return mc;
    }
}
