package io.github.badbull643.economiesmod.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /**
     * Whether the world was created with cheats enabled, from its saved settings.
     *
     * Not the whole answer on its own — see {@link #cheatsLive}.
     */
    public boolean commandsAllowed;

    /**
     * Whether commands are available right now, whatever the world was created with.
     *
     * Open to LAN with "Allow Cheats" ticked calls PlayerManager.setCheatsAllowed and
     * never touches the saved level settings, so a world created without cheats reports
     * commandsAllowed false for the rest of its life while /give works perfectly well.
     * Checking only the saved flag would have made that the obvious way past every
     * cheat-related rule here.
     *
     * Read from the running server rather than the save, which is why both exist: the
     * pair also distinguishes "always had cheats" from "turned them on this session",
     * and the second is the more interesting one to see in a log.
     */
    public boolean cheatsLive;

    /**
     * Whether this world has ever been seen with commands enabled, at any point.
     *
     * Neither of the fields above survives a reload. Open to LAN sets a runtime flag
     * and writes nothing to the save, so quitting to the title and coming back leaves a
     * world that honestly reports having never had commands — while whatever was taken
     * with them is still in the player's inventory. Enable, take, reload, connect was
     * the whole bypass.
     *
     * Recorded by the client into the world's own folder the first time it sees them,
     * and only ever added to. Deletable by anyone who goes looking, which is the same
     * ceiling as everything else here.
     */
    public boolean cheatsEverSeen;

    /** Every route to commands, including one the world no longer admits to. */
    public boolean cheatsAvailable() {
        return commandsAllowed || cheatsLive || cheatsEverSeen;
    }

    /** Cheats switched on for this session in a world that was not made with them. */
    public boolean cheatsEnabledLater() {
        return cheatsLive && !commandsAllowed;
    }

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

    /**
     * Item id to how much of it this player has ever mined, crafted or picked up.
     *
     * Minecraft's own statistics, which it keeps during ordinary play and which /give
     * does not touch — GiveCommand increments nothing, an item picked up off the floor
     * goes through ItemEntity and increments PICKED_UP. That makes this the only figure
     * here the player did not author and cannot quietly restate, and the only one that
     * survives the mod being switched off, because the game maintains it regardless.
     *
     * Sent per item, immediately before depositing that item, rather than as a whole
     * inventory at the handshake: the host needs one number at one moment, and shipping
     * a map of every item anybody has ever touched to answer it would be absurd.
     *
     * Picked up counts net of dropped, because picking something up raises the figure
     * regardless of where it came from — including an item thrown on the ground a
     * second earlier. Give, drop, collect would otherwise launder anything into the
     * statistic meant to be evidence against it, once per cycle and repeatable.
     *
     * Undercounts by design. Smelted output and anything taken from a chest never touch
     * PICKED_UP, and handing items to a friend counts against the giver, so the number
     * is a floor on what somebody has handled rather than a measure of it — which is
     * why the rule built on it is a generous multiple.
     */
    public Map<String, Long> handledByItem;

    /** What this attestation claims for one item, or 0 when it says nothing about it. */
    public long handledOf(String itemId) {
        if (handledByItem == null || itemId == null) return 0;
        Long n = handledByItem.get(itemId);
        return n == null ? 0 : n;
    }

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
        if (config.refuseCheatWorlds && cheatsAvailable()) {
            // Named separately, because the two are not equally suspicious. A world made
            // with cheats is usually somebody who ticked a box a year ago; cheats
            // switched on mid-session, in a world that did not have them, is the shape
            // of somebody who wanted something.
            String how;
            if (cheatsEnabledLater()) {
                how = "commands were switched on in this world after it was created";
            } else if (commandsAllowed) {
                how = "this world has commands enabled";
            } else {
                // Neither flag is set, so the world would describe itself as clean; the
                // only reason we know otherwise is that it was seen earlier.
                how = "this world has had commands enabled at some point";
            }
            out.add(how);
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
