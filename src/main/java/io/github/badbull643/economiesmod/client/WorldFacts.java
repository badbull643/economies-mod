package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.WorldAttestation;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.ServerStatHandler;
import net.minecraft.stat.Stats;
import net.minecraft.world.SaveProperties;

import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * Reads the local world and describes it, for a host that has asked.
 *
 * The whole of the client's side of attestation. It reports honestly — this mod has no
 * reason to lie about its own player — which is exactly why it stops only the people
 * who are not lying either. See WorldAttestation for what that is worth.
 *
 * Kept out of core, like everything that touches Minecraft.
 */
public final class WorldFacts {

    private WorldFacts() {}

    /**
     * How much of one item this player has ever handled, by Minecraft's own reckoning.
     *
     * Mined plus crafted plus picked up. Vanilla maintains these during ordinary play
     * and writes them to the world's stats folder, and — this is the part that matters —
     * /give increments none of them: GiveCommand calls insertStack and touches no
     * statistic, while an item picked up off the ground goes through ItemEntity, which
     * does. Checked in the jar rather than assumed.
     *
     * So this is the closest thing to a provenance record that exists without anybody
     * building one. It survives world reloads, and unlike the mod's own note it survives
     * the mod being switched off, because the game keeps it either way.
     *
     * It undercounts on purpose-built routes — smelted output and anything taken from a
     * chest never touch PICKED_UP — which is why what is done with it is a generous
     * multiple rather than a limit.
     */
    public static long handledCountOf(MinecraftServer server, String itemId) {
        if (server == null || itemId == null) return 0;
        try {
            if (server.getPlayerManager() == null
                    || server.getPlayerManager().getPlayerList().isEmpty()) {
                return 0;
            }
            ServerPlayerEntity player = server.getPlayerManager().getPlayerList().get(0);
            ServerStatHandler stats = player.getStatHandler();
            if (stats == null) return 0;

            Item item = MinecraftIds.idToItem(itemId);
            if (item == null || item == Items.AIR) return 0;

            long total = stats.getStat(Stats.PICKED_UP.getOrCreateStat(item));
            total += stats.getStat(Stats.CRAFTED.getOrCreateStat(item));

            // Mined is per block, not per item, so it only exists for things that are
            // one. An ore that drops something else — diamond ore to diamonds — counts
            // under the block it came from, which this deliberately does not chase: the
            // margin is what covers the difference.
            if (item instanceof BlockItem) {
                total += stats.getStat(
                        Stats.MINED.getOrCreateStat(((BlockItem) item).getBlock()));
            }
            return total;
        } catch (Exception e) {
            // A missing statistic is not worth failing a deposit over. Zero reads as
            // "nothing known", and the host decides what to do with that.
            return 0;
        }
    }

    /**
     * Remembers that this world has had commands enabled, once it ever does.
     *
     * Minecraft does not. Open to LAN sets a flag on the running PlayerManager and
     * writes nothing to the save, so quitting to the title and loading the world again
     * clears it — verified in the jar: setCheatsAllowed is called from openToLan and
     * from nowhere else, and nothing seeds it at startup. Enable cheats, take what you
     * want, quit, come back, and the world truthfully describes itself as never having
     * had them.
     *
     * So the mod keeps its own note. It only grows: once seen, a world carries it, and
     * the file sits beside the market data for that world.
     *
     * Deletable by anybody who goes looking, which is the same ceiling everything else
     * here has — a modified client would not write it in the first place. What it closes
     * is the version of the trick that needs nothing but the vanilla menus and the
     * patience to reload a world.
     */
    public static void noteCheatsIfSeen(MinecraftServer server) {
        if (server == null) return;

        Path marker = cheatMarker(server);
        if (marker == null) return;

        // One filesystem check per world rather than one per tick.
        if (!marker.equals(checkedMarker)) {
            checkedMarker = marker;
            alreadyNoted = Files.exists(marker);
        }
        if (alreadyNoted || !cheatsAvailable(server)) return;

        try {
            Files.createDirectories(marker.getParent());
            Files.write(marker, "commands were enabled in this world"
                    .getBytes(StandardCharsets.UTF_8));
            alreadyNoted = true;
            System.out.println("[economiesmod] noting that this world has had commands"
                    + " enabled — hosts will be told");
        } catch (IOException e) {
            // Worth saying: silently failing to record this is the one outcome that
            // looks exactly like the world being clean.
            System.err.println("[economiesmod] could not record that commands were"
                    + " enabled: " + e);
        }
    }

