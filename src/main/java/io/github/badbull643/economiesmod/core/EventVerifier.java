package io.github.badbull643.economiesmod.core;

import java.security.PublicKey;

/**
 * Decides which key an event must be verified against, and verifies it.
 *
 * The key always comes from the log — either carried by the event itself, for the two
 * events that introduce an identity, or from the directory built by replaying earlier
 * events. Never from a side file.
 *
 * That matters because the host and every client must reach the same verdict about the
 * same event. When the host verified against its own known-keys.json while replicas
 * verified against the log, the two could disagree — the host accepting events its
 * clients would reject — and a market where participants disagree about what happened
 * is exactly what the signing exists to prevent.
 */
public class EventVerifier {

    /**
     * The public key this event should be checked against, or null if the log has no
     * key for its author.
     *
     * MarketCreated and KeyRegistered carry their own key: they are self-certifying,
     * and have to be, since they are what introduce an identity to the log in the
     * first place.
     */
    public static String keyFor(MarketState state, Event e) {
        if (e instanceof Event.MarketCreated) {
            return ((Event.MarketCreated) e).creatorPublicKey;
        }
        if (e instanceof Event.KeyRegistered) {
            return ((Event.KeyRegistered) e).publicKey;
        }
        return state.publicKeyOf(e.userId);
    }

    /** Returns null if the signature is good, otherwise why it isn't. */
    public static String verify(MarketState state, Event e, String signature) {
        if (signature == null || signature.isEmpty()) {
            return "unsigned event";
        }

        String keyString = keyFor(state, e);
        if (keyString == null) {
            return "author is not registered in this market";
        }

        PublicKey key;
        try {
            key = PlayerKeys.decodePublic(keyString);
        } catch (Exception ex) {
            return "malformed public key";
        }

        if (!PlayerKeys.verify(EventCanonical.canonicalPayload(e), signature, key)) {
            return "bad signature";
        }
        return null;
    }
}
