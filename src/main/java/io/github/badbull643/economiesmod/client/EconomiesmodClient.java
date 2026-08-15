package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.Event;
import io.github.badbull643.economiesmod.core.PendingOps;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Path;

public class EconomiesmodClient implements ClientModInitializer {

    /**
     * Whether the half-finished inventory operations from last session have been
     * settled yet.
     *
     * Not done at SERVER_STARTED, which is where the market itself loads: that fires
     * before the player exists, and handing someone their items back requires a player
     * to hand them to. So it waits for the first tick where one is actually there.
     */
    private static boolean pendingOpsSettled = false;

    @Override
    public void onInitializeClient() {
        MarketKeybinds.register();


        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            String name = mc.getSession().getUsername();
            Path configDir = FabricLoader.getInstance().getConfigDir();

            MarketStateHolder.loadKeys(configDir.resolve("economiesmod-identity-" + name + ".key"));
            MarketStateHolder.loadPeers(configDir.resolve("economiesmod-peers-" + name + ".json"));

            MarketStateHolder.ensureShareFolders();

            Path worldDir = server.getSavePath(WorldSavePath.ROOT);
            MarketStateHolder.loadLocal(worldDir);

            pendingOpsSettled = false;
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            System.out.println("[economiesmod] world stopping — shutting down");
            MarketStateHolder.shutdown();
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (pendingOpsSettled) return;
            if (mc.player == null || mc.getServer() == null) return;
            pendingOpsSettled = true;
            settlePendingOps(mc);
        });

        MarketStateHolder.setOnApplied(se -> {
            if (!(se.event instanceof Event.Withdraw)) return;

            Event.Withdraw w = (Event.Withdraw) se.event;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null) return;

            // Only grant for our own withdrawals — everyone else's are just ledger changes.
            if (!w.userId.equals(MinecraftIds.userIdOf(mc.player))) return;

            Item item = MinecraftIds.idToItem(w.itemId);
            if (item == Items.AIR) return;

            // The debit is already in the log and durable; the hand-over is neither, and
            // happens a tick later on the game thread. Note it before, clear it after, so
            // a crash in between leaves something that says what never arrived.
            PendingOps journal = MarketStateHolder.pendingOps();
            if (journal != null) {
                journal.recordWithdraw(w.userId, se.seq, w.itemId, w.quantity);
            }

            // This may fire on the network reader thread — inventory work must be on
            // the game thread.
            mc.execute(() -> {
                InventoryBridge.give(mc.player, item, (int) w.quantity);
                if (journal != null) journal.clearWithdraw(se.seq);
            });
        });

    }

    /**
     * Puts back anything a deposit took but never got credit for, and says plainly
     * what couldn't be settled.
     */
    private static void settlePendingOps(MinecraftClient mc) {
        MarketStateHolder.Recovery recovery = MarketStateHolder.resolvePendingOps();
        if (recovery.isEmpty()) return;

        for (PendingOps.Op op : recovery.refunds) {
            Item item = MinecraftIds.idToItem(op.itemId);
            if (item == Items.AIR) {
                System.err.println("[economiesmod] cannot return " + op.describe()
                        + " — unknown item");
                continue;
            }
            InventoryBridge.give(mc.player, item, (int) op.quantity);
            System.out.println("[economiesmod] returned " + op.describe()
                    + " from a deposit that never completed");
        }

        for (PendingOps.Op op : recovery.unconfirmed) {
            // Deliberately not re-given. Nothing records whether the hand-over
            // completed, so doing it again would mint items whenever the interruption
            // landed after the give rather than before it.
            System.err.println("[economiesmod] withdrawal of " + op.describe()
                    + " (event " + op.seq + ") may not have reached your inventory");
        }

        int total = recovery.refunds.size() + recovery.unconfirmed.size();
        MarketScreen.reportRecovery(recovery.refunds.size(), recovery.unconfirmed.size());
        System.out.println("[economiesmod] settled " + total
                + " interrupted inventory operation(s) from a previous session");
    }
}
