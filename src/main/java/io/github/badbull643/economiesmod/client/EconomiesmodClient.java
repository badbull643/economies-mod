package io.github.badbull643.economiesmod.client;

import net.fabricmc.api.ClientModInitializer;

public class EconomiesmodClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        MarketKeybinds.register();
    }
}
