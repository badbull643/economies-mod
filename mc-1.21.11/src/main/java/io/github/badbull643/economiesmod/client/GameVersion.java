package io.github.badbull643.economiesmod.client;

/**
 * The Minecraft version this build is for, which is the version its market belongs to.
 *
 * A constant rather than something read from the running game, because the build's
 * fabric.mod.json already pins Minecraft to exactly this version: a copy of this jar that
 * loads at all is on it. Said on every handshake and compared against what the host serves,
 * so a player here is never put in a market with players on another version — see
 * GameVersions in core for why that matters and how 1.16.5 is written.
 */
public final class GameVersion {

    private static final String CURRENT = "1.21.11";

    private GameVersion() {}

    public static String current() {
        return CURRENT;
    }
}
