package io.github.badbull643.economiesmod.client;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;
import java.util.UUID;

public class MinecraftIds {
    public static String itemToId(Item item) {
        return Registry.ITEM.getId(item).toString();   // "minecraft:iron_ingot"
    }

    public static Item idToItem(String id) {
        return Registry.ITEM.get(new Identifier(id));
    }

    /** Looks up an item by name. Returns AIR for unknown or malformed identifiers. */
    public static Item itemFromName(String name) {
        try {
            return Registry.ITEM.get(new Identifier(name));
        } catch (Exception e) {
            return Items.AIR;   // malformed identifier — treat as unknown
        }
    }

    public static UUID userIdOf(PlayerEntity player) {
        return player.getUuid();
    }
}
