package io.github.badbull643.economiesmod.test;

import io.github.badbull643.economiesmod.client.MarketScreen;
import io.github.badbull643.economiesmod.client.MarketStateHolder;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Walks the screens in a real client and photographs each one.
 *
 * Not assertions about what is drawn — a screenshot cannot say whether a layout is right —
 * but it does fail on the thing a compile cannot catch: a screen that throws while it
 * renders, which is what changed most between 1.16.5 and 1.21.
 */
@SuppressWarnings("UnstableApiUsage")
public class MarketScreensGameTest implements FabricClientGameTest {

    // In MarketScreen's SCREEN_ order. "item" is one item's view, reached from Browse.
    private static final String[] TABS = {"browse", "sell", "orders", "history", "leaderboard",
            "network", "market", "settings", "item"};
    private static final int BROWSE = 0, ORDERS = 2, NETWORK = 5, MARKET = 6, ITEM = 8;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            context.takeScreenshot("00-world");

            // The inventory, where the emerald button and the listings panel live.
            context.getInput().pressKey(GLFW.GLFW_KEY_E);
            context.waitTicks(10);
            context.takeScreenshot("01-inventory");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitTicks(5);

            // Every tab of the market screen, before any market exists.
            for (int i = 0; i < TABS.length; i++) {
                openTab(context, i);
                context.takeScreenshot("02-no-market-" + TABS[i]);
            }

