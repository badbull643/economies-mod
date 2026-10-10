package io.github.badbull643.economiesmod.client;

import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public class MinecraftIds {
    public static String itemToId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();   // "minecraft:iron_ingot"
    }

    /**
     * The item an id names, or AIR for one that names nothing — including one that is not
     * an identifier at all.
     *
     * Ids reach this from the market log, which every participant writes to: core insists an
     * id is non-empty and nothing more, and a signature proves who wrote an event, not that
     * it makes sense. Identifier.of throws on anything outside [a-z0-9/._-], and this is
     * called while drawing — the inventory panel draws every resting order — so one order
     * with an id like "Not An Item!" crashed the client of everyone who opened their
     * inventory, and again on every reopen, because the order stays in the log.
     */
    public static Item idToItem(String id) {
        Identifier parsed = id == null ? null : Identifier.tryParse(id);
        return parsed == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(parsed);
    }

    /** Looks up an item by name. Returns AIR for unknown or malformed identifiers. */
    public static Item itemFromName(String name) {
        try {
            return BuiltInRegistries.ITEM.getValue(Identifier.parse(name));
        } catch (Exception e) {
            return Items.AIR;   // malformed identifier — treat as unknown
        }
    }

    /**
     * An item's display name.
     *
     * 26.x names an item through a stack, since a stack's components can rename it. The item's
     * own default stack gives the plain name — which is the one the market deals in, as it
     * skips any stack that carries components of its own.
     */
    public static Component nameOf(Item item) {
        return item.getName(item.getDefaultInstance());
    }

    public static UUID userIdOf(Player player) {
        return player.getUUID();
    }
}
