package io.github.badbull643.economiesmod.client;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import java.util.UUID;

public class MinecraftIds {
    public static String itemToId(Item item) {
        return Registries.ITEM.getId(item).toString();   // "minecraft:iron_ingot"
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
        return parsed == null ? Items.AIR : Registries.ITEM.get(parsed);
    }

    /** Looks up an item by name. Returns AIR for unknown or malformed identifiers. */
    public static Item itemFromName(String name) {
        try {
            return Registries.ITEM.get(new Identifier(name));
        } catch (Exception e) {
            return Items.AIR;   // malformed identifier — treat as unknown
        }
    }

    public static UUID userIdOf(PlayerEntity player) {
        return player.getUuid();
    }
}
