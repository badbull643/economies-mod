package io.github.badbull643.economiesmod.client;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
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

    public static Item itemFromName(String name) {
        return Registry.ITEM.get(new Identifier(name));
    }

    public static UUID userIdOf(PlayerEntity player) {
        return player.getUuid();
    }
}
