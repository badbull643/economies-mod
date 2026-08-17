package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.Event;
import io.github.badbull643.economiesmod.core.Fill;
import io.github.badbull643.economiesmod.core.PendingOps;
import io.github.badbull643.economiesmod.core.SequencedEvent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Path;
import java.util.UUID;

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

    /** Owns the rate-limiting window, so it has to outlive any one event. */
    private static final FillNotifier FILLS = new FillNotifier();

    @Override
    public void onInitializeClient() {
        MarketKeybinds.register();
        // A second way in, for players who have no reason to know about the keybind.
        InventoryMarketButton.register();
        // A third, for reading the market without leaving what you were doing.
        TradeCommands.register();


        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            String name = mc.getSession().getUsername();
            Path configDir = FabricLoader.getInstance().getConfigDir();

            MarketStateHolder.loadKeys(configDir.resolve("economiesmod-identity-" + name + ".key"));
            MarketStateHolder.loadPeers(configDir.resolve("economiesmod-peers-" + name + ".json"));
            // Per-username like the two above: the clientAlice/clientBob dev launches
            // share a config directory and would otherwise overwrite each other.
            MarketStateHolder.loadSettings(
                    configDir.resolve("economiesmod-settings-" + name + ".json"));

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
            // Batched fills wait for their window to close, so something has to come
            // back and flush them.
            FILLS.tick();

            // Here rather than in the market screen's render, which is where it started
            // and where it could never have worked: Open to LAN is reached from the
            // pause menu, so the screen that was doing the checking is closed at exactly
            // the moment the world changes. A tick happens whether anyone is looking.
            MarketStateHolder.reattestIfChanged();

            if (pendingOpsSettled) return;
            if (mc.player == null || mc.getServer() == null) return;
            pendingOpsSettled = true;
            settlePendingOps(mc);
        });

        MarketStateHolder.setOnApplied(applied -> {
            // Replayed history must not hand over anything. Joining a market syncs its
            // whole log, which includes every withdrawal you ever made in it — acting
            // on those would give you the items a second time, and again on every
            // rejoin. Reset-then-connect made that a repeatable duplication.
            if (!applied.live) return;

            SequencedEvent se = applied.event;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null) return;

            // Fills reach a client for events it did not author — that is how you learn
            // a resting order of yours traded while you were doing something else, and
            // it is the only place that information exists on this side.
            if (applied.result != null && !applied.result.fills.isEmpty()) {
                UUID me = MinecraftIds.userIdOf(mc.player);
                // Whoever authored the event is the aggressor; anyone else in a fill was
                // sitting on the book.
                boolean iAggressed = me.equals(se.event.userId);
                mc.execute(() -> {
                    for (Fill fill : applied.result.fills) {
                        FILLS.onFill(fill, me, !iAggressed);
                    }
                });
            }

            if (!(se.event instanceof Event.Withdraw)) return;

            Event.Withdraw w = (Event.Withdraw) se.event;

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
        MarketStateHolder.reportRecovery(recovery.refunds.size(), recovery.unconfirmed.size());
        System.out.println("[economiesmod] settled " + total
                + " interrupted inventory operation(s) from a previous session");
    }
}