            // The create-a-market confirmation: a modal over a screen full of text.
            openTab(context, MARKET);
            context.runOnClient(client -> setText(client.currentScreen, "marketNameField", "Test Market"));
            context.clickScreenButton("Create a new market");
            context.waitTicks(3);
            context.takeScreenshot("03-create-overlay");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);   // answers the overlay
            context.waitTicks(2);

            // Make the market for real, and put something in the inventory to trade.
            context.runOnClient(client -> {
                Path dir = MarketStateHolder.worldDirOrNull();
                if (dir == null) throw new IllegalStateException("no world dir");
                UUID me = client.player.getUuid();
                if (!MarketStateHolder.createMarket(dir, me, "Test Market")) {
                    throw new IllegalStateException("market was not created");
                }
            });
            world.getServer().runCommand("give @p minecraft:iron_ingot 64");
            world.getServer().runCommand("give @p minecraft:diamond 5");
            world.getServer().runCommand("give @p minecraft:cobblestone 200");
            context.waitTicks(10);

            // A second player's orders, put straight into the live market so the order
            // book and the listings panel have something to draw. Not signed or logged —
            // this is for looking at, not for testing the engine.
            context.runOnClient(client -> {
                io.github.badbull643.economiesmod.core.MarketState state = MarketStateHolder.get();
                UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
                state.deposit(other, "minecraft:diamond", 50);
                state.deposit(other, "minecraft:iron_ingot", 200);
                state.wallets().adjust(other, 5000);
                state.submitOrder(new io.github.badbull643.economiesmod.core.Order(
                        900, 40, "minecraft:diamond", 12, false, other));
                state.submitOrder(new io.github.badbull643.economiesmod.core.Order(
                        901, 55, "minecraft:diamond", 3, false, other));
                state.submitOrder(new io.github.badbull643.economiesmod.core.Order(
                        902, 6, "minecraft:iron_ingot", 64, false, other));
                state.submitOrder(new io.github.badbull643.economiesmod.core.Order(
                        903, 3, "minecraft:iron_ingot", 30, true, other));
            });

            // Some trading of our own, so Your orders and History have rows to draw: a buy
            // and a sale that fill, through the applier so they are recorded as trades, and
            // two orders that rest.
            context.runOnClient(client -> {
                io.github.badbull643.economiesmod.core.MarketState state = MarketStateHolder.get();
                UUID me = client.player.getUuid();
                state.wallets().adjust(me, 2000);
                state.deposit(me, "minecraft:iron_ingot", 40);
                apply(state, me, 9001, "minecraft:diamond", 40, 2, true);      // buys from the ask at 40
                apply(state, me, 9002, "minecraft:iron_ingot", 3, 10, false);  // sells into the bid at 3
                // A run of iron trades at drifting prices, so the chart has a line to draw.
                UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
                long[] prices = {4, 5, 5, 6, 5, 7, 6, 8, 7, 6, 7, 8};
                for (int i = 0; i < prices.length; i++) {
                    state.submitOrder(new io.github.badbull643.economiesmod.core.Order(
                            920 + i, prices[i], "minecraft:iron_ingot", 2, false, other));
                    apply(state, me, 9100 + i, "minecraft:iron_ingot", prices[i], 2, true);
                }
                state.submitOrder(new io.github.badbull643.economiesmod.core.Order(
                        910, 30, "minecraft:diamond", 4, true, me));
                state.submitOrder(new io.github.badbull643.economiesmod.core.Order(
                        911, 9, "minecraft:iron_ingot", 16, false, me));
                // More open orders than the panel shows, so the list has to scroll.
                for (int i = 0; i < 12; i++) {
                    state.submitOrder(new io.github.badbull643.economiesmod.core.Order(
                            940 + i, 1 + i, "minecraft:cobblestone", 2, true, me));
                }
            });
            selectItem("minecraft:diamond");

            // Every tab again, now that there is a market and a creator.
            for (int i = 0; i < TABS.length; i++) {
                openTab(context, i);
                context.takeScreenshot("04-market-" + TABS[i]);
            }

            // An item's advanced side: the chart, the figures and the book in full.
            setStatic("itemAdvanced", true);
            openTab(context, ITEM);
            context.takeScreenshot("04-market-item-advanced");
            setStatic("itemAdvanced", false);

            // The view menu, open over Browse.
            openTab(context, BROWSE);
            context.runOnClient(client -> setField(client.currentScreen, "menuOpen", true));
            context.waitTicks(2);
            context.takeScreenshot("04-market-menu");

            // A search that matches nothing here, which offers every item in the game instead.
            openTab(context, BROWSE);
            context.runOnClient(client -> setText(client.currentScreen, "searchField", "gold"));
            context.waitTicks(2);
            context.takeScreenshot("04-market-search");

            // The item picker, which clips a scrolling grid and layers over the screen.
            openTab(context, BROWSE);
            context.runOnClient(client -> call(client.currentScreen, "openPicker"));
            context.waitTicks(3);
            context.takeScreenshot("05-picker");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitTicks(2);

            // And the inventory again, which now holds a market and so shows the panel.
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            // GUI scale 1 so the panel has room beside the inventory, and long enough for
            // the toasts the give commands raised to leave the corner it would sit in.
            context.runOnClient(client -> {
                client.options.getGuiScale().setValue(1);
                client.onResolutionChanged();
            });
            context.waitTicks(160);
            context.getInput().pressKey(GLFW.GLFW_KEY_E);
            context.waitTicks(10);
            context.takeScreenshot("06-inventory-with-market");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitTicks(3);

            // An order whose item id is not an identifier at all. Core only insists an id is
            // non-empty and a signature proves who wrote an event, not that it is sensible, so
            // any participant can put one in the log — and every other client then draws it,
            // from the inventory panel as well as the market screen. Drawing it must not throw.
            context.runOnClient(client -> {
                io.github.badbull643.economiesmod.core.MarketState state = MarketStateHolder.get();
                UUID hostile = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
                state.deposit(hostile, "Not An Item!", 5);
                state.wallets().adjust(hostile, 5000);
                state.submitOrder(new io.github.badbull643.economiesmod.core.Order(
                        904, 10, "Not An Item!", 5, false, hostile));
                // Below the ask, so the two rest side by side instead of filling each other.
                state.submitOrder(new io.github.badbull643.economiesmod.core.Order(
                        905, 4, "Not An Item!", 5, true, hostile));
            });
            context.getInput().pressKey(GLFW.GLFW_KEY_E);
            context.waitTicks(10);
            context.takeScreenshot("08-hostile-order-inventory");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitTicks(3);
            for (int i = 0; i < TABS.length; i++) {
                openTab(context, i);
                context.takeScreenshot("09-hostile-order-" + TABS[i]);
            }
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitTicks(3);

            // Valid orders at the largest sizes a long allows. They pass every check core
            // makes, so anyone in the market can place them, and the screens do arithmetic
            // on prices and volumes — scaling a chart, measuring a label — that nobody
            // ever tried with a number this big.
            context.runOnClient(client -> {
                io.github.badbull643.economiesmod.core.MarketState state = MarketStateHolder.get();
                UUID whale = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
                state.deposit(whale, "minecraft:diamond", Long.MAX_VALUE - 10);
                state.deposit(whale, "minecraft:iron_ingot", 5);
                state.wallets().adjust(whale, Long.MAX_VALUE - 100);
                state.submitOrder(new io.github.badbull643.economiesmod.core.Order(
                        906, 1, "minecraft:diamond", Long.MAX_VALUE - 10, false, whale));
                state.submitOrder(new io.github.badbull643.economiesmod.core.Order(
                        907, Long.MAX_VALUE - 1, "minecraft:iron_ingot", 1, false, whale));
                state.submitOrder(new io.github.badbull643.economiesmod.core.Order(
                        908, Long.MAX_VALUE - 200, "minecraft:cobblestone", 1, true, whale));
            });
            context.getInput().pressKey(GLFW.GLFW_KEY_E);
            context.waitTicks(10);
            context.takeScreenshot("10-extreme-orders-inventory");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitTicks(3);
            for (int i = 0; i < TABS.length; i++) {
                openTab(context, i);
                context.takeScreenshot("11-extreme-orders-" + TABS[i]);
            }
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitTicks(3);

            // The warnings a rotating market raises, on the new screen: each banner, all of
            // them stacked with a recovery note under the panel, a damaged log, and the offer
            // that joining a different market brings up. Set by hand rather than by really
            // forking a market — this checks how they look and read; RotationTest covers how
            // they arise. Back at the usual GUI scale, which the inventory shot above changed.
            context.runOnClient(client -> {
                client.options.getGuiScale().setValue(0);
                client.onResolutionChanged();
            });
            context.waitTicks(5);
            long head = context.computeOnClient(client -> localHead());

            // Behind: somebody on this market was last seen 37 events further on.
            context.runOnClient(client -> MarketStateHolder.observeMarketHeight(
                    MarketStateHolder.get().marketId(), head + 37, "a-peer-further-on"));
            openTab(context, BROWSE);
            context.takeScreenshot("12-warning-behind");

            // Forked: a host on another branch, with where the two parted.
            context.runOnClient(client -> setHolder("divergence",
                    holderNew("Divergence", "Alice's world", 412L, "aaaa", "bbbb", 400L)));
            openTab(context, BROWSE);
            context.takeScreenshot("12-warning-forked");

            // Orders from a market left behind, waiting to be placed again — the banner, then
            // the list itself in Your orders. And a recovery note under the panel.
            context.runOnClient(client -> {
                @SuppressWarnings("unchecked")
                java.util.List<Object> waiting = (java.util.List<Object>) holder("pendingReplace");
                waiting.add(holderNew("OldOrder", "minecraft:iron_ingot", 6L, 16L, false));
                waiting.add(holderNew("OldOrder", "minecraft:diamond", 30L, 4L, true));
                waiting.add(holderNew("OldOrder", "minecraft:oak_log", 9L, 64L, false));
                setHolder("recoveryNote", "Recovered 12 iron ingots from a deposit that did not finish");
            });
            openTab(context, BROWSE);
            context.takeScreenshot("12-warning-all-stacked");
            openTab(context, ORDERS);
            context.takeScreenshot("12-warning-replace-orders");
            openTab(context, MARKET);
            context.takeScreenshot("12-warning-market");

            // A damaged log, which replaces every other warning. Set once the screen is open:
            // opening it reads the log again, which would clear a flag set beforehand.
            openTab(context, BROWSE);
            context.runOnClient(client -> {
                setHolder("chainBrokenAt", 388L);
                setHolder("damageReason", "chain is broken at event 388");
            });
            context.waitTicks(3);
            context.takeScreenshot("12-warning-log-unusable");
            context.runOnClient(client -> selectTabNow(client.currentScreen, MARKET));
            context.waitTicks(3);
            context.takeScreenshot("12-warning-log-unusable-market");

            context.runOnClient(client -> {
                setHolder("chainBrokenAt", -1L);
                setHolder("damageReason", null);
                setHolder("divergence", null);
                ((java.util.List<?>) holder("pendingReplace")).clear();
                setHolder("recoveryNote", "");
            });

            // Connecting to a server that holds a different market: the offer of a new slot.
            openTab(context, NETWORK);
            context.runOnClient(client -> setObject(client.currentScreen, "offerNewSlot",
                    new String[]{"192.168.1.20", "25555", "192.168.1.20:25555"}));
            context.waitTicks(3);
            context.takeScreenshot("12-different-market-offer");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitTicks(3);
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitTicks(3);

            // The chat commands, which now register through a different Fabric API.
            for (String command : new String[] {"trade", "trade balance", "trade price iron_ingot",
                    "trade hostrules", "trade hostconfig"}) {
                context.runOnClient(client -> client.getNetworkHandler().sendChatCommand(command));
                context.waitTicks(3);
            }
            context.runOnClient(client -> client.inGameHud.getChatHud().scroll(0));
            context.takeScreenshot("07-commands");

            // What a reconnect says about the fills that happened while you were away. Fed a
            // summary by hand: how the count arises is AW1 in the core suite.
            context.runOnClient(client -> {
                UUID me = client.player.getUuid();
                UUID other = UUID.randomUUID();
                io.github.badbull643.economiesmod.core.SequencedEvent se =
                        new io.github.badbull643.economiesmod.core.SequencedEvent();
                se.seq = 1;
                io.github.badbull643.economiesmod.core.AwaySummary away =
                        new io.github.badbull643.economiesmod.core.AwaySummary(me, 0);
                away.accept(new io.github.badbull643.economiesmod.core.AppliedEvent(se,
                        io.github.badbull643.economiesmod.core.EventApplier.Result.ok(java.util.Arrays.asList(
                                new io.github.badbull643.economiesmod.core.Fill(other, me, 20, 3, "minecraft:cobblestone"),
                                new io.github.badbull643.economiesmod.core.Fill(other, me, 12, 6, "minecraft:iron_ingot"),
                                new io.github.badbull643.economiesmod.core.Fill(me, other, 2, 40, "minecraft:diamond"),
                                new io.github.badbull643.economiesmod.core.Fill(other, me, 64, 1, "minecraft:oak_log"))),
                        false));
                new io.github.badbull643.economiesmod.client.FillNotifier().onAway(away);
            });
            context.waitTicks(3);
            context.takeScreenshot("13-away-summary");

            // A market shared across versions: renamed items go by their first name, and
            // Browse shows only what this version has. The server side is CrossVersionTest.
            context.runOnClient(client -> {
                net.minecraft.item.Item ironChain =
                        io.github.badbull643.economiesmod.client.MinecraftIds.idToItem("minecraft:iron_chain");
                if (!"minecraft:iron_chain".equals(
                        io.github.badbull643.economiesmod.client.MinecraftIds.itemToId(ironChain))) {
                    throw new IllegalStateException("a market of one version renamed an item");
                }
                io.github.badbull643.economiesmod.core.MarketState state = MarketStateHolder.get();
                MarketStateHolder.settings().markShared(state.marketId());
                if (!MarketStateHolder.sharedMarket()) {
                    throw new IllegalStateException("the market wasn't remembered as shared");
                }
                if (!"minecraft:chain".equals(
                        io.github.badbull643.economiesmod.client.MinecraftIds.itemToId(ironChain))) {
                    throw new IllegalStateException("iron chain didn't go by its first name");
                }
                if (io.github.badbull643.economiesmod.client.MinecraftIds.idToItem("minecraft:chain") != ironChain) {
                    throw new IllegalStateException("chain didn't come back as iron chain");
                }
                UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
                state.deposit(other, "minecraft:poplar_log", 30);
                state.deposit(other, "minecraft:chain", 10);
                state.submitOrder(new io.github.badbull643.economiesmod.core.Order(
                        960, 4, "minecraft:poplar_log", 30, false, other));
                state.submitOrder(new io.github.badbull643.economiesmod.core.Order(
                        961, 2, "minecraft:chain", 10, false, other));
            });
            openTab(context, BROWSE);
            context.runOnClient(client -> {
                try {
                    java.lang.reflect.Method rowsOf = MarketScreen.class.getDeclaredMethod("browseRows");
                    rowsOf.setAccessible(true);
                    java.util.List<?> rows = (java.util.List<?>) rowsOf.invoke(client.currentScreen);
                    boolean poplar = false, chain = false;
                    for (Object r : rows) {
                        String id = (String) r.getClass().getField("itemId").get(r);
                        if (id.equals("minecraft:poplar_log")) poplar = true;
                        if (id.equals("minecraft:chain")) chain = true;
                    }
                    if (poplar) throw new IllegalStateException("Browse shows a 26.3-only item on 1.21.11");
                    if (!chain) throw new IllegalStateException("Browse lost the chains");
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
            context.takeScreenshot("14-shared-browse");
        }
    }

    // ─── MarketStateHolder's private state, for putting the screen into its warning states ───

    private static Object holder(String name) {
        try {
            Field f = MarketStateHolder.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setHolder(String name, Object value) {
        try {
            Field f = MarketStateHolder.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One of MarketStateHolder's nested records, through its package-private constructor. */
    private static Object holderNew(String nested, Object... args) {
        try {
            for (Class<?> c : MarketStateHolder.class.getDeclaredClasses()) {
                if (!c.getSimpleName().equals(nested)) continue;
                for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
                    if (k.getParameterCount() != args.length) continue;
                    k.setAccessible(true);
                    return k.newInstance(args);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException("no " + nested + " taking " + args.length + " arguments");
    }

    /** Where this world's own copy of the market has got to. */
    private static long localHead() {
        Object log = holder("localLog");
        return log == null ? 0 : ((io.github.badbull643.economiesmod.core.EventLog) log).lastSeq();
    }

    /** Switches view on the open screen, as the menu does, without opening a fresh one. */
    private static void selectTabNow(Screen screen, int tab) {
        try {
            java.lang.reflect.Method m = MarketScreen.class.getDeclaredMethod("selectScreen", int.class);
            m.setAccessible(true);
            m.invoke(screen, tab);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setObject(Screen screen, String name, Object value) {
        try {
            Field f = MarketScreen.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(screen, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void openTab(ClientGameTestContext context, int tab) {
        selectTab(tab);
        context.setScreen(MarketScreen::new);
        context.waitTicks(5);
    }

    /** Which item the item view shows: the selection is saved in settings, which init reads. */
    private static void selectItem(String id) {
        io.github.badbull643.economiesmod.core.Settings s = MarketStateHolder.settings();
        if (s != null) s.setLastItem(id);
    }

    /** An order put through the applier, so a fill is recorded as a trade the way a real one is. */
    private static void apply(io.github.badbull643.economiesmod.core.MarketState state, UUID who, long seq,
                              String item, long price, long volume, boolean bid) {
        io.github.badbull643.economiesmod.core.Event.PlaceOrder p =
                new io.github.badbull643.economiesmod.core.Event.PlaceOrder();
        p.userId = who;
        p.marketId = state.marketId();
        p.itemId = item;
        p.price = price;
        p.volume = volume;
        p.isBid = bid;
        p.timestamp = System.currentTimeMillis() - 5 * 60_000;
        io.github.badbull643.economiesmod.core.SequencedEvent se =
                new io.github.badbull643.economiesmod.core.SequencedEvent();
        se.seq = seq;
        se.event = p;
        io.github.badbull643.economiesmod.core.EventApplier.Result r =
                io.github.badbull643.economiesmod.core.EventApplier.apply(state, se);
        if (!r.accepted) System.out.println("[gametest] order not applied: " + r.reason);
    }

    private static void setField(Screen screen, String name, boolean value) {
        try {
            Field f = MarketScreen.class.getDeclaredField(name);
            f.setAccessible(true);
            f.setBoolean(screen, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setStatic(String name, boolean value) {
        try {
            Field f = MarketScreen.class.getDeclaredField(name);
            f.setAccessible(true);
            f.setBoolean(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void call(Screen screen, String method) {
        try {
            java.lang.reflect.Method m = MarketScreen.class.getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(screen);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The tab is remembered in a static, so choosing one is setting that before opening. */
    private static void selectTab(int tab) {
        try {
            Field f = MarketScreen.class.getDeclaredField("activeScreen");
            f.setAccessible(true);
            f.setInt(null, tab);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object field(Screen screen, String name) {
        try {
            Field f = MarketScreen.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(screen);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setText(Screen screen, String name, String text) {
        ((TextFieldWidget) field(screen, name)).setText(text);
    }

    /** Presses a button by field name, for the ones whose label changes with what is chosen. */
    private static void click(Screen screen, String name) {
        ((ButtonWidget) field(screen, name)).onPress(new KeyInput(GLFW.GLFW_KEY_ENTER, 0, 0));
    }
}
