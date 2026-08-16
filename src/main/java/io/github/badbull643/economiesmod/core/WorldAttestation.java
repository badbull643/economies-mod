package io.github.badbull643.economiesmod.core;

import java.util.ArrayList;
import java.util.List;

/**
 * What a client says about the world it is trading from.
 *
 * <h2>This is a claim, not evidence</h2>
 *
 * Every field here is supplied by the client and none of it can be verified. A modified
 * client fills this in with whatever it likes, and no amount of signing changes that —
 * a signature proves who said it, never that it was true. Anyone reading this class
 * expecting proof of honest play should stop here.
 *
 * <h2>What it is actually for</h2>
 *
 * Two things, and neither is trust.
 *
 * The first is the casual case. Most people who deposit creative-mode diamonds are not
 * running a modified client; they enabled cheats once, spawned some things, and thought
 * nothing of it. A truthful client reports that, and the server can decline. That is a
 * real and common case caught for almost no cost — but it is caught because the client
 * was honest, not because it was checked.
 *
 * The second is the useful one, and it is stolen from how client-spoof detection works
 * elsewhere in Minecraft: a vanilla client registers no plugin channels, so a client
 * claiming to be vanilla while registering channels has proven itself a liar without
 * anyone verifying the claim directly. The lie is caught by self-contradiction rather
 * than by inspection.
 *
 * The same shape applies here. Nothing can check whether a world is really 40 hours
 * old, but a world claiming to be 20 minutes old that has deposited four thousand
 * diamonds is making two statements that cannot both be true. The check does not need
 * to know which one is false.
 *
 * That makes the attestation self-limiting rather than merely optional. Claiming a
 * young world caps what may be deposited; claiming an old one to lift that cap is a
 * larger, more specific lie that a server operator can see in the record. Neither
 * direction is free, which is the most that can be had without a shared world.
 */
public class WorldAttestation {

    /** Total world age in ticks, as the client reports it. 20 ticks is one second. */
    public long worldAgeTicks;

    /** Whether commands are enabled for this world right now. */
    public boolean commandsAllowed;

    /** The world's game mode, as a plain string: "survival", "creative", ... */
    public String gameMode;

    public boolean hardcore;

    /**
     * A stable identifier for the world, derived from its seed rather than being it.
     *
     * Hashed because a seed is the whole world — publishing one to every host anybody
     * connects to hands out the location of everything in it. The hash keeps what this
     * is for, which is noticing that two identities are trading from the same world.
     */
    public String worldIdHash;

    public static final long TICKS_PER_HOUR = 72_000L;   // 20/s * 3600

    /** Play time the client claims, in hours. */
    public double claimedHours() {
        return worldAgeTicks <= 0 ? 0 : (double) worldAgeTicks / TICKS_PER_HOUR;
    }

    public boolean isCreative() {
        return "creative".equalsIgnoreCase(gameMode);
    }

    /**
     * Everything about this claim that a host might object to, in plain language.
     *
     * Returns the objections rather than a verdict, because what to do about them is
     * the operator's policy and not this class's business — one server refuses a
     * creative world outright, another only wants it in the log.
     *
     * @param depositedUnits how much this identity has actually handed over, which is
     *                       the only number here the host observed rather than was told
     */
    public List<String> objections(ServerConfig config, long depositedUnits) {
        List<String> out = new ArrayList<>();

        if (config.refuseCreativeWorlds && isCreative()) {
            out.add("this world is in creative mode");
        }
        if (config.refuseCheatWorlds && commandsAllowed) {
            out.add("this world has commands enabled");
        }

        // The contradiction check. Not "have you cheated" — unanswerable — but "is what
        // you have handed over possible in the time you say you have played". A liar has
        // to pick which statement to make false.
        if (config.maxDepositUnitsPerPlayHour > 0 && depositedUnits > 0) {
            double hours = claimedHours();
            long plausible = (long) Math.floor(hours * config.maxDepositUnitsPerPlayHour);
            if (depositedUnits > plausible) {
                out.add("deposited " + depositedUnits + " items from a world claiming "
                        + String.format("%.1f", hours) + " hours of play, where "
                        + plausible + " would be the most this server finds plausible");
            }
        }

        return out;
    }
}
