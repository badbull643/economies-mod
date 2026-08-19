package io.github.badbull643.economiesmod.client;

import com.mojang.brigadier.context.CommandContext;
import io.github.badbull643.economiesmod.core.MarketState;
import io.github.badbull643.economiesmod.core.Order;
import io.github.badbull643.economiesmod.core.OrderBook;
import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v1.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.item.Item;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Reading the market from chat.
 *
 * <h2>Why these are client commands</h2>
 *
 * Not a convenience: the market connection is this mod's own socket to a HostServer and
 * has nothing to do with whatever Minecraft server the player happens to be on. A
 * server-side command would be asking the wrong machine. ClientCommandManager runs
 * these entirely locally, against the replica this client already maintains, and needs
 * no mixin — verified present in fabric-command-api-v1 1.1.3 before being written
 * against.
 *
 * <h2>Why only queries</h2>
 *
 * Every command here reads. Nothing buys, sells, cancels, resets or migrates.
 *
 * The reading ones are free: they duplicate no mutation logic, need no confirmation
 * design, and cannot cost anybody anything if they are wrong. Trading verbs are not
 * free, and the constraint that decides whether they stay cheap is that a command and
 * the screen must submit through one path — two paths mean two validations, and they
 * drift silently until someone reports a bug. That work belongs with the verbs, not
 * before them.
 *
 * Lifecycle verbs — reset, migrate, import, host — are deliberately never coming. Those
 * are exactly what the guided Market screen exists to protect: it works out which of
 * Reset and Migrate applies and refuses to offer the wrong one, behind a DANGER overlay
 * with a double-click guard. A command would put a market-destroying action one typo
 * from execution with none of that.
 */
public final class TradeCommands {

    private TradeCommands() {}

    public static void register() {
        ClientCommandManager.DISPATCHER.register(
                ClientCommandManager.literal("trade")
                        .then(ClientCommandManager.literal("balance")
                                .executes(TradeCommands::balance))
                        .then(ClientCommandManager.literal("orders")
                                .executes(TradeCommands::orders))
                        .then(ClientCommandManager.literal("price")
                                // Brigadier's own item argument, so tab-completion
                                // offers every item id — which is what the GUI's picker
                                // deliberately retired typing for, returned here where
                                // typing is the whole interface.
                                .then(ClientCommandManager.argument("item",
                                                ItemStackArgumentType.itemStack())
                                        .executes(TradeCommands::price)))
                        .executes(TradeCommands::usage));
    }

    private static int usage(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        info(src, "/trade balance — your credits and holdings");
        info(src, "/trade orders — your resting orders");
        info(src, "/trade price <item> — the book for one item");
        info(src, "Trading itself is on the market screen (M).");
        return 1;
    }

    /**
     * The market as this client currently sees it, or null with a reason printed.
     *
     * Every command starts here, because "no market" is by far the most likely thing to
     * be wrong and saying so once beats each command discovering it differently.
     */
    private static MarketState marketOrComplain(FabricClientCommandSource src) {
        MarketState market = MarketStateHolder.get();
        if (market == null || market.marketId() == null) {
            src.sendError(new LiteralText("No market here yet — open the market screen"
                    + " (M) to create one or connect to a host."));
            return null;
        }
        return market;
    }

    private static UUID me() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player == null ? null : MinecraftIds.userIdOf(mc.player);
    }

    private static int balance(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        MarketState market = marketOrComplain(src);
        if (market == null) return 0;

        UUID me = me();
        if (me == null) return 0;

        head(src, "Your position in '" + market.marketName() + "'");
        info(src, "Credits: " + market.wallets().getBalance(me));

        // Sorted, so a list that changes between two runs changes because holdings did,
        // not because a hash map felt different about the order.
        Map<String, Long> held = new TreeMap<>(market.itemBalances().heldBy(me));
        if (held.isEmpty()) {
            info(src, "Holding nothing in the market.");
        } else {
            for (Map.Entry<String, Long> e : held.entrySet()) {
                if (e.getValue() != null && e.getValue() > 0) {
                    info(src, "  " + e.getValue() + " " + itemName(e.getKey()));
                }
            }
        }
        return 1;
    }

    private static int orders(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        MarketState market = marketOrComplain(src);
        if (market == null) return 0;

        UUID me = me();
        if (me == null) return 0;

        head(src, "Your resting orders");
        int found = 0;
        // Sorted, so two runs differ only when the market did.
        for (String itemId : new java.util.TreeSet<>(market.activeItems())) {
            // peekBook, not bookFor: this walks every traded item, and bookFor creates a
            // book for each one it is asked about.
            OrderBook book = market.peekBook(itemId);
            if (book == null) continue;

            for (Order o : book.restingBids()) {
                if (o.userID().equals(me)) {
                    info(src, "  #" + o.orderId() + " BUY " + o.volume() + " "
                            + itemName(itemId) + " @ " + o.value());
                    found++;
                }
            }
            for (Order o : book.restingAsks()) {
                if (o.userID().equals(me)) {
                    info(src, "  #" + o.orderId() + " SELL " + o.volume() + " "
                            + itemName(itemId) + " @ " + o.value());
                    found++;
                }
            }
        }
        if (found == 0) info(src, "Nothing resting. Place orders on the market screen (M).");
        return 1;
    }

    private static int price(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        MarketState market = marketOrComplain(src);
        if (market == null) return 0;

        Item item = ItemStackArgumentType.getItemStackArgument(ctx, "item").getItem();
        String itemId = MinecraftIds.itemToId(item);

        head(src, itemName(itemId));

        OrderBook book = market.peekBook(itemId);
        List<Order> bids = book == null ? null : book.restingBids();
        List<Order> asks = book == null ? null : book.restingAsks();

        if (book == null || (bids.isEmpty() && asks.isEmpty())) {
            info(src, "Nobody is buying or selling this.");
        } else {
            info(src, "Best bid: " + (bids.isEmpty() ? "none"
                    : bids.get(0).value() + " for " + bids.get(0).volume()));
            info(src, "Best ask: " + (asks.isEmpty() ? "none"
                    : asks.get(0).value() + " for " + asks.get(0).volume()));
        }

        long last = market.trades().lastPrice(itemId);
        // The honest number, as opposed to what someone is currently hoping for.
        info(src, last >= 0 ? "Last traded at " + last : "Never traded.");

        int fee = market.taxBps();
        if (fee > 0) {
            info(src, "Selling here costs " + (fee / 100.0) + "% of the sale.");
        }
        return 1;
    }

    /** The name a player would recognise, falling back to the raw id. */
    private static String itemName(String itemId) {
        Item item = MinecraftIds.idToItem(itemId);
        return item == null ? itemId : item.getName().getString();
    }

    private static void head(FabricClientCommandSource src, String text) {
        src.sendFeedback(new LiteralText(text).formatted(Formatting.GOLD));
    }

    private static void info(FabricClientCommandSource src, String text) {
        src.sendFeedback(new LiteralText(text).formatted(Formatting.GRAY));
    }
}
