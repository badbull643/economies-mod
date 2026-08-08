package io.github.badbull643.economiesmod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Path;

public class EconomiesmodClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        MarketKeybinds.register();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Path worldDir = server.getSavePath(WorldSavePath.ROOT);
            MarketStateHolder.load(worldDir);
        });

    }
}
