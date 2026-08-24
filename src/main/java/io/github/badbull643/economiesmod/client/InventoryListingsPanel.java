package io.github.badbull643.economiesmod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.badbull643.economiesmod.core.MarketState;
import io.github.badbull643.economiesmod.core.Order;
import io.github.badbull643.economiesmod.core.OrderBook;
import io.github.badbull643.economiesmod.core.Settings;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * What other people are selling, beside the inventory, without being asked.
 *
 * In a group of two or three, a listing that nobody notices is a trade that does not
 * happen. Everything the mod had for this was somewhere you go and look — the book, the
 * activity panel on Home — and the report from playing was the obvious one: you do not
 * go and look. The inventory is somewhere you already are, several times a minute, for
 * reasons that have nothing to do with the market.
 *
 * <h2>Read from the book, not from the activity feed</h2>
 *
 * The feed is the last 64 events of any kind, so a filtered view of it can be empty in a
 * busy market and can show an order that was cancelled an hour ago. The book is what is
 * <i>for sale now</i>, which is the question somebody standing in their inventory is
 * actually asking. It also answers "which events create a listing" for free: both
 * PlaceOrder and DepositAndList put an Order on the book, so neither can be forgotten
 * here the way a filter on event type would forget one.
 *
 * "The last N" comes out of the same place. An order's id is the sequence number of the
 * event that created it, so the highest ids are the most recently listed — and because
 * they are read off the book, cancelled and filled orders are simply not there to be
 * shown.
 *
 * <h2>Not your own</h2>
 *
 * You know what you listed. Your own orders would crowd out the only rows with news in
 * them, and in a market of three they are a third of the book.
 */
public final class InventoryListingsPanel {

    private static final int WIDTH = 132;

    /** Where a row's text starts: past the 16-pixel icon, with a pixel either side. */
    private static final int LABEL_X = 20;

    /**
     * An item icon is 16 pixels tall, so anything under 18 stacks them into each other.
     * The first version used 11 and the rows touched — visible immediately on a screen
     * and invisible in every other way.
     */
    private static final int ROW_H = 18;

    /** The title, plus enough air under it that the first row is not part of it. */
    private static final int HEADER_H = 16;

    /**
     * The market's name, above the title.
     *
     * Worth the ten pixels because the panel is the one place in the mod that shows a
     * market without being asked, and until now it showed one without saying which. Two
     * worlds in this tree hold markets both called "newB" and they are different
     * economies — a name is a label, not an identity, so this narrows "which market am I
     * looking at" without settling it.
     */
    private static final int NAME_H = 10;

    /** The gap between vanilla's panel and this one, matching vanilla's own margins. */
    private static final int GAP = 8;

    /**
     * Which side is showing, kept across screen opens but not across sessions.
     *
     * A static rather than a Settings field on purpose: it is a glance, not a
     * preference, and somebody who flipped to buys to answer one question should not
     * find the game still showing buys next week.
     */
    private static boolean showingBids = false;

