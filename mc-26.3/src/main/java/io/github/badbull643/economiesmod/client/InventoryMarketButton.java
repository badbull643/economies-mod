package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.mixin.HandledScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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

    private static final Component LABEL = Component.literal("Market");

    private InventoryMarketButton() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledW, scaledH) -> {
            if (!(screen instanceof InventoryScreen)) return;
            Screens.getWidgets(screen).add(new OpenMarket((InventoryScreen) screen));
        });
    }

    /**
     * Extends PressableWidget rather than ButtonWidget so the icon is ours to draw.
     *
     * PressableWidget draws the frame and calls drawIcon for whatever sits on it, which is
     * exactly the seam wanted: ButtonWidget would draw its own label there instead. Since
     * 1.21 the frame is a nine-slice sprite that stretches to any height, so the 20x18
     * footprint matching the recipe button no longer needs the hand-built four-piece frame
     * the 1.16.5 version drew — that existed only because the old texture could not be
     * cut at 18 rows without losing its bottom bevel.
     */
    private static final class OpenMarket extends AbstractButton {

        private final InventoryScreen owner;

        OpenMarket(InventoryScreen owner) {
            // Blank, because PressableWidget draws the message centred in the button and
            // "Market" is far wider than 20px — it spilled out over the slots on both
            // sides. The name belongs to the tooltip, and to the narrator below.
            super(0, 0, WIDTH, HEIGHT, Component.literal(""));
            this.owner = owner;
            setTooltip(Tooltip.create(LABEL));
        }

        @Override
        protected MutableComponent createNarrationMessage() {
            return Component.translatable("gui.narrate.button", LABEL);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput builder) {
            builder.add(NarratedElementType.TITLE, createNarrationMessage());
        }

        @Override
        public void onPress(InputWithModifiers input) {
            Minecraft.getInstance().gui.setScreen(new MarketScreen());
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
         * PressableWidget draws nothing of its own — ButtonWidget supplies the frame from
         * its own drawIcon, so a subclass that overrides it has to ask for the frame itself.
         * The emerald is the mod's mark for money, and matches the credit line.
         */
        @Override
        protected void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
            extractDefaultSprite(context);
            context.item(new ItemStack(Items.EMERALD), getX() + 2, getY() + 1);
        }
    }
}
