package io.github.badbull643.economiesmod.screentour;

import io.github.badbull643.economiesmod.client.MarketScreen;
import io.github.badbull643.economiesmod.client.MarketStateHolder;
import io.github.badbull643.economiesmod.core.MarketState;
import io.github.badbull643.economiesmod.core.Order;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.ScreenshotUtils;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.world.level.LevelInfo;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Walks the screens in a real 1.16.5 client and photographs each one, then quits.
 *
 * The stand-in for the client game test mc-1.21.11 has: Fabric's client game-test API does
 * not exist for 1.16.5, and without it nothing would ever draw these screens except a person.
 * Like that test it asserts nothing about what is drawn — a screenshot cannot say whether a
 * layout is right — but a screen that throws while rendering crashes the client, and with it
 * the run, which is the failure a compile cannot catch.
 *
 * Only ever on the classpath of `./gradlew runScreenTour`, and inert unless the run sets
 * -Deconomiesmod.screentour=true. Screenshots land in build/run-screentour/screenshots.
 */
public class ScreenTour implements ClientModInitializer {

    // In MarketScreen's SCREEN_ order. "item" is one item's view, reached from Browse.
    private static final String[] TABS = {"browse", "sell", "orders", "history", "leaderboard",
            "network", "market", "settings", "item"};
    private static final int BROWSE = 0, MARKET = 6, ITEM = 8;

    /** One thing to do, once {@code ready} holds, followed by {@code after} idle ticks. */
    private static final class Step {
        final Predicate<MinecraftClient> ready;
        final Consumer<MinecraftClient> action;
        final int after;

        Step(Predicate<MinecraftClient> ready, Consumer<MinecraftClient> action, int after) {
            this.ready = ready;
            this.action = action;
            this.after = after;
        }
    }

