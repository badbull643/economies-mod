package io.github.badbull643.economiesmod.core;

/**
 * Which Minecraft version a market's players are on, and how that is said on the wire.
 *
 * A market belongs to one Minecraft version. The mod ships one build per version from a
 * shared engine, so everything here is about keeping those builds apart: items are named
 * differently, the rules of the game differ, and a trade that one side cannot make good
 * on is worse than a trade that never happened. Nothing in the engine knows what a version
 * means — it only compares them — so this works the same on a dedicated server, which has
 * no game to ask.
 *
 * <b>1.16.5 is written as nothing.</b> It was the only version for as long as these
 * messages have existed, so a Hello, a Sync or a probe reply with no version in it came from
 * a 1.16.5 build and is read as one. That keeps the 1.16.5 line byte-for-byte compatible
 * with every build already in the wild — an older client's messages still mean what they
 * did, and a newer 1.16.5 host's signed probe reply is still the payload an older client
 * expects — and it means any other version is, by construction, a message an older build
 * cannot mistake for its own.
 */
public final class GameVersions {

    /** The version every build before this setting existed was running. */
    public static final String LEGACY = "1.16.5";

    private GameVersions() {}

    /** A version as it should be compared: trimmed, with "not said" meaning {@link #LEGACY}. */
    public static String normalize(String version) {
        if (version == null) return LEGACY;
        String v = version.trim();
        return v.isEmpty() ? LEGACY : v;
    }

    /** What to put on the wire: nothing for 1.16.5, the version itself for anything else. */
    public static String toWire(String version) {
        String v = normalize(version);
        return LEGACY.equals(v) ? null : v;
    }

    public static boolean same(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    /** True when somebody has actually said, as opposed to "not set". */
    public static boolean isSet(String version) {
        return version != null && !version.trim().isEmpty();
    }
}
