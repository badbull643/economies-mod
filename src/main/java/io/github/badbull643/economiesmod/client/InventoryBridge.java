package io.github.badbull643.economiesmod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Bridges the engine's ledger to real Minecraft inventories.
 *
 * IMPORTANT: all operations run against the SERVER-side player. Editing the
 * client-side player only changes what you see locally — the server overwrites
 * it on the next sync, which would let the same items be sold repeatedly.
 *
 * Assumes an integrated (singleplayer) server. Real multiplayer would need
 * client-to-server packets instead.
 */
public class InventoryBridge {

    /** Resolves the authoritative server-side player, or null if unavailable. */
    private static ServerPlayerEntity serverPlayer(PlayerEntity player) {
        MinecraftServer server = MinecraftClient.getInstance().getServer();
        if (server == null) return null;
        return server.getPlayerManager().getPlayer(player.getUuid());
    }

    /** Counts how many of `item` the player has, ignoring NBT-bearing stacks. */
    public static int count(PlayerEntity player, Item item) {
        ServerPlayerEntity sp = serverPlayer(player);
        if (sp == null) return 0;

        int total = 0;
        PlayerInventory inv = sp.inventory;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.getItem() == item && !stack.hasTag()) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Removes exactly `qty` of `item`. Returns false and changes nothing if insufficient. */
    public static boolean remove(PlayerEntity player, Item item, int qty) {
        ServerPlayerEntity sp = serverPlayer(player);
        if (sp == null) return false;

        if (count(player, item) < qty) return false;

        int remaining = qty;
        PlayerInventory inv = sp.inventory;
        for (int i = 0; i < inv.size() && remaining > 0; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.getItem() == item && !stack.hasTag()) {
                int take = Math.min(remaining, stack.getCount());
                stack.decrement(take);
                remaining -= take;
            }
        }

        inv.markDirty();
        sp.playerScreenHandler.sendContentUpdates();

        return remaining == 0;
    }

    /** Gives items to the player, dropping any that don't fit. */
    public static void give(PlayerEntity player, Item item, int qty) {
        ServerPlayerEntity sp = serverPlayer(player);
        if (sp == null) return;

        int maxStack = item.getMaxCount();
        while (qty > 0) {
            int stackSize = Math.min(qty, maxStack);
            sp.giveItemStack(new ItemStack(item, stackSize));
            qty -= stackSize;
        }

        sp.inventory.markDirty();
        sp.playerScreenHandler.sendContentUpdates();
    }
}