    /** Whether this world has ever been seen with commands enabled. */
    public static boolean cheatsEverSeen(MinecraftServer server) {
        Path marker = cheatMarker(server);
        return marker != null && Files.exists(marker);
    }

    private static Path checkedMarker;
    private static boolean alreadyNoted;

    private static Path cheatMarker(MinecraftServer server) {
        if (server == null) return null;
        try {
            return server.getSavePath(WorldSavePath.ROOT)
                    .resolve("economiesmod").resolve("commands-were-enabled");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether commands are available right now, by either route, cheaply.
     *
     * Separate from {@link #of} so it can be asked every tick. of() hashes the world
     * seed, which is not something to do sixty times a second to answer a question that
     * is two field reads.
     */
    public static boolean cheatsAvailable(MinecraftServer server) {
        if (server == null) return false;
        try {
            if (server.getPlayerManager() != null
                    && server.getPlayerManager().areCheatsAllowed()) {
                return true;
            }
            SaveProperties props = server.getSaveProperties();
            return props != null && props.areCommandsAllowed();
        } catch (Exception e) {
            return false;
        }
    }

    /** The world's game mode right now, or "" when there is no world. Cheap. */
    public static String gameModeOf(MinecraftServer server) {
        if (server == null) return "";
        try {
            SaveProperties props = server.getSaveProperties();
            if (props == null || props.getGameMode() == null) return "";
            return props.getGameMode().getName();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Describes the world this player is in, or null when there is not one.
     *
     * Null is a real answer, not a failure: a client between worlds has nothing to
     * describe, and a host configured to require an attestation should turn that away
     * rather than receive an invented one.
     */
    public static WorldAttestation of(MinecraftServer server) {
        if (server == null) return null;

        try {
            SaveProperties props = server.getSaveProperties();
            if (props == null) return null;

            WorldAttestation a = new WorldAttestation();

            // The overworld's clock, which is the world's age in ticks. Read from the
            // world rather than the save properties because SaveProperties does not
            // expose it — checked against the remapped 1.16.5 jar rather than assumed.
            a.worldAgeTicks = server.getOverworld() != null
                    ? server.getOverworld().getTime() : 0;

            a.commandsAllowed = props.areCommandsAllowed();

            // The live answer, which the saved one does not give. Open to LAN with
            // "Allow Cheats" sets this and leaves the world's saved settings alone, so
            // reading only the save would report no cheats in a world where /give
            // works. Verified against the remapped jar: openToLan calls
            // PlayerManager.setCheatsAllowed and touches nothing else.
            a.cheatsLive = server.getPlayerManager() != null
                    && server.getPlayerManager().areCheatsAllowed();

            // Whether this world has ever had them, which Minecraft forgets and the mod
            // does not. Without it, quitting and reloading launders a world clean.
            a.cheatsEverSeen = cheatsEverSeen(server);
            a.hardcore = props.isHardcore();
            a.gameMode = props.getGameMode() != null
                    ? props.getGameMode().getName() : "unknown";

            // Hashed, never sent raw. A seed is the entire world — handing it to every
            // host anyone connects to gives away the location of everything in it. The
            // hash still tells a host that two identities share a world, which is the
            // only thing this field is for.
            a.worldIdHash = hash(props.getGeneratorOptions() != null
                    ? Long.toString(props.getGeneratorOptions().getSeed())
                    : props.getLevelName());

            return a;
        } catch (Exception e) {
            // Never worth failing a connection over. A host that requires an
            // attestation will refuse the null, which is the correct outcome anyway.
            System.err.println("[economiesmod] could not describe this world: " + e);
            return null;
        }
    }

    private static String hash(String input) {
        if (input == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            // First eight bytes are plenty to tell worlds apart, and a short string
            // stays readable in a console line an operator is scanning.
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
