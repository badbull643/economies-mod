package io.github.badbull643.economiesmod.test;

import io.github.badbull643.economiesmod.client.MarketScreen;
import io.github.badbull643.economiesmod.client.MarketStateHolder;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import com.mojang.blaze3d.platform.InputConstants;

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
    private static final int BROWSE = 0, MARKET = 6, ITEM = 8;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            context.takeScreenshot("00-world");

            // The inventory, where the emerald button and the listings panel live.
            context.getInput().pressKey(InputConstants.KEY_E);
            context.waitTicks(10);
            context.takeScreenshot("01-inventory");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitTicks(5);

            // Every tab of the market screen, before any market exists.
            for (int i = 0; i < TABS.length; i++) {
                openTab(context, i);
                context.takeScreenshot("02-no-market-" + TABS[i]);
            }

            // A view chosen by clicking the menu, as a player does. Every other step here picks
            // the view through a field, and that never once exercised the click — which is how
            // 26.x numbering mouse buttons from 1 left every tab dead without this test noticing.
            openTab(context, BROWSE);
            int[] menuButton = context.computeOnClient(client -> rect(client.gui.screen(), "menuButtonRect"));
            clickAt(context, menuButton[0] + menuButton[2] / 2.0, menuButton[1] + menuButton[3] / 2.0);
            boolean menuShown = context.computeOnClient(client -> (Boolean) field(client.gui.screen(), "menuOpen"));
            if (!menuShown) throw new AssertionError("clicking the menu button did not open the menu");
            int[] historyEntry = context.computeOnClient(client -> {
                int[] menu = rect(client.gui.screen(), "menuRect");
                return new int[]{menu[0], entryY(client.gui.screen(), 3), menu[2], 12};
            });
            clickAt(context, historyEntry[0] + historyEntry[2] / 2.0, historyEntry[1] + historyEntry[3] / 2.0);
            int shown = context.computeOnClient(client -> activeView());
            if (shown != 3) throw new AssertionError("clicking History in the menu left view " + shown + " showing");

            // The create-a-market confirmation: a modal over a screen full of text.
            openTab(context, MARKET);
            context.runOnClient(client -> setText(client.gui.screen(), "marketNameField", "Test Market"));
            context.clickScreenButton("Create a new market");
            context.waitTicks(3);
            context.takeScreenshot("03-create-overlay");
            // Answered by clicking Cancel with the mouse, not by Escape: the overlay's buttons
            // are hit-tested by hand, and that is the code a left click has to reach.
            int[] cancel = context.computeOnClient(client -> {
                Button b = (Button) field(client.gui.screen(), "overlayDismissButton");
                return new int[]{b.getX(), b.getY(), b.getWidth(), b.getHeight()};
            });
            clickAt(context, cancel[0] + cancel[2] / 2.0, cancel[1] + cancel[3] / 2.0);
            boolean answered = context.computeOnClient(client ->
                    ((java.util.Deque<?>) field(client.gui.screen(), "overlays")).isEmpty());
            if (!answered) throw new AssertionError("clicking the overlay's Cancel did not close it");
            context.waitTicks(2);

            // Make the market for real, and put something in the inventory to trade.
            context.runOnClient(client -> {
                Path dir = MarketStateHolder.worldDirOrNull();
                if (dir == null) throw new IllegalStateException("no world dir");
                UUID me = client.player.getUUID();
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
                UUID me = client.player.getUUID();
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
            context.runOnClient(client -> setField(client.gui.screen(), "menuOpen", true));
            context.waitTicks(2);
            context.takeScreenshot("04-market-menu");

            // A search that matches nothing here, which offers every item in the game instead.
            openTab(context, BROWSE);
            context.runOnClient(client -> setText(client.gui.screen(), "searchField", "gold"));
            context.waitTicks(2);
            context.takeScreenshot("04-market-search");

            // The item picker, which clips a scrolling grid and layers over the screen.
            openTab(context, BROWSE);
            context.runOnClient(client -> call(client.gui.screen(), "openPicker"));
            context.waitTicks(3);
            context.takeScreenshot("05-picker");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitTicks(2);

            // And the inventory again, which now holds a market and so shows the panel.
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            // GUI scale 1 so the panel has room beside the inventory, and long enough for
            // the toasts the give commands raised to leave the corner it would sit in.
            context.runOnClient(client -> {
                client.options.guiScale().set(1);
                client.resizeGui();
            });
            context.waitTicks(160);
            context.getInput().pressKey(InputConstants.KEY_E);
            context.waitTicks(10);
            context.takeScreenshot("06-inventory-with-market");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
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
            context.getInput().pressKey(InputConstants.KEY_E);
            context.waitTicks(10);
            context.takeScreenshot("08-hostile-order-inventory");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitTicks(3);
            for (int i = 0; i < TABS.length; i++) {
                openTab(context, i);
                context.takeScreenshot("09-hostile-order-" + TABS[i]);
            }
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
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
            context.getInput().pressKey(InputConstants.KEY_E);
            context.waitTicks(10);
            context.takeScreenshot("10-extreme-orders-inventory");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitTicks(3);
            for (int i = 0; i < TABS.length; i++) {
                openTab(context, i);
                context.takeScreenshot("11-extreme-orders-" + TABS[i]);
            }
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitTicks(3);

            // The chat commands, which now register through a different Fabric API.
            for (String command : new String[] {"trade", "trade balance", "trade price iron_ingot",
                    "trade hostrules", "trade hostconfig"}) {
                context.runOnClient(client -> client.getConnection().sendCommand(command));
                context.waitTicks(3);
            }
            context.runOnClient(client -> client.gui.hud.getChat().scrollChat(0));
            context.takeScreenshot("07-commands");

            // A market shared across versions, seen from the newest one: chains come back
            // as iron chains, and poplar logs, which only 26.3 has, are listed here.
            context.runOnClient(client -> {
                net.minecraft.world.item.Item ironChain =
                        io.github.badbull643.economiesmod.client.MinecraftIds.idToItem("minecraft:iron_chain");
                io.github.badbull643.economiesmod.core.MarketState state = MarketStateHolder.get();
                MarketStateHolder.settings().markShared(state.marketId());
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
                    java.util.List<?> rows = (java.util.List<?>) rowsOf.invoke(client.gui.screen());
                    boolean poplar = false, chain = false;
                    for (Object r : rows) {
                        String id = (String) r.getClass().getField("itemId").get(r);
                        if (id.equals("minecraft:poplar_log")) poplar = true;
                        if (id.equals("minecraft:chain")) chain = true;
                    }
                    if (!poplar) throw new IllegalStateException("Browse hides poplar logs on 26.3");
                    if (!chain) throw new IllegalStateException("Browse lost the chains");
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
            context.takeScreenshot("14-shared-browse");
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

    /** A real left click at a GUI-scaled point: the cursor is placed in window pixels. */
    private static void clickAt(ClientGameTestContext context, double guiX, double guiY) {
        int scale = context.computeOnClient(client -> client.getWindow().getGuiScale());
        context.getInput().setCursorPos(guiX * scale, guiY * scale);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(3);
    }

    private static int[] rect(Screen screen, String method) {
        try {
            java.lang.reflect.Method m = MarketScreen.class.getDeclaredMethod(method);
            m.setAccessible(true);
            return (int[]) m.invoke(screen);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int entryY(Screen screen, int index) {
        try {
            java.lang.reflect.Method m = MarketScreen.class.getDeclaredMethod("menuEntryY", int.class);
            m.setAccessible(true);
            return (int) m.invoke(screen, index);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int activeView() {
        try {
            Field f = MarketScreen.class.getDeclaredField("activeScreen");
            f.setAccessible(true);
            return f.getInt(null);
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
        ((EditBox) field(screen, name)).setValue(text);
    }

    /** Presses a button by field name, for the ones whose label changes with what is chosen. */
    private static void click(Screen screen, String name) {
        ((Button) field(screen, name)).onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
    }
}