    private InventoryListingsPanel() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledW, scaledH) -> {
            if (!(screen instanceof InventoryScreen)) return;
            Screens.getButtons(screen).add(new Listings((InventoryScreen) screen));
        });
    }

    /** One row of the panel, worked out once per draw rather than per frame element. */
    private static final class Row {
        final ItemStack icon;
        final String label;

        Row(ItemStack icon, String label) {
            this.icon = icon;
            this.label = label;
        }
    }

    /**
     * A widget whose bounds are the header and whose drawing is the whole panel.
     *
     * The bounds are what can be clicked, and only the header is meant to be: clicking a
     * row should do nothing, because there is nothing sensible for it to do that is not
     * a trade, and a trade one click from the inventory with no confirmation is exactly
     * what TradeCommands refuses to allow itself.
     *
     * Drawing from renderButton rather than from a render event keeps the panel in the
     * same draw phase as the Market button beside it — after the background, before the
     * tooltips — which is the phase that was already known to work here.
     */
    private static final class Listings extends PressableWidget {

        private final InventoryScreen owner;

        Listings(InventoryScreen owner) {
            super(0, 0, WIDTH, NAME_H + HEADER_H, new LiteralText(""));
            this.owner = owner;
        }

        @Override
        protected MutableText getNarrationMessage() {
            // The same two words the header shows. A narrator that says "Wanted" for a
            // panel headed "Buy" describes a screen nobody is looking at.
            return new TranslatableText("gui.narrate.button",
                    new LiteralText(showingBids ? "Buy orders" : "Sell orders"));
        }

        @Override
        public void onPress() {
            showingBids = !showingBids;
        }

        @Override
        public void renderButton(MatrixStack m, int mouseX, int mouseY, float delta) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null) return;

            Settings settings = MarketStateHolder.settings();
            if (settings != null && !settings.inventoryPanel()) {
                this.visible = false;
                return;
            }

            // Nothing to say without a market, and an empty frame beside the inventory
            // would be a permanent question about what it is.
            MarketState state = MarketStateHolder.get();
            if (state == null || state.marketId() == null) {
                this.visible = false;
                return;
            }

            if (!place()) {
                // No room at this window size and GUI scale. Drawing anyway would put
                // this over the inventory or off the edge, and a control that is
                // sometimes off-screen is worse than one that is sometimes absent.
                this.visible = false;
                return;
            }
            this.visible = true;

            int wanted = settings == null ? 6 : settings.inventoryPanelRows();
            Listing listing = rows(state, MinecraftIds.userIdOf(mc.player), wanted,
                    mc.textRenderer, WIDTH - LABEL_X - 2);
            List<Row> rows = listing.rows;
            int bodyH = NAME_H + HEADER_H + Math.max(1, rows.size()) * ROW_H;
            Panels.vanillaPanel(m, this.x, this.y, WIDTH, bodyH);

            String market = state.marketName();
            if (market != null && !market.isEmpty()) {
                mc.textRenderer.drawWithShadow(m,
                        mc.textRenderer.trimToWidth(market, WIDTH - 4),
                        this.x + 2, this.y + 2, 0x9080C0);
            }

            // Named for what is in the list, not for what the reader could do about it.
            // A bare "Buy" or "Sell" does not say whose action it is: the same word over
            // the same rows means "orders to buy" to one person and "things you can buy"
            // to the next, and both readings are reasonable, which is how two people
            // read one header two ways within an hour of it being drawn.
            //
            // "You can buy" would also be unambiguous and was turned down for making a
            // claim this panel cannot check — whether you hold the credits, or the
            // stack. This is a fact about the market; that would be a promise about you.
            String title = showingBids ? "Buy orders" : "Sell orders";
            mc.textRenderer.drawWithShadow(m, title, this.x + 2, this.y + NAME_H + 2,
                    isHovered() ? 0xFFFFFF : 0xFFDD66);

            if (rows.isEmpty()) {
                // Which kind of empty, because they are different facts and only one of
                // them is about the market. A panel that says "nothing listed" while the
                // player is looking at their own four listings on the Market screen reads
                // as broken — and that is exactly what happened the first time somebody
                // placed orders and then went looking for them here. It is working; they
                // are their own, and this says so.
                String empty;
                if (listing.mine > 0) {
                    empty = "only your " + listing.mine
                            + (listing.mine == 1 ? " order" : " orders");
                } else {
                    empty = showingBids ? "nobody is buying" : "nothing listed";
                }
                mc.textRenderer.drawWithShadow(m, empty,
                        this.x + 2, this.y + NAME_H + HEADER_H + 2, 0x707070);
                hint(m, mouseX, mouseY);
                return;
            }

            ItemRenderer items = mc.getItemRenderer();
            int y = this.y + NAME_H + HEADER_H;
            for (Row row : rows) {
                items.zOffset = 100.0F;
                RenderSystem.enableDepthTest();
                items.renderInGui(row.icon, this.x + 1, y);
                items.zOffset = 0.0F;
                RenderSystem.disableDepthTest();

                mc.textRenderer.drawWithShadow(m, row.label, this.x + LABEL_X, y + 5, 0xE0E0E0);
                y += ROW_H;
            }
            hint(m, mouseX, mouseY);
        }

        /**
         * Drawn after the rows, and only on hover.
         *
         * Nothing else on screen says the header can be clicked, so the panel would
         * otherwise have a second half nobody finds — but a permanent instruction is a
         * row's worth of space spent on something you need once. Last, because a tooltip
         * drawn before the rows would be painted over by them.
         */
        private void hint(MatrixStack m, int mouseX, int mouseY) {
            if (!isHovered()) return;
            // Says what the other side is *for*, which the header deliberately does not:
            // the header is a label on a list, and this is the one place there is room to
            // answer "and why would I want that".
            owner.renderTooltip(m, new LiteralText(showingBids
                    ? "Click for what is on sale"
                    : "Click for what people will buy from you"), mouseX, mouseY);
        }

        /**
         * Anchors to where vanilla actually drew itself, this frame.
         *
         * The inventory's left edge moves when the recipe book opens — that is the whole
         * reason HandledScreenAccessor exists — so this asks every frame rather than
         * remembering a position from init. Returns false when the answer would not fit
         * on screen, which at GUI scale 4 on a small window is most of the time.
         */
        private boolean place() {
            io.github.badbull643.economiesmod.mixin.HandledScreenAccessor panel =
                    (io.github.badbull643.economiesmod.mixin.HandledScreenAccessor) owner;
            int left = panel.getPanelX() + panel.getPanelWidth() + GAP;
            if (left + WIDTH + 4 > owner.width) return false;

            this.x = left;
            this.y = panel.getPanelY() + 4;
            return true;
        }
    }

    /**
     * The most recently listed orders on the showing side, excluding the viewer's own.
     *
     * Sorted by order id descending, which is sequence order: the newest listing that is
     * still resting comes first. Not sorted by price — this answers "what is new here",
     * and a cheapest-first list would be the same six rows every time somebody undercut
     * once and then nothing happened for a week.
     */
    private static Listing rows(MarketState state, UUID me, int limit,
                                TextRenderer text, int room) {
        List<Order> found = new ArrayList<>();
        int mine = 0;
        for (String itemId : state.activeItems()) {
            OrderBook book = state.peekBook(itemId);
            if (book == null) continue;
            for (Order o : showingBids ? book.restingBids() : book.restingAsks()) {
                if (o.userID().equals(me)) mine++;
                else found.add(o);
            }
        }
        found.sort(Comparator.comparingLong(Order::orderId).reversed());

        List<Row> rows = new ArrayList<>();
        for (Order o : found) {
            if (rows.size() >= limit) break;
            ItemStack icon = new ItemStack(MinecraftIds.idToItem(o.itemID()));
            rows.add(new Row(icon, label(icon.getName().getString(),
                    o.volume(), o.value(), text, room)));
        }
        return new Listing(rows, mine);
    }

    /**
     * One row's text, measured against the space it has rather than counted in letters.
     *
     * The first version trimmed the name to eleven characters and then appended the
     * quantity and price to it, which is a guess at a width in a proportional font —
     * "Cobblestone" is exactly eleven and the row ran out through the border. The
     * quantity and price are the news and are never trimmed; the name gives up whatever
     * space they need.
     */
    private static String label(String name, long volume, long price,
                                TextRenderer text, int room) {
        String tail = " " + volume + " @" + price;
        int forName = room - text.getWidth(tail);
        if (text.getWidth(name) <= forName) return name + tail;

        String cut = text.trimToWidth(name, Math.max(0, forName - text.getWidth("..")));
        return cut + ".." + tail;
    }

    /** The rows to draw, and how many were left out for being the viewer's own. */
    private static final class Listing {
        final List<Row> rows;
        final int mine;

        Listing(List<Row> rows, int mine) {
            this.rows = rows;
            this.mine = mine;
        }
    }
}