    private final Deque<Step> steps = new ArrayDeque<>();
    private int idle = 0;
    private int shot = 0;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("economiesmod.screentour")) return;
        plan();
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient client) {
        if (idle > 0) { idle--; return; }
        Step next = steps.peekFirst();
        if (next == null || !next.ready.test(client)) return;
        steps.pollFirst();
        next.action.accept(client);
        idle = next.after;
    }

    private void then(Consumer<MinecraftClient> action, int after) {
        steps.addLast(new Step(c -> true, action, after));
    }

    private void when(Predicate<MinecraftClient> ready, Consumer<MinecraftClient> action, int after) {
        steps.addLast(new Step(ready, action, after));
    }

    private void screenshot(String name) {
        then(c -> {
            String file = String.format("%02d-%s.png", shot++, name);
            ScreenshotUtils.saveScreenshot(c.runDirectory, file, c.getWindow().getFramebufferWidth(),
                    c.getWindow().getFramebufferHeight(), c.getFramebuffer(), m -> {});
            System.out.println("[screentour] " + file);
        }, 2);
    }

    private void open(Consumer<MinecraftClient> how) {
        then(how, 10);
    }

    private void tab(int i) {
        open(c -> {
            setStatic("activeScreen", i);
            c.openScreen(new MarketScreen());
        });
    }

    private void plan() {
        // A fresh survival world with commands on, so the tour can give itself items, from the
        // title screen.
        // Only once the loading overlay has gone: the first screen appears while resources
        // are still loading, and a world started then gets block updates before the block
        // models exist — vanilla throws on every one of them until they do.
        when(c -> c.world == null && c.getOverlay() == null
                && c.currentScreen instanceof TitleScreen, c -> {
            String name = "screentour-" + System.currentTimeMillis();
            LevelInfo info = new LevelInfo(name, GameMode.SURVIVAL, false, Difficulty.PEACEFUL,
                    true, new GameRules(), DataPackSettings.SAFE_MODE);
            DynamicRegistryManager.Impl registries = DynamicRegistryManager.create();
            c.createWorld(name, info, registries, GeneratorOptions.method_31112(registries));
        }, 20);
        when(c -> c.player != null && c.world != null && c.currentScreen == null,
                c -> System.out.println("[screentour] in the world"), 100);
        screenshot("world");

        // The inventory, where the emerald button lives.
        open(c -> c.openScreen(new InventoryScreen(c.player)));
        screenshot("inventory");

        // Every tab before any market exists.
        for (int i = 0; i < TABS.length; i++) {
            tab(i);
            screenshot("no-market-" + TABS[i]);
        }

        // The create-a-market confirmation: a modal over a screen full of text.
        tab(MARKET);
        then(c -> {
            ((TextFieldWidget) field(c.currentScreen, "marketNameField")).setText("Test Market");
            press(c.currentScreen, "Create a new market");
        }, 5);
        screenshot("create-overlay");
        then(c -> c.currentScreen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0), 3);

        // The market for real, things to trade, and other people's orders — including an
        // id that is not an identifier and prices at the top of a long, both of which any
        // participant can put in a market and every screen then has to draw.
        then(c -> {
            Path dir = MarketStateHolder.worldDirOrNull();
            if (dir == null) throw new IllegalStateException("no world dir");
            if (!MarketStateHolder.createMarket(dir, c.player.getUuid(), "Test Market")) {
                throw new IllegalStateException("market was not created");
            }
            MinecraftServer server = c.getServer();
            server.execute(() -> {
                for (String give : new String[] {"minecraft:iron_ingot 64", "minecraft:diamond 5",
                        "minecraft:cobblestone 200"}) {
                    server.getCommandManager().execute(server.getCommandSource(),
                            "give @a " + give);
                }
            });
        }, 10);

        // Swords: one straight off the crafting table, one damaged and one enchanted. In this
        // version Minecraft gives every new tool a Damage tag of 0, which used to make the fresh
        // one look like the other two; only the fresh one is plain enough to trade.
        then(c -> {
            MinecraftServer server = c.getServer();
            server.execute(() -> {
                for (String give : new String[] {"minecraft:iron_sword 1",
                        "minecraft:iron_sword{Damage:10} 1",
                        "minecraft:diamond_sword{Enchantments:[{id:\"minecraft:sharpness\",lvl:1s}]} 1"}) {
                    server.getCommandManager().execute(server.getCommandSource(), "give @a " + give);
                }
            });
        }, 10);
        then(c -> {
            io.github.badbull643.economiesmod.client.InventoryBridge.Holdings held =
                    io.github.badbull643.economiesmod.client.InventoryBridge.held(c.player);
            int ironSwords = 0;
            boolean diamondSword = false;
            for (io.github.badbull643.economiesmod.client.InventoryBridge.Holding h : held.items) {
                if (h.item == net.minecraft.item.Items.IRON_SWORD) ironSwords = h.count;
                if (h.item == net.minecraft.item.Items.DIAMOND_SWORD) diamondSword = true;
            }
            if (ironSwords != 1) {
                throw new IllegalStateException("expected the fresh iron sword, and only it, to be tradable: " + ironSwords);
            }
            if (diamondSword) throw new IllegalStateException("an enchanted sword was offered for trade");
            System.out.println("[screentour] swords: the fresh one is tradable, the damaged and enchanted ones are skipped ("
                    + held.skipped + " skipped)");
        }, 2);
        then(c -> {
            MarketState state = MarketStateHolder.get();
            UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
            state.deposit(other, "minecraft:diamond", 50);
            state.deposit(other, "minecraft:iron_ingot", 200);
            state.wallets().adjust(other, 5000);
            state.submitOrder(new Order(900, 40, "minecraft:diamond", 12, false, other));
            state.submitOrder(new Order(901, 55, "minecraft:diamond", 3, false, other));
            state.submitOrder(new Order(902, 6, "minecraft:iron_ingot", 64, false, other));
            state.submitOrder(new Order(903, 3, "minecraft:iron_ingot", 30, true, other));

            UUID hostile = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
            state.deposit(hostile, "Not An Item!", 5);
            state.wallets().adjust(hostile, 5000);
            state.submitOrder(new Order(904, 10, "Not An Item!", 5, false, hostile));
            state.submitOrder(new Order(905, 4, "Not An Item!", 5, true, hostile));

            UUID whale = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
            state.deposit(whale, "minecraft:diamond", Long.MAX_VALUE - 10);
            state.deposit(whale, "minecraft:iron_ingot", 5);
            state.wallets().adjust(whale, Long.MAX_VALUE - 100);
            state.submitOrder(new Order(906, 1, "minecraft:diamond", Long.MAX_VALUE - 10, false, whale));
            state.submitOrder(new Order(907, Long.MAX_VALUE - 1, "minecraft:iron_ingot", 1, false, whale));
            state.submitOrder(new Order(908, Long.MAX_VALUE - 200, "minecraft:cobblestone", 1, true, whale));

            // Some trading of our own, so Your orders, History and the leaderboard have rows:
            // fills through the applier, so they are recorded as trades, a run of iron at
            // drifting prices for the chart, and more resting orders than the panel shows.
            UUID me = c.player.getUuid();
            state.wallets().adjust(me, 2000);
            state.deposit(me, "minecraft:iron_ingot", 40);
            apply(state, me, 9001, "minecraft:diamond", 40, 2, true);
            apply(state, me, 9002, "minecraft:iron_ingot", 3, 10, false);
            long[] prices = {4, 5, 5, 6, 5, 7, 6, 8, 7, 6, 7, 8};
            for (int i = 0; i < prices.length; i++) {
                state.submitOrder(new Order(920 + i, prices[i], "minecraft:iron_ingot", 2, false, other));
                apply(state, me, 9100 + i, "minecraft:iron_ingot", prices[i], 2, true);
            }
            state.submitOrder(new Order(910, 30, "minecraft:diamond", 4, true, me));
            state.submitOrder(new Order(911, 9, "minecraft:iron_ingot", 16, false, me));
            for (int i = 0; i < 12; i++) {
                state.submitOrder(new Order(940 + i, 1 + i, "minecraft:cobblestone", 2, true, me));
            }
            if (MarketStateHolder.settings() != null) MarketStateHolder.settings().setLastItem("minecraft:iron_ingot");
        }, 5);

        for (int i = 0; i < TABS.length; i++) {
            tab(i);
            screenshot("market-" + TABS[i]);
        }

        // An item's advanced side, and the view menu open — both drawn over icons, which is
        // what the depth lift this version needs is for.
        then(c -> setStaticBool("itemAdvanced", true), 0);
        tab(ITEM);
        screenshot("market-item-advanced");
        then(c -> setStaticBool("itemAdvanced", false), 0);
        tab(BROWSE);
        then(c -> setBool(c.currentScreen, "menuOpen", true), 3);
        screenshot("market-menu");

        // The item picker, which layers over the screen and clips a scrolling grid.
        tab(BROWSE);
        then(c -> call(c.currentScreen, "openPicker"), 5);
        screenshot("picker");
        then(c -> c.currentScreen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0), 3);
        then(c -> c.openScreen(null), 3);

        // The inventory with a market, at a GUI scale where the panel has room.
        then(c -> {
            c.options.guiScale = 1;
            c.onResolutionChanged();
        }, 40);
        open(c -> c.openScreen(new InventoryScreen(c.player)));
        screenshot("inventory-with-market");
        then(c -> c.openScreen(null), 3);

        // The chat commands.
        for (String command : new String[] {"trade", "trade balance", "trade price iron_ingot",
                "trade hostrules", "trade hostconfig"}) {
            then(c -> c.player.sendChatMessage("/" + command), 3);
        }
        open(c -> c.openScreen(new ChatScreen("")));
        screenshot("commands");

        then(c -> {
            System.out.println("[screentour] done — " + shot + " screenshots");
            c.scheduleStop();
        }, 0);
    }

    // ─────────── reflection, for the fields the screen keeps private ───────────

    private static Object field(Screen screen, String name) {
        try {
            Field f = MarketScreen.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(screen);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setStaticBool(String name, boolean value) {
        try {
            Field f = MarketScreen.class.getDeclaredField(name);
            f.setAccessible(true);
            f.setBoolean(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setBool(Screen screen, String name, boolean value) {
        try {
            Field f = MarketScreen.class.getDeclaredField(name);
            f.setAccessible(true);
            f.setBoolean(screen, value);
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

    /** An order put through the applier, so a fill is recorded as a trade the way a real one is. */
    private static void apply(MarketState state, UUID who, long seq, String item, long price, long volume, boolean bid) {
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
        io.github.badbull643.economiesmod.core.EventApplier.apply(state, se);
    }

    private static void setStatic(String name, int value) {
        try {
            Field f = MarketScreen.class.getDeclaredField(name);
            f.setAccessible(true);
            f.setInt(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Presses the button on screen whose label reads {@code label}. */
    private static void press(Screen screen, String label) {
        for (Element e : screen.children()) {
            if (e instanceof ButtonWidget && label.equals(((ButtonWidget) e).getMessage().getString())) {
                ((ButtonWidget) e).onPress();
                return;
            }
        }
        throw new IllegalStateException("no button labelled " + label);
    }
}
