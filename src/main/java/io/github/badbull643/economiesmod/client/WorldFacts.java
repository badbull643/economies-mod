package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.WorldAttestation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.SaveProperties;

import java.nio.charset.StandardCharsets;
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
