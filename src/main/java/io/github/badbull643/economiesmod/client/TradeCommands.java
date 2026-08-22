package io.github.badbull643.economiesmod.client;

import com.google.gson.JsonElement;
import com.mojang.brigadier.context.CommandContext;
import io.github.badbull643.economiesmod.core.MarketState;
import io.github.badbull643.economiesmod.core.Order;
import io.github.badbull643.economiesmod.core.OrderBook;
import io.github.badbull643.economiesmod.core.ServerConfig;
import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v1.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.item.Item;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <h2>Why only queries, and the one exception</h2>
 *
 * Every command here reads the market. Nothing buys, sells, cancels, resets or migrates.
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
 *
 * <b>hostconfig write is the exception, and it writes a file rather than the market.</b>
 * The rule above is about the ledger — an append-only chain with no undo, where a wrong
 * command costs somebody their items. A host-config.json costs nothing: it takes effect
 * only when hosting next starts, every value in it can be edited back by hand, and it
 * cannot be written over anything (write refuses when the file is already there). It is
 * here because there was no other way to reach it. Host rules — admission, deposit caps,
 * attestation, acceptsMigration, maxWelcomeGrant — have no control anywhere in the UI,
 * and the file they live in is one nothing creates, in a directory the game never names
 * until you have already hosted once. A setting nobody can find is a setting that does
 * not exist, which is how the free-order allowance shipped switched permanently off.
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
                        .then(ClientCommandManager.literal("hostconfig")
                                // Bare hostconfig reads, and write is a word you have to
                                // type. The reading half is what somebody wanting to know
                                // the rules is after, and it is the half that answers
                                // what can be set here at all.
                                .then(ClientCommandManager.literal("write")
                                        .executes(TradeCommands::hostConfigWrite))
                                .executes(TradeCommands::hostConfig))
                        .executes(TradeCommands::usage));
    }

    private static int usage(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        info(src, "/trade balance — your credits and holdings");
        info(src, "/trade orders — your resting orders");
        info(src, "/trade price <item> — the book for one item");
        info(src, "/trade hostconfig — the rules this world hosts under");
        info(src, "Trading itself is on the market screen (M).");
        return 1;
    }

    /**
     * The rules this world hosts under, whether or not the file setting them exists.
     *
     * Printed from ServerConfig.hostRulesTree, which is the same object hostconfig write
     * saves — so every line here is a key that will be in the file, spelled the way the
     * file spells it, and nothing that reaches the file goes unmentioned. Defaults are
     * resolved rather than shown as absent, because the question this answers is what is
     * in force, and a key with no line against it reads as a setting that does not
     * exist. That is the mistake the whole command is here to stop.
     */
    private static int hostConfig(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        Path file = hostConfigFileOrComplain(src);
        if (file == null) return 0;

        boolean exists = Files.exists(file);
        ServerConfig cfg = hostRulesOrComplain(src, file);
        if (cfg == null) return 0;

        head(src, exists ? "Hosting rules, from the file" : "Hosting rules — no file yet");
        info(src, file.toString());

        for (Map.Entry<String, JsonElement> e : cfg.hostRulesTree().entrySet()) {
            info(src, "  " + e.getKey() + ": " + e.getValue());
        }

        // Said here rather than left for hosting to discover, because this is the moment
        // somebody is looking at the file. problem() is what HostServer's constructor
        // asks, so a complaint here is the same complaint hosting would make — except
        // that a world falls back to the defaults and hosts anyway, so a file saying
        // something impossible otherwise goes past as one scrolled console line.
        String bad = cfg.problem();
        if (bad != null) {
            src.sendError(new LiteralText("This file is not usable — " + bad));
            src.sendError(new LiteralText("Hosting will ignore all of it and use the"
                    + " friend-group defaults instead."));
            return 1;
        }

        if (exists) {
            info(src, "Edit the file, then start hosting again — it is read once, when"
                    + " hosting starts.");
        } else {
            info(src, "Those are the defaults. /trade hostconfig write puts exactly these"
                    + " lines in that file so you can edit them.");
        }
        return 1;
    }

    /**
     * Writes the file, once.
     *
     * Refuses when it is already there rather than offering a flag to overwrite. The
     * file is hand-edited by definition, and this writer round-trips through Gson, so an
     * overwrite would silently drop any key Gson does not know — a misspelt one, for
     * instance, which is the case somebody most needs to still be able to see. Refusing
     * costs a delete; the alternative costs the thing they were trying to debug.
     */
    private static int hostConfigWrite(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        Path file = hostConfigFileOrComplain(src);
        if (file == null) return 0;

        if (Files.exists(file)) {
            src.sendError(new LiteralText("That file already exists: " + file));
            src.sendError(new LiteralText("Edit it, or delete it and run this again."
                    + " /trade hostconfig shows what it currently says."));
            return 0;
        }

        ServerConfig cfg = hostRulesOrComplain(src, file);
        if (cfg == null) return 0;

        try {
            cfg.saveHostRules(file);
        } catch (IOException e) {
            src.sendError(new LiteralText("Could not write " + file + " — " + e));
            return 0;
        }

        head(src, "Written");
        info(src, file.toString());
        info(src, "Every setting is in it, at the value already in force, so nothing"
                + " changes until you edit one.");
        info(src, "Then start hosting again — the file is read once, when hosting starts.");
        return 1;
    }

    /**
     * Where this world's host rules live, or null with a reason printed.
     *
     * Asks MarketStateHolder for the path rather than building it, because hosting reads
     * that same path and a command that wrote somewhere else would fail silently: the
     * file would appear, and nothing would ever open it.
     */
    private static Path hostConfigFileOrComplain(FabricClientCommandSource src) {
        Path worldDir = MarketStateHolder.currentWorldDir();
        if (worldDir == null) {
            // The likely case is a player on somebody else's Minecraft server, where
            // there is no local world and the rules belong to whoever hosts the market.
            src.sendError(new LiteralText("No world open here — host rules belong to the"
                    + " world a market is hosted from, so this only works in your own"
                    + " single-player world."));
            return null;
        }
        return MarketStateHolder.hostConfigPathFor(worldDir);
    }

    /**
     * The config hosting would build from that file, or null with the reason printed.
     *
     * asWorldHost is the whole reason this is a method rather than a load call: hosting
     * stamps the session facts on before asking problem(), and a verdict reached against
     * different values is a different verdict. This command exists to report the one
     * hosting will reach, so it has to build the same object the same way.
     */
    private static ServerConfig hostRulesOrComplain(FabricClientCommandSource src, Path file) {
        MinecraftClient mc = MinecraftClient.getInstance();
        UUID me = me();
        try {
            return ServerConfig.load(file)   // defaults when absent
                    .asWorldHost(MarketStateHolder.myHostPort(),
                            mc.getSession().getUsername(),
                            me == null ? null : me.toString());
        } catch (IOException e) {
            src.sendError(new LiteralText("Could not read " + file + " — " + e.getMessage()));
            return null;
        }
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
