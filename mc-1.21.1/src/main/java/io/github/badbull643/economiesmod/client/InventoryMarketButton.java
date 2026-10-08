package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.mixin.HandledScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

/**
 * A way into the market from the inventory, next to the recipe book.
 *
 * The keybind is not discoverable. Nothing in the game says the market is on M, so a
 * player who installs the mod and never reads its page never finds it — and the
 * inventory is both where items live and where someone wondering what to do with their
 * items already is.
 *
 * Added, never substituted: M still works and is still the fast way in. This is the
 * path for people who don't know M exists yet.
 */
public final class InventoryMarketButton {

    /**
     * Beside vanilla's recipe-book button, which sits at x+104 and is 20 wide.
     *
     * The band between the crafting grid and the inventory rows is empty in the vanilla
     * texture, and the recipe book expands leftward, so nothing here gets covered when
     * it opens.
     */
    private static final int OFFSET_X = 126;

    /** Exactly the recipe button's footprint, so the pair reads as one row of controls. */
    private static final int WIDTH = 20;
    private static final int HEIGHT = 18;

    private static final Text LABEL = Text.literal("Market");

    private InventoryMarketButton() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledW, scaledH) -> {
            if (!(screen instanceof InventoryScreen)) return;
            Screens.getButtons(screen).add(new OpenMarket((InventoryScreen) screen));
        });
    }

    /**
     * Extends PressableWidget rather than ButtonWidget so the icon is ours to draw.
     *
     * PressableWidget draws the frame and a centred message, and the message here is blank,
     * so drawing the emerald after it puts the icon on a plain button. Since 1.20.2 the
     * frame is a nine-slice sprite that stretches to any height, so the 20x18
     * footprint matching the recipe button no longer needs the hand-built four-piece frame
     * the 1.16.5 version drew — that existed only because the old texture could not be
     * cut at 18 rows without losing its bottom bevel.
     */
    private static final class OpenMarket extends PressableWidget {

        private final InventoryScreen owner;

        OpenMarket(InventoryScreen owner) {
            // Blank, because PressableWidget draws the message centred in the button and
            // "Market" is far wider than 20px — it spilled out over the slots on both
            // sides. The name belongs to the tooltip, and to the narrator below.
            super(0, 0, WIDTH, HEIGHT, Text.literal(""));
            this.owner = owner;
            setTooltip(Tooltip.of(LABEL));
        }

        @Override
        protected MutableText getNarrationMessage() {
            return Text.translatable("gui.narrate.button", LABEL);
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            builder.put(NarrationPart.TITLE, getNarrationMessage());
        }

        @Override
        public void onPress() {
            MinecraftClient.getInstance().setScreen(new MarketScreen());
        }

        /**
         * Anchored to the panel every time it is asked, not once in init().
         *
         * Opening the recipe book shifts the whole GUI and only vanilla's own button is
         * moved to match. Answering from the screen's current position means the frame,
         * the icon, the hover test and the tooltip all agree without anything having to
         * remember to re-anchor, and it survives init() running more than once, which it
         * does on every window resize.
         */
        @Override
        public int getX() {
            return ((HandledScreenAccessor) owner).getPanelX() + OFFSET_X;
        }

        /** The same y vanilla gives the recipe button, since this is now the same size. */
        @Override
        public int getY() {
            return owner.height / 2 - 22;
        }

        /**
         * The frame, then the emerald on it.
         *
         * PressableWidget.renderWidget draws the frame and the (blank) message; the emerald
         * goes on afterwards. It is the mod's mark for money, and matches the credit line.
         */
        @Override
        protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            super.renderWidget(context, mouseX, mouseY, delta);
            context.drawItem(new ItemStack(Items.EMERALD), getX() + 2, getY() + 1);
        }
    }
}
