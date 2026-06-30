package io.github.badbull643.economiesmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;        // 1.16.5: "options" (plural)
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class MarketKeybinds {

    public static KeyBinding openMarketKey;

    public static void register() {
        openMarketKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.economiesmod.openmarket",      // translation key
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,                    // default bound to M
                "key.category.economiesmod"         // category translation key
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMarketKey.wasPressed()) {    // 1.16.5: wasPressed(), not consumeClick()

                //opens a new market screen
                if (client.currentScreen instanceof MarketScreen) {
                    // Market is open → close it (null = return to game)
                    client.openScreen(null);
                } else if (client.currentScreen == null) {
                    // No screen open → open the market
                    client.openScreen(new MarketScreen());
                }
            }
        });
    }
}