package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.GameVersions;

/**
 * The Minecraft version this build is for, which is the version its market belongs to.
 *
 * 1.16.5 is the one the wire writes as nothing — see GameVersions in core — so this build
 * says exactly what it always said, and every 1.16.5 host and client already out there keeps
 * working with it unchanged.
 */
public final class GameVersion {

    private GameVersion() {}

    public static String current() {
        return GameVersions.LEGACY;
    }
}
