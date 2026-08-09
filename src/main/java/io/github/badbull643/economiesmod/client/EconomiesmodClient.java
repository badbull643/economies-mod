package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.Event;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Path;

public class EconomiesmodClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        MarketKeybinds.register();


        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            String name = mc.getSession().getUsername();
            Path keyFile = FabricLoader.getInstance().getConfigDir()
                    .resolve("economiesmod-identity-" + name + ".key");
            MarketStateHolder.loadKeys(keyFile);

            Path worldDir = server.getSavePath(WorldSavePath.ROOT);
            MarketStateHolder.loadLocal(worldDir);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            MarketStateHolder.shutdown();
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

            // This may fire on the network reader thread — inventory work must be on
            // the game thread.
            mc.execute(() -> InventoryBridge.give(mc.player, item, (int) w.quantity));
        });

    }
}
