package io.github.badbull643.economiesmod.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public class MarketKeybinds {

    public static KeyMapping openMarketKey;

    // 1.21.9 made a key category an object rather than a translation key. The label comes
    // from key.category.economiesmod.main in the lang file.
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("economiesmod", "main"));

    public static void register() {
        openMarketKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.economiesmod.openmarket",      // translation key
                InputConstants.Type.KEYBOARD,
                // Unbound by default, so this mod claims no key until asked. M is a
                // reasonable key for a market and a reasonable key for a dozen other
                // mods, and a fresh install silently taking it is how keys get fought
                // over. The player binds it in Options → Controls → EconomiesMod, which
                // is where somebody looking for a keybind already looks.
                //
                // There are two other ways in that need no key at all — the inventory
                // button and /trade — so an unbound default costs nobody access.
                InputConstants.UNKNOWN.getValue(),
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMarketKey.consumeClick()) {    // 1.16.5: wasPressed(), not consumeClick()

                //opens a new market screen
                if (client.gui.screen() instanceof MarketScreen) {
                    // Market is open → close it (null = return to game)
                    client.gui.setScreen(null);
                } else if (client.gui.screen() == null) {
                    // No screen open → open the market
                    client.gui.setScreen(new MarketScreen());
                }
            }
        });
    }
}