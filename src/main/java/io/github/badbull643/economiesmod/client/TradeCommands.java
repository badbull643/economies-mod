package io.github.badbull643.economiesmod.client;

import com.google.gson.JsonElement;
import com.mojang.brigadier.context.CommandContext;
import io.github.badbull643.economiesmod.core.Event;
import io.github.badbull643.economiesmod.core.MarketState;
import io.github.badbull643.economiesmod.core.Settings;
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
 * <h2>What may be a command, and what may not</h2>
 *
 * This used to read "every command here reads the market", with an exception bolted on
 * beneath it. Then a second exception arrived, and two exceptions with good arguments
 * and no rule between them is how a boundary turns into a habit — the next person has a
 * precedent to argue from rather than a test to apply. So the test is written down here
 * instead, and the exceptions are gone because they were never exceptions to the thing
 * that actually mattered.
 *
 * <b>A command may write when getting it wrong cannot cost anybody their items, their
 * credits, or their market.</b>
 *
 * That is the whole rule. It is about the harm, not the mechanism: "does this touch the
 * ledger" was never the question, because the ledger is only frightening for what it
 * cannot take back.
 *
 * What it admits, and each of these is here now:
 *
 * <ul>
 *   <li><b>Queries.</b> They cannot be wrong in a way that costs anything.</li>
 *   <li><b>A local file</b> — {@code hostconfig write}. It takes effect only when
 *       hosting next starts, every value can be edited back by hand, and it refuses to
 *       overwrite. It is here because there was no other way to reach it: host rules had
 *       no control anywhere in the UI and live in a file nothing creates, in a directory
 *       the game never names until you have hosted once.</li>
 *   <li><b>A local preference</b> — {@code archive}. It decides what this machine keeps
 *       on its own disk and can be reversed by typing the opposite.</li>
 *   <li><b>An advisory record</b> — {@code hostrules publish}. It appends to the log,
 *       and passes anyway: it moves no balance, creates and destroys nothing, is read by
 *       no rule in EventApplier, and publishing again replaces it entirely. What a wrong
 *       one costs is a figure future hosts start from until somebody corrects it, which
 *       is a message rather than a market.</li>
 * </ul>
 *
 * What it permanently excludes, and why each fails the same test:
 *
 * <ul>
 *   <li><b>Trading verbs</b> — buy, sell, cancel. A wrong one costs credits or goods
 *       immediately. They fail for a second reason too: a command and the screen would
 *       have to submit through one path, or two validations drift silently until
 *       somebody reports a bug.</li>
 *   <li><b>Lifecycle verbs</b> — reset, migrate, import, host. A wrong one costs a
 *       market. These are exactly what the guided screen exists to protect: it works out
 *       which of Reset and Migrate applies, refuses to offer the wrong one, and puts a
 *       DANGER overlay and a double-click guard in front of it. A command would put a
 *       market-destroying action one typo from execution with none of that.</li>
 * </ul>
 *
 * The two admitted writes are both here for the same reason, and it is worth stating
 * because it is the argument that will be made next: <b>a setting nobody can find is a
 * setting that does not exist</b>, which is how the free-order allowance shipped
 * switched permanently off for its entire life. That reason justifies reaching for a
 * command; it does not justify failing the test above. Something that would cost
 * somebody their market and has no control anywhere needs a control, not a command.
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
                        .then(ClientCommandManager.literal("hostrules")
                                .then(ClientCommandManager.literal("publish")
                                        .executes(TradeCommands::publishHostRules))
                                .executes(TradeCommands::hostRules))
                        .then(ClientCommandManager.literal("archive")
                                .then(ClientCommandManager.literal("on")
                                        .executes(c -> setArchive(c, true)))
                                .then(ClientCommandManager.literal("off")
                                        .executes(c -> setArchive(c, false)))
                                .executes(TradeCommands::archive))
                        .executes(TradeCommands::usage));
    }

    private static int usage(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        info(src, "/trade balance — your credits and holdings");
        info(src, "/trade orders — your resting orders");
        info(src, "/trade price <item> — the book for one item");
        info(src, "/trade hostconfig — the rules this world hosts under");
        info(src, "/trade hostrules — the rules this market's group agreed once");
        info(src, "/trade archive — whether this copy keeps the market's whole history");
        info(src, "Trading itself is on the market screen (M).");
        return 1;
    }

    /**
     * What host rules this market's group has agreed, and what this host does with them.
     *
     * Reading half. The publishing half below writes, and the rule on this class is what
     * says it may.
     */
    private static int hostRules(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        MarketState market = marketOrComplain(src);
        if (market == null) return 0;

        Event.HostDefaults published = market.hostDefaults();
        head(src, "Host rules published by '" + market.marketName() + "'");
        if (published == null) {
            info(src, "None. Every host of this market starts from its own settings, so"
                    + " a friend who has never opened the file hosts with no caps at all.");
            info(src, "/trade hostrules publish writes this host's rules into the market,"
                    + " for every future host to start from.");
            return 1;
        }

        describe(src, "deposit cap per window", published.maxDepositUnitsPerWindow);
        describe(src, "deposit window (minutes)", published.depositWindowMinutes);
        describe(src, "migrated-credit cap", published.maxMigratedCredits);
        describe(src, "welcome-grant ceiling", published.maxWelcomeGrant);
        describe(src, "accepts migration", published.acceptsMigration);
        describe(src, "admission", published.admission);
        describe(src, "allow", published.allow);
        describe(src, "deny", published.deny);

        info(src, "These are defaults, not rules. A host takes up whatever it has not set"
                + " for itself, and is free to disagree with all of it.");
        info(src, "/trade hostconfig shows what is actually in force here.");
        return 1;
    }

    private static void describe(FabricClientCommandSource src, String label, Object value) {
        info(src, "  " + label + ": " + (value == null ? "not set by the group" : value));
    }

    /**
     * Publishes this host's rules into the market, for every future host to start from.
     *
     * Writes to the log, and is allowed to under the rule on this class: getting it
     * wrong cannot cost anybody their items, their credits or their market. See there
     * for what that admits and what it will never admit — the reasoning lives in one
     * place so the next write can be tested against it rather than argued beside it.
     *
     * It is a command rather than a screen control because that screen is 4,700 lines
     * and backlog item 6 defers adding to it until item 5 has split it.
     */
    private static int publishHostRules(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        MarketState market = marketOrComplain(src);
        if (market == null) return 0;

        UUID me = me();
        if (me == null) return 0;
        if (market.creator() == null || !market.creator().equals(me)) {
            info(src, "Only the market's creator can publish its host rules.");
            return 0;
        }

        Path file = hostConfigFileOrComplain(src);
        if (file == null) return 0;
        ServerConfig cfg = hostRulesOrComplain(src, file);
        if (cfg == null) return 0;

        // From the same object /trade hostconfig prints, so what somebody read a moment
        // ago is what goes into the market. Two ways of working out "the rules here"
        // would be one description of a setting living next to the setting.
        Event.HostDefaults rules = new Event.HostDefaults();
        rules.userId = me;
        rules.maxDepositUnitsPerWindow = cfg.maxDepositUnitsPerWindow;
        rules.depositWindowMinutes = cfg.depositWindowMinutes;
        rules.maxMigratedCredits = cfg.maxMigratedCredits;
        rules.maxWelcomeGrant = cfg.maxWelcomeGrant();
        rules.acceptsMigration = cfg.acceptsMigration();
        rules.admission = cfg.admission;
        rules.allow = cfg.allow == null ? null : new java.util.ArrayList<>(cfg.allow);
        rules.deny = cfg.deny == null ? null : new java.util.ArrayList<>(cfg.deny);

        MarketStateHolder.Submission sent = MarketStateHolder.submit(rules);
        if (sent != null && !sent.pending && !sent.accepted) {
            info(src, "Could not publish: "
                    + (sent.reason == null ? "the host refused it" : sent.reason));
            return 0;
        }
        head(src, "Published this host's rules to '" + market.marketName() + "'");
        info(src, "Every future host of this market starts from these, for anything they"
                + " have not set for themselves. Nothing is enforced by them.");
        info(src, "/trade hostrules to see what the market now carries.");
        return 1;
    }

    /**
     * Whether this machine keeps the full history of the market it is holding.
     *
     * On a market a dedicated server serves, the default is no: a snapshot is enough to
     * play from, the events were each checked as they arrived, and a hundred players do
     * not need a hundred copies of a half-gigabyte log to keep one market alive. A few
     * do, though, and this is how somebody becomes one of them.
     *
     * Sets a local preference, which the rule on this class admits: getting it wrong
     * costs a slower load and nothing else, and typing the opposite undoes it.
     *
     * Here rather than only in `economiesmod-settings-<name>.json` for §0.18's reason,
     * which cost this project a whole entry: a rule whose default does something, with no
     * way to reach it short of knowing a file exists, is a rule nobody can act on. The
     * console line printed when a market stops being archived names this command.
     */
    private static int archive(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        MarketState market = marketOrComplain(src);
        if (market == null) return 0;

        Settings settings = MarketStateHolder.settings();
        boolean on = settings != null && settings.archives(market.marketId());

        // The setting and the history are two facts, and reporting only the first is
        // what made this command useless to somebody testing it: they turned archiving
        // on, were told the history would arrive, watched it arrive, asked again, and
        // were told the same thing. Whether it is here is the question being asked.
        boolean here = MarketStateHolder.hasFullHistory();

        head(src, "Archiving '" + market.marketName() + "'");
        info(src, on
                ? "On — this copy keeps every event, and could serve this market if"
                  + " whoever hosts it stops."
                : "Off — this copy keeps a snapshot of the balances and books, not the"
                  + " history behind them.");
        info(src, here
                ? "The history is here: " + MarketStateHolder.localHeadSeq() + " events on"
                  + " disk, and this copy could host."
                : "The history is not here — only a snapshot of where the market got to.");
        if (on && !here) {
            info(src, "Connect once and the host will send it from the beginning.");
        }
        if (!on) {
            info(src, "That is the default for a market a dedicated server serves, and"
                    + " has no effect on a market your friends take turns hosting.");
            info(src, "You cannot host a market you have not archived — there would be"
                    + " nothing to send anyone who joined.");
        }
        info(src, "/trade archive " + (on ? "off" : "on") + " to change it."
                + " It takes effect on the next connect.");
        return 1;
    }

    /**
     * Turning it on does not fetch the history — the next connect does.
     *
     * Saying so matters more than it looks. Somebody switches this on precisely because
     * they want to be able to host, and between the switch and the reconnect they still
     * cannot; a message that implied otherwise would send them to a Host button that is
     * still greyed with no explanation of why.
     */
    private static int setArchive(CommandContext<FabricClientCommandSource> ctx, boolean on) {
        FabricClientCommandSource src = ctx.getSource();
        MarketState market = marketOrComplain(src);
        if (market == null) return 0;

        Settings settings = MarketStateHolder.settings();
        if (settings == null) {
            info(src, "No settings are loaded, so there is nowhere to remember this.");
            return 0;
        }
        settings.setArchives(market.marketId(), on);
        head(src, "Archiving '" + market.marketName() + "' is now " + (on ? "on" : "off"));
        if (on) {
            // Asked rather than assumed. This line used to say the history was on its
            // way whatever the truth was, so it went on saying it after the history had
            // arrived — which reads as nothing having happened, and is how somebody
            // testing this concluded the feature did not work when it had.
            if (MarketStateHolder.hasFullHistory()) {
                info(src, "The history is already here — " + MarketStateHolder.localHeadSeq()
                        + " events on disk. Nothing to fetch.");
            } else {
                info(src, "The history is not here yet. Connect once and the host will send"
                        + " it from the beginning; until then this is still a snapshot.");
            }
        } else {
            info(src, "Events already written stay on disk. Nothing new is added, and"
                    + " this copy stops being one of the ones keeping the market alive.");
        }
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

        // welcomeGrant is the one key here whose name lies about what it does. On a
        // world it is a switch: issueWelcomeGrant tests it against zero and then takes
        // the amount from the market, because the amount was recorded at genesis and is
        // not this host's to overrule. The figure beside it — 1000, the compiled default
        // — is inert, and reading it as "newcomers get 1000" is the natural mistake.
        //
        // Said as a line under the list rather than beside the key, because a per-key
        // annotation is a second description of a setting living next to the setting,
        // and those drift.
        info(src, "welcomeGrant here only chooses whether grants go out at all — 0 stops"
                + " them. The amount is the market's, fixed when it was created.");

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
