package io.github.badbull643.economiesmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class MarketKeybinds {

    public static KeyBinding openMarketKey;

    // 1.21.9 made a key category an object rather than a translation key. The label comes
    // from key.category.economiesmod.main in the lang file.
    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("economiesmod", "main"));

    public static void register() {
        openMarketKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.economiesmod.openmarket",      // translation key
                InputUtil.Type.KEYSYM,
                // Unbound by default, so this mod claims no key until asked. M is a
                // reasonable key for a market and a reasonable key for a dozen other
                // mods, and a fresh install silently taking it is how keys get fought
                // over. The player binds it in Options → Controls → EconomiesMod, which
                // is where somebody looking for a keybind already looks.
                //
                // There are two other ways in that need no key at all — the inventory
                // button and /trade — so an unbound default costs nobody access.
                GLFW.GLFW_KEY_UNKNOWN,
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMarketKey.wasPressed()) {    // 1.16.5: wasPressed(), not consumeClick()

                //opens a new market screen
                if (client.currentScreen instanceof MarketScreen) {
                    // Market is open → close it (null = return to game)
                    client.setScreen(null);
                } else if (client.currentScreen == null) {
                    // No screen open → open the market
                    client.setScreen(new MarketScreen());
                }
            }
        });
    }
}