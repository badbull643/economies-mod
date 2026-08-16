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
        System.out.println("[economiesmod] created market '" + mc.marketName
                + "' (" + mc.marketId + ")");
        return mc;
    }
}
