package io.github.badbull643.economiesmod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Whether a stack carries anything beyond what its item always has.
     *
     * 1.16.5 answered this with hasTag(): any NBT at all — an enchantment, a custom name, a
     * damaged tool — meant the stack was not interchangeable with a plain one, and the
     * market skips it. Since 1.20.5 that NBT is data components, and the equivalent is
     * whether any component differs from the item's defaults. A tool at full durability
     * has no changes and still counts as plain, exactly as it did before.
     */
    private static boolean hasCustomData(ItemStack stack) {
        return !stack.getComponentsPatch().isEmpty();
    }

    /** Resolves the authoritative server-side player, or null if unavailable. */
    private static ServerPlayer serverPlayer(Player player) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return null;
        return server.getPlayerList().getPlayer(player.getUUID());
    }

    /** One item the player is carrying, with everything they have of it added up. */
    public static final class Holding {
        public final Item item;
        public final int count;

        Holding(Item item, int count) {
            this.item = item;
            this.count = count;
        }
    }

    /** What a player is carrying, plus what was left out of it. */
    public static final class Holdings {
        public final List<Holding> items;
        /** Stacks skipped for carrying NBT. Reported so a UI can say so rather than
         *  silently disagree with what the player sees in their inventory. */
        public final int skipped;

        Holdings(List<Holding> items, int skipped) {
            this.items = items;
            this.skipped = skipped;
        }
    }

    /**
     * Everything the player is physically carrying, aggregated across stacks and
     * ordered by quantity.
     *
     * Reads the given player's own inventory rather than resolving the server-side
     * copy the mutating methods use. This is display only, and the client's copy is
     * exactly what the player sees when they press E — agreeing with that is the whole
     * point of showing it.
     *
     * Skips NBT-bearing stacks for the same reason {@link #count} and {@link #remove}
     * do: an enchanted pickaxe is not interchangeable with a plain one, so the market
     * has no way to price it, and listing it here would offer something unsellable.
     */
    public static Holdings held(Player player) {
        Map<Item, Integer> totals = new LinkedHashMap<>();
        int skipped = 0;

        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (hasCustomData(stack)) { skipped++; continue; }
            Item item = stack.getItem();
            Integer running = totals.get(item);
            totals.put(item, (running == null ? 0 : running) + stack.getCount());
        }

        List<Holding> out = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : totals.entrySet()) {
            out.add(new Holding(e.getKey(), e.getValue()));
        }
        // Most of what you carry is a handful of stone; what you can actually trade in
        // bulk should not be at the bottom of the list.
        out.sort((a, b) -> Integer.compare(b.count, a.count));
        return new Holdings(out, skipped);
    }

    /** Counts how many of `item` the player has, ignoring NBT-bearing stacks. */
    public static int count(Player player, Item item) {
        ServerPlayer sp = serverPlayer(player);
        if (sp == null) return 0;

        int total = 0;
        Inventory inv = sp.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() == item && !hasCustomData(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Removes exactly `qty` of `item`. Returns false and changes nothing if insufficient. */
    public static boolean remove(Player player, Item item, int qty) {
        ServerPlayer sp = serverPlayer(player);
        if (sp == null) return false;

        if (count(player, item) < qty) return false;

        int remaining = qty;
        Inventory inv = sp.getInventory();
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() == item && !hasCustomData(stack)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }

        inv.setChanged();
        sp.inventoryMenu.broadcastChanges();

        return remaining == 0;
    }

    /**
     * Gives items to the player, dropping at their feet anything that doesn't fit.
     *
     * giveItemStack is only inventory.insertStack — it returns whether everything went
     * in and drops nothing itself. Ignoring that return value silently destroyed the
     * items whenever a player withdrew into a full inventory: the ledger was already
     * debited, so the value was simply gone, and the pending-op journal cleared because
     * the hand-over had "completed".
     *
     * insertStack also mutates the stack as it fills what space there is, so a partly
     * accepted stack leaves its remainder behind — that remainder is what gets dropped,
     * not the whole stack again.
     *
     * <h2>What the return value means, and why it has to mean exactly this</h2>
     *
     * <b>False means nothing happened at all</b> — not "some of it failed", not "it went
     * badly". The only way to get false is the server being unreachable, which is checked
     * before a single stack is built, so no inventory was touched and nothing was
     * dropped. True means the whole quantity is either in the inventory or on the floor
     * beside the player.
     *
     * Every caller leans on that. A journal entry may only be cleared when this returns
     * true, and may be safely put back when it returns false — putting one back after a
     * *partial* hand-over would pay it twice, which is why "false" is not allowed to
     * cover partial anything. If a future change makes this able to fail halfway, it
     * needs a third answer rather than a wider false.
     *
     * This used to return void, and the one caller that mattered could not tell the
     * difference between a hand-over and a no-op. The failure it hid is the same one the
     * paragraph above describes, one level up: the ledger debited, the items nowhere,
     * and the journal cleared by the same lambda that failed to deliver.
     */
    public static boolean give(Player player, Item item, int qty) {
        ServerPlayer sp = serverPlayer(player);
        if (sp == null) return false;

        int maxStack = item.getDefaultMaxStackSize();
        while (qty > 0) {
            int stackSize = Math.min(qty, maxStack);
            ItemStack stack = new ItemStack(item, stackSize);
            sp.addItem(stack);
            if (!stack.isEmpty()) {
                // Same fallback vanilla's /give uses when the inventory is full.
                // SERVER_ONLY: the client predicted nothing — this is the server handing over
                // what would not fit, so the player has to be told the item exists.
                sp.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
            }
            qty -= stackSize;
        }

        sp.getInventory().setChanged();
        sp.inventoryMenu.broadcastChanges();
        return true;
    }
}