package io.github.badbull643.economiesmod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.badbull643.economiesmod.mixin.HandledScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;

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

    /** The shared button texture is 20 tall, whatever height we draw it at. */
    private static final int TEXTURE_HEIGHT = 20;

    private static final Text LABEL = new LiteralText("Market");

    private InventoryMarketButton() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledW, scaledH) -> {
            if (!(screen instanceof InventoryScreen)) return;
            Screens.getButtons(screen).add(new OpenMarket((InventoryScreen) screen));
        });
    }

    /**
     * Extends PressableWidget rather than ButtonWidget purely for draw order.
     *
     * ButtonWidget.renderButton draws the background and then, if hovered, the tooltip —
     * which leaves nowhere to put the icon: after that call it lands on top of the
     * tooltip, before it the background covers it. PressableWidget draws only the
     * background, so the three can be sequenced properly.
     */
    private static final class OpenMarket extends PressableWidget {

        private final InventoryScreen owner;

        OpenMarket(InventoryScreen owner) {
            // Blank, because PressableWidget draws the message centred in the button and
            // "Market" is far wider than 20px — it spilled out over the slots on both
            // sides. The name belongs to the tooltip, and to the narrator below.
            super(0, 0, WIDTH, HEIGHT, new LiteralText(""));
            this.owner = owner;
        }

        @Override
        protected MutableText getNarrationMessage() {
            return new TranslatableText("gui.narrate.button", LABEL);
        }

        @Override
        public void onPress() {
            MinecraftClient.getInstance().openScreen(new MarketScreen());
        }

        @Override
        public void renderButton(MatrixStack m, int mouseX, int mouseY, float delta) {
            follow();
            drawFrame(m);
            drawEmerald(m);
            if (isHovered()) owner.renderTooltip(m, LABEL, mouseX, mouseY);
        }

        /**
         * The vanilla button frame, drawn at a height it was never cut for.
         *
         * ClickableWidget passes its own height through as the texture's <em>source</em>
         * height, so at 18 it samples the top 18 rows of a 20-row button and the bottom
         * bevel is simply never drawn — which is what read as permanently pressed in.
         *
         * Drawn here as four pieces instead: each half takes its 9 rows from the matching
         * end of the source, so both bevels survive and the two middle rows are the only
         * thing dropped. That is the same trick vanilla itself uses for stretched widgets,
         * and it lets this match the recipe button's 20x18 exactly rather than standing
         * two pixels taller beside it.
         */
        private void drawFrame(MatrixStack m) {
            MinecraftClient.getInstance().getTextureManager().bindTexture(WIDGETS_TEXTURE);
            RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();

            int v = 46 + getYImage(isHovered()) * TEXTURE_HEIGHT;
            int halfW = WIDTH / 2;
            int halfH = HEIGHT / 2;
            int lowerV = v + TEXTURE_HEIGHT - halfH;
            int rightU = 200 - halfW;

            drawTexture(m, this.x, this.y, 0, v, halfW, halfH);
            drawTexture(m, this.x + halfW, this.y, rightU, v, halfW, halfH);
            drawTexture(m, this.x, this.y + halfH, 0, lowerV, halfW, halfH);
            drawTexture(m, this.x + halfW, this.y + halfH, rightU, lowerV, halfW, halfH);
        }

        /**
         * Re-anchors to the panel every frame.
         *
         * init() is not enough: opening the recipe book shifts the whole GUI and only
         * vanilla's own button is moved to match. Doing it here rather than from a
         * render callback also means it survives init() running more than once, which
         * it does on every window resize.
         */
        private void follow() {
            this.x = ((HandledScreenAccessor) owner).getPanelX() + OFFSET_X;
            // The same y vanilla gives the recipe button, since this is now the same size.
            this.y = owner.height / 2 - 22;
        }

        /** The emerald is the mod's mark for money, and matches the credit line. */
        private void drawEmerald(MatrixStack m) {
            ItemRenderer items = MinecraftClient.getInstance().getItemRenderer();

            // Depth testing is already on — ClickableWidget enables it before drawing
            // the button texture — but it is asserted rather than assumed, because item
            // models need it and a stray disabled one is silent. Restored afterwards to
            // the state HandledScreen sets around its own buttons.
            items.zOffset = 100.0F;
            RenderSystem.enableDepthTest();
            items.renderInGui(new ItemStack(Items.EMERALD), this.x + 2, this.y + 1);
            items.zOffset = 0.0F;
            RenderSystem.disableDepthTest();
        }
    }
}
