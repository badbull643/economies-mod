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

    private static final String[] TABS = {"info", "trading", "network", "market", "settings"};

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

            // A tab chosen by clicking it, as a player does. Every other step here picks the tab
            // through a field, and that never once exercised the click — which is how 26.x
            // numbering mouse buttons from 1 left every tab dead without this test noticing.
            openTab(context, 0);
            int[] tradingTab = context.computeOnClient(client -> tabRect(client.gui.screen(), 1));
            clickAt(context, tradingTab[0] + tradingTab[2] / 2.0, tradingTab[1] + tradingTab[3] / 2.0);
            int shown = context.computeOnClient(client -> activeTab());
            if (shown != 1) throw new AssertionError("clicking the Trading tab left tab " + shown + " showing");

            // Every tab of the market screen, before any market exists.
            for (int i = 0; i < TABS.length; i++) {
                openTab(context, i);
                context.takeScreenshot("02-no-market-" + TABS[i]);
            }

            // The create-a-market confirmation: a modal over a screen full of text.
            openTab(context, 3);
            context.runOnClient(client -> setText(client.gui.screen(), "marketNameField", "Test Market"));
            context.clickScreenButton("Create a new market");
            context.waitTicks(3);
            context.takeScreenshot("03-create-overlay");
            // Answered by clicking Cancel with the mouse, not by Escape: the overlay's buttons
            // are hit-tested by hand, and that is the code a left click has to reach.
            int[] cancel = context.computeOnClient(client -> {
                net.minecraft.client.gui.components.Button b =
                        (net.minecraft.client.gui.components.Button) field(client.gui.screen(), "overlayDismissButton");
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

            // Every tab again, now that there is a market and a creator.
            for (int i = 0; i < TABS.length; i++) {
                openTab(context, i);
                context.takeScreenshot("04-market-" + TABS[i]);
            }

            // The item picker, which clips a scrolling grid and layers over the screen.
            openTab(context, 1);
            context.runOnClient(client -> click(client.gui.screen(), "itemButton"));
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
        }
    }

    private static void openTab(ClientGameTestContext context, int tab) {
        selectTab(tab);
        context.setScreen(MarketScreen::new);
        context.waitTicks(5);
    }

    /** A real left click at a GUI-scaled point: the cursor is placed in window pixels. */
    private static void clickAt(ClientGameTestContext context, double guiX, double guiY) {
        int scale = context.computeOnClient(client -> client.getWindow().getGuiScale());
        context.getInput().setCursorPos(guiX * scale, guiY * scale);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(3);
    }

    private static int[] tabRect(Screen screen, int tab) {
        try {
            java.lang.reflect.Method m = MarketScreen.class.getDeclaredMethod("tabRect", int.class);
            m.setAccessible(true);
            return (int[]) m.invoke(screen, tab);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int activeTab() {
        try {
            Field f = MarketScreen.class.getDeclaredField("activeScreen");
            f.setAccessible(true);
            return f.getInt(null);
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
        ((EditBox) field(screen, name)).setValue(text);
    }

    /** Presses a button by field name, for the ones whose label changes with what is chosen. */
    private static void click(Screen screen, String name) {
        ((Button) field(screen, name)).onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
    }
}
