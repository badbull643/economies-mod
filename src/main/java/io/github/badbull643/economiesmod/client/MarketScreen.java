package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.*;
import io.github.badbull643.economiesmod.core.net.PeerPoll;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.LiteralText;
import net.minecraft.text.OrderedText;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.registry.Registry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

//idea was too make the whole control panel weve built be a craftable block instead so this whole marketscreen is teid too a block

public class MarketScreen extends Screen {

    private TextFieldWidget amountField;
    private TextFieldWidget itemField;
    private TextFieldWidget priceField;
    private TextFieldWidget hostField;
    private TextFieldWidget cancelField;
    private TextFieldWidget hostPortField;
    private TextFieldWidget marketNameField;
    private ButtonWidget hostButton;
    private ButtonWidget disconnectButton;
    private ButtonWidget stopHostButton;
    private ButtonWidget createButton;
    private ButtonWidget importButton;
    private ButtonWidget migrateButton;
    private ButtonWidget itemButton;
    private ButtonWidget exportButton;
    private ButtonWidget resetButton;
    private TextFieldWidget feeField;
    private ButtonWidget feeButton;
    private TextFieldWidget listingFeeField;
    private ButtonWidget listingFeeButton;
    private TextFieldWidget stipendField;
    private ButtonWidget stipendButton;
    private ButtonWidget claimStipendButton;
    private TextFieldWidget grantField;
    private ButtonWidget grantButton;
    private ButtonWidget addMarketButton;
    private ButtonWidget deleteMarketButton;

    /**
     * The status line.
     *
     * Set from the game thread by button handlers, and from the network thread by the
     * rejection callback registered in init() — hence volatile. Per-screen rather than
     * static: a status is a reply to something you just did on this screen, and one
     * left over from a previous screen would be answering a question nobody asked.
     * The callback that writes it is dropped in removed(), so a closed screen is not
     * kept alive by the holder still pointing at its handler.
     */
    private volatile String status = "";

    private static final int FIELD_HEIGHT = 20;
    private static final long MAX_QTY = 100_000L;

    private static final int COL_GAP = 12;
    private static final int ROW_STEP = 26;

    /**
     * The control column's internal spacing.
     *
     * One gap, and widths derived from the column rather than typed in per row, so
     * every row ends flush with every other. Hand-picked pixel widths were what left
     * the rows raggedly different lengths.
     */
    private static final int PAD = 6;

    // ─── layout, measured against the window rather than fixed ───
    //
    // A fixed box stopped the screen overflowing at high GUI scale, but at any normal
    // size it left three small columns adrift in the middle of a large empty screen.
    // These are computed per init() from the window, between a floor that keeps the
    // buttons legible and a ceiling that stops the panels sprawling.

    private static final int MIN_CONTENT_W = 380;
    private static final int MAX_CONTENT_W = 620;
    private static final int MIN_CONTENT_H = 150;
    private static final int MAX_CONTENT_H = 260;
    /** Below this the inventory column is not worth showing at all. */
    private static final int MIN_INV_W = 96;

    private int listW;
    private int controlsW;
    private int invW;
    private int contentW;
    private int contentH;
    private int halfW;
    /** A short field — a quantity, a price, a port. */
    private int halfWS;

    /**
     * Left edge of the inventory column, or -1 when there isn't room for it.
     *
     * The third column is dropped rather than squeezed when the window is too narrow —
     * at GUI scale 4 the whole screen is only 480 wide. Losing a panel that duplicates
     * what pressing E already tells you is a far better failure than a layout that runs
     * off the edge, which is what the old percentage-derived origins did.
     */
    private int invX = -1;

    // Row positions, set in init() so render and hit-tests can't drift.
    private int rowX;
    private int rowY;
    private int buttonsY;
    private int cancelY;

    /** Left column, where the lists live. */
    private int listX;

    // ─── frame bounds ───
    //
    // Everything used to be placed relative to the control rows, which is why content
    // hung out of the panels drawn around it — the panel and the thing inside it were
    // measured from different origins. These are the single source for both.

    /**
     * Top of the panels, pushed down by the alert band when there is one.
     *
     * The tab row measures from here too, so tabs and panels move together and the
     * alerts land in the strip the tabs vacate — space already known to be clear of
     * the header, which is what makes this safe on a short window. Putting the band
     * anywhere else above the tabs would have had about 10px of clearance at the
     * minimum layout and overlapped the credit lines.
     */
    private int frameTop() { return rowY - 6 + alertBandH(); }
    /** Shrinks by the same band, so a panel never runs off the bottom of the box. */
    private int frameH() { return contentH - 18 - alertBandH(); }
    /**
     * First usable row inside a panel, below the alert band.
     *
     * The alerts used to be drawn straight over whatever the panel had already put
     * here. That was deliberate — a damaged log outranks the first line of an order
     * book — but "outranks" was implemented as painting on top of it, so both were
     * unreadable rather than one. They get reserved space instead, and because every
     * panel's contents already measure from here, they all move down together.
     */
    private int panelTop() { return frameTop() + 5; }
    /** One past the last usable row inside a panel. */
    private int panelBottom() { return frameTop() + frameH() - 5; }

    // ─── tabs ───
    //
    // Three clusters used at wildly different rates — trading constantly, connecting
    // once a session, market lifecycle rarely and mostly destructively — were all
    // competing for one surface. Separating them is most of what made this screen
    // hard to read.
    //
    // Hand-rolled: 1.16.5 has no tab widget. Every widget is built once and shown or
    // hidden per tab rather than rebuilt, because ClickableWidget skips both rendering
    // and hit-testing when invisible, and rebuilding would clear what's typed.

    static final int SCREEN_HOME = 0;
    static final int SCREEN_TRADING = 1;
    static final int SCREEN_NETWORK = 2;
    static final int SCREEN_MARKET = 3;
    static final int SCREEN_SETTINGS = 4;
    private static final String[] SCREEN_NAMES =
            {"Home", "Trading", "Network", "Market", "Settings"};

    /** Static so the screen you were on survives closing and reopening. */
    private static int activeScreen = SCREEN_HOME;

    /** One widget list per destination, indexed by the SCREEN_ constants. */
    private final List<List<ClickableWidget>> screenWidgets = new ArrayList<>();

    private <T extends ClickableWidget> T onScreen(int screen, T widget) {
        screenWidgets.get(screen).add(widget);
        return this.addButton(widget);
    }

    private void selectScreen(int screen) {
        activeScreen = screen;
        applyScreenVisibility();
    }

    private void applyScreenVisibility() {
        for (int i = 0; i < screenWidgets.size(); i++) {
            setShown(screenWidgets.get(i), i == activeScreen);
        }
    }

    private static void setShown(List<ClickableWidget> widgets, boolean shown) {
        for (ClickableWidget w : widgets) {
            w.visible = shown;
            w.active = shown;
        }
    }

    // ─── tab bar geometry ───
    //
    // A hamburger menu is the one thing on this screen that exists nowhere in Minecraft —
    // it is a web idiom, and it announced the whole screen as a mod louder than anything
    // else did. Vanilla's own answer to "several views of one thing" is a row of tabs
    // along the top of the panel, which is what the advancements screen is, so that is
    // what this is now.
    //
    // Still hand-drawn and hand-hit-tested: 1.16.5 has no tab widget, and the creative
    // and advancement screens both draw their own from a texture. Attached to the panel
    // rather than to the window, so the tabs travel with the box when it is re-centred.

    private static final int TAB_H = 18;
    private static final int TAB_GAP = 2;

    private int[] tabRect(int index) {
        int span = contentW - TAB_GAP * (SCREEN_NAMES.length - 1);
        int w = span / SCREEN_NAMES.length;
        return new int[]{listX + index * (w + TAB_GAP), frameTop() - TAB_H - 2, w, TAB_H};
    }

    /**
     * Field contents come from persisted settings rather than statics now, so they
     * survive a restart instead of only a screen close. Falls back to the same
     * defaults when settings haven't loaded (the screen is unreachable before
     * SERVER_STARTED in practice, but null-tolerance costs nothing).
     */
    private static Settings settings() { return MarketStateHolder.settings(); }

    private static String savedHost() {
        Settings s = settings();
        return s == null ? "localhost:25555" : s.lastHostAddress();
    }

    private static String savedPort() {
        Settings s = settings();
        return s == null ? "25555" : String.valueOf(s.hostPort());
    }

    private static String savedItem() {
        Settings s = settings();
        return s == null ? "minecraft:iron_ingot" : s.lastItem();
    }

    private static String savedMarketName() {
        Settings s = settings();
        return s == null ? "" : s.lastMarketName();
    }


    public MarketScreen() {
        super(new LiteralText("Market"));
    }

    @Override
    protected void init() {
        super.init();

        MarketStateHolder.setOnRejected(reason -> status = "Rejected: " + reason);

        // Widgets are about to be rebuilt at their unshifted positions, so whatever the
        // band was before this counts for nothing.
        laidOutForBand = 0;

        screenWidgets.clear();
        for (int i = 0; i < SCREEN_NAMES.length; i++) {
            screenWidgets.add(new ArrayList<>());
        }

        // Sized from the window, between a floor that keeps the controls legible and a
        // ceiling that stops them sprawling. The old fixed box kept the screen from
        // overflowing at GUI scale 4, but at any ordinary size it left three small
        // columns adrift in the middle of a lot of nothing.
        this.contentW = Math.max(MIN_CONTENT_W,
                Math.min(MAX_CONTENT_W, this.width - 48));
        this.contentH = Math.max(MIN_CONTENT_H,
                Math.min(MAX_CONTENT_H, this.height - 96));

        // The controls take a fixed share; whatever is left is split between the two
        // panels, favouring the left one because it carries the book and the chart.
        int usable = contentW - COL_GAP * 2;
        this.controlsW = Math.max(200, Math.min(280, (int) (usable * 0.36)));
        int panels = usable - controlsW;
        this.listW = (int) (panels * 0.58);
        this.invW = panels - listW;

        // Only dropped when it is genuinely too narrow to read, rather than whenever
        // the window is not large — it disappearing at ordinary sizes was worse than
        // it being a little cramped.
        boolean showInventory = invW >= MIN_INV_W;
        if (!showInventory) {
            this.listW = panels;
            this.invW = 0;
        }

        this.halfW = (controlsW - PAD) / 2;
        // A quarter of the row rather than a fifth. The quantity and price boxes are
        // typed into constantly and were the narrowest things on the screen, while the
        // item button beside them only ever shows a name it can afford to trim.
        this.halfWS = Math.max(46, Math.min(76, (controlsW - PAD * 2) / 4));

        int boxX = Math.max(4, (this.width - contentW) / 2);
        // The floor was 34, which left exactly enough room for the header. The tab row
        // now lives between the header and the panel and needs its own 20 on top of that.
        int boxY = Math.max(46, Math.min((this.height - contentH) / 2,
                this.height - contentH - 30));

        this.listX = boxX;
        this.rowX = boxX + listW + COL_GAP;
        this.invX = showInventory ? rowX + controlsW + COL_GAP : -1;
        this.rowY = boxY + 24;
        this.buttonsY = rowY + ROW_STEP;
        this.cancelY = rowY + ROW_STEP * 2;

        // ─── TRADING ───
        this.amountField = new TextFieldWidget(this.textRenderer,
                rowX, rowY, halfWS, FIELD_HEIGHT, new LiteralText("Amount"));
        hint(this.amountField, "qty");
        onScreen(SCREEN_TRADING, this.amountField);

        // Built but deliberately NOT registered as a widget. It is now pure storage for
        // the selection — every handler still reads the item from here, and the slot
        // and picker write to it, so none of them had to change. Nothing types into it.
        this.itemField = new TextFieldWidget(this.textRenderer,
                rowX + halfWS + PAD, rowY, controlsW - (halfWS + PAD) * 2, FIELD_HEIGHT, new LiteralText("Item"));
        this.itemField.setMaxLength(64);
        this.itemField.setText(savedItem());

        this.itemButton = onScreen(SCREEN_TRADING,
                new ButtonWidget(rowX + halfWS + PAD, rowY, controlsW - (halfWS + PAD) * 2, FIELD_HEIGHT,
                        new LiteralText("Choose item..."), b -> openPicker()));

        this.priceField = new TextFieldWidget(this.textRenderer,
                rowX + controlsW - halfWS, rowY, halfWS, FIELD_HEIGHT, new LiteralText("Price"));
        hint(this.priceField, "price");
        onScreen(SCREEN_TRADING, this.priceField);

        onScreen(SCREEN_TRADING, new ButtonWidget(rowX, buttonsY, halfW, FIELD_HEIGHT,
                new LiteralText("Buy"), b -> onBuy()));
        onScreen(SCREEN_TRADING, new ButtonWidget(rowX + halfW + PAD, buttonsY, halfW, FIELD_HEIGHT,
                new LiteralText("Sell"), b -> onSell()));

        // Withdraw takes its own row. Sharing one with the cancel controls left three
        // things competing for a width that only comfortably fits two, and the order-id
        // box was the one that lost.
        onScreen(SCREEN_TRADING, new ButtonWidget(rowX, cancelY, controlsW, FIELD_HEIGHT,
                new LiteralText("Withdraw"), b -> onWithdraw()));

        int cancelRowY = cancelY + ROW_STEP;
        this.cancelField = new TextFieldWidget(this.textRenderer,
                rowX, cancelRowY, halfW, FIELD_HEIGHT, new LiteralText("Order ID"));
        hint(this.cancelField, "order id");
        onScreen(SCREEN_TRADING, this.cancelField);
        onScreen(SCREEN_TRADING, new ButtonWidget(rowX + halfW + PAD, cancelRowY, halfW,
                FIELD_HEIGHT, new LiteralText("Cancel"), b -> onCancel()));

        // ─── NETWORK ───
        this.hostField = new TextFieldWidget(this.textRenderer,
                rowX, rowY, controlsW - halfWS - PAD, FIELD_HEIGHT, new LiteralText("Host"));
        this.hostField.setMaxLength(64);
        this.hostField.setText(savedHost());
        onScreen(SCREEN_NETWORK, this.hostField);

        this.hostPortField = new TextFieldWidget(this.textRenderer,
                rowX + controlsW - halfWS, rowY, halfWS, FIELD_HEIGHT, new LiteralText("Port"));
        this.hostPortField.setText(savedPort());
        onScreen(SCREEN_NETWORK, this.hostPortField);

        onScreen(SCREEN_NETWORK, new ButtonWidget(rowX, rowY + ROW_STEP, halfW, FIELD_HEIGHT,
                new LiteralText("Connect"), b -> onConnect()));

        // Host serves the market this world already holds. With no market there is
        // nothing to serve, so the button is disabled rather than silently creating
        // one — that silent creation is what fragments a friend group into two
        // permanently incompatible economies.
        this.hostButton = new ButtonWidget(rowX + halfW + PAD, rowY + ROW_STEP, halfW, FIELD_HEIGHT,
                new LiteralText("Host"), b -> onHost());
        onScreen(SCREEN_NETWORK, this.hostButton);

        // Both were always live, side by side, whatever mode you were in — so while
        // hosting the obvious-looking one was Disconnect, which dropped the
        // self-connection and left the server running. Greyed by mode now, so the pair
        // says which of the two things you are actually doing.
        this.disconnectButton = new ButtonWidget(rowX, rowY + ROW_STEP * 2, halfW,
                FIELD_HEIGHT, new LiteralText("Disconnect"), b -> onDisconnect());
        onScreen(SCREEN_NETWORK, this.disconnectButton);

        this.stopHostButton = new ButtonWidget(rowX + halfW + PAD, rowY + ROW_STEP * 2, halfW,
                FIELD_HEIGHT, new LiteralText("Stop hosting"), b -> onStopHosting());
        onScreen(SCREEN_NETWORK, this.stopHostButton);

        onScreen(SCREEN_NETWORK, new ButtonWidget(rowX, rowY + ROW_STEP * 3, halfW, FIELD_HEIGHT,
                new LiteralText("Refresh hosts"), b -> startPoll()));

        // ─── MARKET ───
        this.marketNameField = new TextFieldWidget(this.textRenderer,
                rowX, rowY, controlsW, FIELD_HEIGHT, new LiteralText("Market name"));
        this.marketNameField.setMaxLength(32);
        this.marketNameField.setText(savedMarketName());
        hint(this.marketNameField, "new market name");
        onScreen(SCREEN_MARKET, this.marketNameField);

        // Positions are assigned per frame by refreshMarketActions, since which of
        // these apply depends on the situation and gaps where a hidden button used to
        // be would read as something missing.
        this.createButton = onScreen(SCREEN_MARKET,
                new ButtonWidget(rowX, rowY, controlsW, FIELD_HEIGHT,
                        new LiteralText("Create a new market"), b -> onCreateMarket()));

        // Sharing a market by file is how someone joins who was never online at the
        // same time as anyone holding it.
        this.importButton = onScreen(SCREEN_MARKET,
                new ButtonWidget(rowX, rowY, controlsW, FIELD_HEIGHT,
                        new LiteralText("Import one from a file"), b -> onImport()));

        this.exportButton = onScreen(SCREEN_MARKET,
                new ButtonWidget(rowX, rowY, controlsW, FIELD_HEIGHT,
                        new LiteralText("Export to a file"), b -> onExport()));

        this.migrateButton = onScreen(SCREEN_MARKET,
                new ButtonWidget(rowX, rowY, controlsW, FIELD_HEIGHT,
                        new LiteralText("Migrate my position"), b -> onMigrate()));

        this.resetButton = onScreen(SCREEN_MARKET,
                new ButtonWidget(rowX, rowY, controlsW, FIELD_HEIGHT,
                        new LiteralText("Discard and start over"), b -> onReset()));

        // Only the market's creator can author policy, so these appear for exactly one
        // person per market — see refreshMarketActions.
        this.feeField = new TextFieldWidget(this.textRenderer,
                rowX, rowY, halfW, FIELD_HEIGHT, new LiteralText("Trading fee"));
        this.feeField.setMaxLength(6);
        hint(this.feeField, "e.g. 2.5");
        onScreen(SCREEN_MARKET, this.feeField);

        this.feeButton = onScreen(SCREEN_MARKET,
                new ButtonWidget(rowX + halfW + PAD, rowY, halfW, FIELD_HEIGHT,
                        new LiteralText("Trading fee"), b -> onSetFee()));

        this.listingFeeField = new TextFieldWidget(this.textRenderer,
                rowX, rowY, halfW, FIELD_HEIGHT, new LiteralText("Listing fee"));
        // Long enough for "1000/1000" — the field takes a fee, optionally followed by
        // the allowance that goes with it.
        this.listingFeeField.setMaxLength(9);
        hint(this.listingFeeField, "2 or 2/3");
        onScreen(SCREEN_MARKET, this.listingFeeField);

        this.listingFeeButton = onScreen(SCREEN_MARKET,
                new ButtonWidget(rowX + halfW + PAD, rowY, halfW, FIELD_HEIGHT,
                        new LiteralText("Listing fee"), b -> onSetListingFee()));

        this.stipendField = new TextFieldWidget(this.textRenderer,
                rowX, rowY, halfW, FIELD_HEIGHT, new LiteralText("Stipend"));
        this.stipendField.setMaxLength(6);
        hint(this.stipendField, "credits");
        onScreen(SCREEN_MARKET, this.stipendField);

        this.stipendButton = onScreen(SCREEN_MARKET,
                new ButtonWidget(rowX + halfW + PAD, rowY, halfW, FIELD_HEIGHT,
                        new LiteralText("Set stipend"), b -> onSetStipend()));

        this.grantField = new TextFieldWidget(this.textRenderer,
                rowX, rowY, halfW, FIELD_HEIGHT, new LiteralText("Welcome grant"));
        this.grantField.setMaxLength(7);
        hint(this.grantField, "credits");
        onScreen(SCREEN_MARKET, this.grantField);

        this.grantButton = onScreen(SCREEN_MARKET,
                new ButtonWidget(rowX + halfW + PAD, rowY, halfW, FIELD_HEIGHT,
                        new LiteralText("Welcome grant"), b -> onSetGrant()));

        this.claimStipendButton = onScreen(SCREEN_MARKET,
                new ButtonWidget(rowX, rowY, controlsW, FIELD_HEIGHT,
                        new LiteralText("Claim stipend"), b -> onClaimStipend()));

        this.addMarketButton = onScreen(SCREEN_MARKET,
                new ButtonWidget(rowX, rowY, controlsW, FIELD_HEIGHT,
                        new LiteralText("Add another market"), b -> onAddMarket()));

        this.deleteMarketButton = onScreen(SCREEN_MARKET,
                new ButtonWidget(rowX, rowY, controlsW, FIELD_HEIGHT,
                        new LiteralText("Remove this market"), b -> onDeleteMarket()));

        // ─── SETTINGS ───
        // Every control writes straight through to the persisted settings, which have
        // been saved since before there was anywhere to see them.
        Settings prefs = settings();

        onScreen(SCREEN_SETTINGS, new Toggle(rowX, rowY, controlsW, FIELD_HEIGHT,
                "Fill notices in chat", prefs != null && prefs.notifyChat(),
                on -> { if (settings() != null) settings().setNotifyChat(on); }));

        onScreen(SCREEN_SETTINGS, new Toggle(rowX, rowY + ROW_STEP, controlsW, FIELD_HEIGHT,
                "Fill notices above hotbar", prefs != null && prefs.notifyActionBar(),
                on -> { if (settings() != null) settings().setNotifyActionBar(on); }));

        onScreen(SCREEN_SETTINGS, new IntSlider(rowX, rowY + ROW_STEP * 2, controlsW,
                FIELD_HEIGHT, "Notice limit", 0, 60,
                prefs == null ? 20 : prefs.notifyMaxPerMinute(),
                v -> { if (settings() != null) settings().setNotifyMaxPerMinute(v); }));

        applyScreenVisibility();
        startPoll();
    }

    /** Trading requires a host — local writes would fork you from the shared market. */
    private boolean requireConnected() {
        // Tested on the live socket, not on mode: being in CONNECTED mode with a dead
        // client is exactly the case that used to let a trade through to fail later.
        if (MarketStateHolder.isConnected()) return true;

        status = MarketStateHolder.mode() == MarketStateHolder.Mode.CONNECTED
                ? "Connection to the host was lost — reconnect to trade"
                : "Market is closed — connect to a host or start hosting to trade";
        return false;
    }

    /**
     * The reset confirmation, written as three lists rather than a paragraph.
     *
     * It used to be one block of prose that opened with what you lose and buried the
     * recovery in a subordinate clause — "if you are rejoining a market you diverged
     * from" — which is a condition the reader cannot evaluate about themselves. Reported
     * from play as reading like everything was gone, on a screen that was about to hand
     * 61 items back.
     *
     * Three changes. The categories are separated, because "comes back by itself",
     * "comes back if you do something" and "is gone" are three different decisions and
     * were three clauses of one sentence. The numbers are real — resetCost has already
     * worked out exactly which items and how many orders, and naming them removes the
     * doubt that a paragraph about what generally happens cannot. And what is
     * permanently lost is finally stated: credits earned since the split and the trades
     * themselves have no existence outside this branch's ledger, which the old wording
     * never said at all while listing three kinds of recovery.
     *
     * The no-fork case says none of it. Without a host holding the shared history there
     * is nothing to rejoin and every sentence about recovery would be false.
     */
    private void onReset() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        UUID me = MinecraftIds.userIdOf(mc.player);

        MarketStateHolder.ResetCost cost = MarketStateHolder.resetCost();
        StringBuilder body = new StringBuilder();

        if (cost.rejoinable()) {
            body.append("You parted from the other copy after event ").append(cost.splitAt)
                    .append(cost.oursSince > 0
                            ? ". The " + cost.oursSince + " events since are yours alone."
                            : ".");
            body.append("\n\nComes back when you reconnect: everything up to event ")
                    .append(cost.splitAt)
                    .append(": your credits, holdings and trades are in their copy too.");
        } else {
            // No fork, or one whose split point could not be found. Either way there is
            // no known shared history, so nothing is promised.
            body.append("You would lose ").append(MarketStateHolder.describeLoss(me))
                    .append(". Nothing here is held anywhere else unless somebody is"
                            + " still hosting this market.");
        }

        if (!cost.refunds.isEmpty()) {
            body.append("\n\nHanded to your inventory: ").append(describeRefunds(cost))
                    .append(". They left your inventory after the split, and this is the"
                            + " only record that they exist.");
        }

        if (!cost.orders.isEmpty()) {
            body.append("\n\nListed afterwards to put back by hand: ")
                    .append(cost.orders.size())
                    .append(cost.orders.size() == 1 ? " order." : " orders.");
        }

        if (cost.rejoinable()) {
            // The one category with no remedy, and the one the old wording omitted.
            body.append("\n\nGone for good: credits you earned since the split, and the"
                    + " trades themselves. Those exist only in this branch's ledger.");
        }

        body.append("\n\nThis cannot be undone.");

        showDanger("Discard this world's market?", body.toString(),
                "Discard", () -> {
                    MarketStateHolder.resetLog();
                    // Only claims the list exists when it does — a reset with no fork
                    // has nothing to offer back and should not imply otherwise.
                    status = MarketStateHolder.pendingReplace().isEmpty()
                            ? "Local history discarded"
                            : "Local history discarded — orders from after the split are"
                                    + " listed to re-place";
                });
    }

    /**
     * The returned items, named the way a player would recognise them.
     *
     * Capped at three kinds, because this goes into an overlay that grows a line at a
     * time and has no scroll — the same unbounded stacking that hid the market switcher
     * and ran the dedicated-server warning off the panel. A count for the rest keeps the
     * total honest without letting the box grow with somebody's inventory.
     */
    private static String describeRefunds(MarketStateHolder.ResetCost cost) {
        StringBuilder s = new StringBuilder();
        int shown = 0;
        for (MarketStateHolder.Refund r : cost.refunds) {
            if (shown == 3) {
                s.append(" and ").append(cost.refunds.size() - 3).append(" more");
                break;
            }
            if (shown > 0) s.append(", ");
            Item item = MinecraftIds.idToItem(r.itemId);
            s.append(r.quantity).append(" ")
                    .append(item == Items.AIR ? r.itemId : item.getName().getString());
            shown++;
        }
        return s.toString();
    }

    // ─────────── connection ───────────

    private static long lastConnectAttempt = 0;

    /** A parsed host address, or a reason it couldn't be. */
    private static final class Address {
        final String host;
        final int port;
        final String error;

        private Address(String host, int port, String error) {
            this.host = host; this.port = port; this.error = error;
        }
    }

    /**
     * Splits "host", "host:port", "[ipv6]", "[ipv6]:port" or a bare IPv6 address.
     *
     * Shared by Connect and Migrate. Migrate used to do its own naive split on the last
     * colon, which silently mangled every IPv6 address — the two must agree, since they
     * read the same field and the user cannot see which one is parsing it.
     */
    private static Address parseAddress(String text) {
        String host;
        int port = 25555;
        try {
            if (text.startsWith("[")) {
                // IPv6 in brackets: [2001:db8::1] or [2001:db8::1]:25555
                int close = text.indexOf(']');
                if (close < 0) return new Address(null, 0, "Bad address — missing closing bracket");
                host = text.substring(1, close);
                String rest = text.substring(close + 1);
                if (rest.startsWith(":")) port = Integer.parseInt(rest.substring(1));
            } else if (text.indexOf(':') != text.lastIndexOf(':')) {
                // More than one colon and no brackets — a bare IPv6 address.
                host = text;
            } else {
                int colon = text.lastIndexOf(':');
                if (colon < 0) {
                    host = text;
                } else {
                    host = text.substring(0, colon);
                    port = Integer.parseInt(text.substring(colon + 1));
                }
            }
        } catch (NumberFormatException e) {
            return new Address(null, 0, "Bad port — use host:port or [ipv6]:port");
        }
        return new Address(host, port, null);
    }

    private void onConnect() {
        long now = System.currentTimeMillis();
        if (now - lastConnectAttempt < 3000) {
            status = "Wait a moment before retrying";
            return;
        }
        lastConnectAttempt = now;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        Address parsed = parseAddress(hostField.getText().trim());
        if (parsed.error != null) {
            status = parsed.error;
            return;
        }
        final String host = parsed.host;
        final int port = parsed.port;

        // in onConnect, before spawning the thread
        try {
            MarketStateHolder.setMyHostPort(Integer.parseInt(hostPortField.getText().trim()));
        } catch (NumberFormatException ignored) { }

        final String finalHost = host;
        final int finalPort = port;

        status = "Connecting to " + finalHost + ":" + finalPort + "...";
        UUID me = MinecraftIds.userIdOf(mc.player);
        String myName = mc.getSession().getUsername();

        new Thread(() -> {
            MarketStateHolder.connect(finalHost, finalPort, me, myName);
            // Only report success here. Failures already went through onRejected with
            // the actual reason and what to do about it — overwriting that with a
            // generic "Connect failed" throws away the only useful part.
            if (MarketStateHolder.isConnected()) {
                status = "Connected to " + finalHost + ":" + finalPort;
            }
        }, "market-connect").start();
    }

    private void onDisconnect() {
        MarketStateHolder.disconnect();
        status = "Disconnected — using local market";
    }

    /**
     * Creates a new market. Confirmed, because the cost of doing this by mistake is not
     * recoverable: a market created here shares no history with any market your friends
     * are already using, and the two can never be merged.
     */
    private void onCreateMarket() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getServer() == null) return;

        final String name = marketNameField.getText().trim();
        if (name.isEmpty()) {
            status = "Give the market a name first";
            return;
        }

        final Path worldDir = MarketStateHolder.worldDirOrNull();
        if (worldDir == null) { status = NO_WORLD; return; }
        final UUID me = MinecraftIds.userIdOf(mc.player);

        showConfirm("Create '" + name + "'?",
                "This starts a separate economy. Anyone who joins will not see trades"
                        + " from a market your friends already use, and the two can never"
                        + " be merged. To join an existing one, use Connect.",
                "Create", () -> {
                    Settings s = settings();
                    if (s != null) s.setLastMarketName(name);
                    if (MarketStateHolder.createMarket(worldDir, me, name)) {
                        status = "Created '" + name + "' — click Host to start serving it";
                    }
                });
    }

    /**
     * Host and Create are mutually exclusive: you either hold a market or you don't.
     *
     * Every branch is also gated on visible, because this runs each frame and would
     * otherwise re-enable buttons belonging to a tab that isn't showing — which would
     * make them clickable again the moment the tab's own visibility rule was applied
     * before this one.
     */
    private void refreshMarketButtons() {
        // The picker button says what is currently selected, so the selection is legible
        // without reading it back off the icon strip.
        if (itemButton != null) {
            Item selected = MinecraftIds.itemFromName(itemField.getText().trim());
            // Trimmed to the button's own width rather than a fixed guess, since that
            // width now varies with the window.
            itemButton.setMessage(new LiteralText(selected == Items.AIR
                    ? "Choose item..."
                    : this.textRenderer.trimToWidth(
                            selected.getName().getString(), itemButton.getWidth() - 8)));
        }

        boolean has = MarketStateHolder.hasMarket();
        boolean hosting = MarketStateHolder.isHosting();
        // Greyed rather than hidden when a dedicated server has this market, because
        // every player could plausibly host and a dead control here teaches why they
        // should not — which is what a comment beside the Migrate button has claimed
        // this does since before it did any of it.
        boolean servedByBox = dedicatedServesThisMarket();
        // And greyed when this machine has the market but not its history. A snapshot-only
        // replica of a dedicated market would bind a port, advertise the market, and have
        // no lines to send anybody who joined. servedByBox does not cover it: that check
        // is live-only, so it goes false the moment the server stops being discovered,
        // which is precisely when somebody would reach for this button.
        boolean canServeIt = MarketStateHolder.hasFullHistory();
        if (hostButton != null) {
            hostButton.active = hostButton.visible && has && !hosting && !servedByBox
                    && canServeIt;
        }

        // Only one of these can ever be the right thing to press: connect() stops
        // hosting before it dials out, so the two modes are exclusive. Leaving both live
        // meant somebody hosting reached for Disconnect — the left one, and the word
        // people use — and stopped only their own self-connection.
        if (disconnectButton != null) {
            // On the mode, not on the live socket. Being in CONNECTED with a dead client
            // is a state this code already knows about — requireConnected has a message
            // for exactly it — and it is precisely when somebody wants to press
            // Disconnect. Greying it on isConnected() left them in a market they could
            // not trade in and could not leave.
            disconnectButton.active = disconnectButton.visible && !hosting
                    && MarketStateHolder.mode() != MarketStateHolder.Mode.LOCAL;
        }
        if (stopHostButton != null) {
            stopHostButton.active = stopHostButton.visible && hosting;
        }

        // Create and Import are governed by the Market screen's situation now, which
        // already only offers them where there is no market to displace.
        refreshMarketActions();
    }

    private void onExport() {
        new Thread(() -> {
            try {
                status = "Exported to " + MarketStateHolder.exportMarket();
            } catch (IOException e) {
                status = "Export failed: " + e.getMessage();
            }
        }, "market-export").start();
    }

    /**
     * Confirmed, like Create — importing replaces what this world holds, and the
     * verification pass reads and checks every event, so it isn't instant.
     */
    private void onImport() {
        showConfirm("Import a market from file?",
                "This world will adopt the market in your economiesmod-imports folder"
                        + " as its own. Every event is verified before anything is"
                        + " written, so a tampered file is refused rather than trusted.",
                "Import", this::startImport);
    }

    private void startImport() {
        status = "Verifying archive...";
        new Thread(() -> {
            try {
                status = "Imported " + MarketStateHolder.importMarket();
            } catch (MarketArchive.InvalidArchive e) {
                status = "Archive rejected: " + e.getMessage();
            } catch (IOException e) {
                status = "Import failed: " + e.getMessage();
            }
        }, "market-import").start();
    }

    private static long lastHostAttempt = 0;

    private void onHost() {
        // Behind the market — Raft's election restriction, with the high-water mark
        // standing in for a quorum. Serving a log that is short of where the market has
        // reached refuses everyone who is current, and forks it outright the moment
        // this host trades. Checked before the collision warning because it holds
        // whether or not anyone else is hosting right now, which is the case that
        // actually bites: you come back after a week and nobody is around to tell you.
        long behind = MarketStateHolder.eventsBehind();
        if (behind > 0) {
            showDanger("You are " + behind + " events behind",
                    "Hosting now would refuse everyone who is up to date, and split the"
                            + " market the moment you trade. Connect to someone serving it"
                            + " first and you will catch up automatically.",
                    "Host anyway", this::startHosting);
            return;
        }

        // Warn only about a genuine collision — someone else already serving the same
        // market. Another host on a different market is not our problem, and warning
        // about it trains people to click through the warning that matters.
        // Only if we actually know what's out there: a warning derived from a minute-old
        // poll names a host that may have stopped since, which teaches people to click
        // through without reading.
        if (pollIsFresh()) {
            MinecraftClient mcCheck = MinecraftClient.getInstance();
            String myUuid = mcCheck.player != null
                    ? MinecraftIds.userIdOf(mcCheck.player).toString() : null;
            MarketState mine = MarketStateHolder.get();
            String myMarket = mine != null && mine.marketId() != null
                    ? mine.marketId().toString() : null;

            for (PeerPoll.HostInfo h : discovered) {
                boolean isOther = h.reply.userId != null && !h.reply.userId.equals(myUuid);
                boolean sameMarket = myMarket != null && myMarket.equals(h.reply.marketId);

                // A dedicated server is not a host you take over from. "Take over" is a
                // reasonable offer against somebody's game — they may be about to log
                // off, and one of you should be serving it. Against a box that is always
                // up it is an offer to fork the market permanently: it will not stop, so
                // both of you keep sequencing, and two branches of one market cannot be
                // merged afterwards. No confirm button here, because there is no version
                // of this that ends well.
                if (isOther && sameMarket && h.reply.dedicated) {
                    status = h.reply.hostName + " is a dedicated server and is always"
                            + " serving this market — connect to it instead";
                    showDanger("You cannot host this market",
                            h.reply.hostName + " is a dedicated server serving this"
                                    + " market right now (" + h.reply.lastSeq + " events),"
                                    + " and it does not stop. Two hosts on one market split"
                                    + " it into two histories that can never be merged,"
                                    + " and you would lose everything you traded on the"
                                    + " losing side. Connect to it from the Network tab"
                                    + " instead.",
                            null, null);
                    return;
                }

                if (isOther && sameMarket) {
                    showDanger(h.reply.hostName + " is already hosting this",
                            h.reply.hostName + " is serving this market right now ("
                                    + h.reply.lastSeq + " events). Two hosts at once will"
                                    + " split it into two economies that cannot be"
                                    + " merged. Connect to them instead.",
                            "Take over", this::startHosting);
                    return;
                }
            }
        }

        startHosting();
    }

    private void startHosting() {
        long now = System.currentTimeMillis();
        if (now - lastHostAttempt < 3000) {
            status = "Wait a moment before retrying";
            return;
        }
        lastHostAttempt = now;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getServer() == null) return;

        int hostPort;
        try {
            hostPort = Integer.parseInt(hostPortField.getText().trim());
        } catch (NumberFormatException e) {
            status = "Port must be a number";
            return;
        }
        if (hostPort < 1024 || hostPort > 65535) {
            status = "Port must be between 1024 and 65535";
            return;
        }

        Path worldDir = MarketStateHolder.worldDirOrNull();
        if (worldDir == null) { status = NO_WORLD; return; }
        UUID me = MinecraftIds.userIdOf(mc.player);
        String playerName = mc.getSession().getUsername();

        final String starting = "Starting host...";
        status = starting;
        new Thread(() -> {
            MarketStateHolder.startHosting(worldDir, hostPort, me, playerName);
            if (MarketStateHolder.mode() == MarketStateHolder.Mode.HOSTING) {
                status = "Hosting on port " + hostPort;
            } else if (starting.equals(status)) {
                // Only when nothing better arrived. Every refusal inside startHosting
                // reports itself through onRejected, which writes this same field — and
                // this line used to overwrite it unconditionally, one statement later, so
                // "your log is damaged" and "this copy has no history" both reached the
                // player as "Failed to start host". The reasons were written, and then
                // thrown away by the code that ran next.
                //
                // onConnect above already says this in its own words, and has since the
                // day somebody noticed it there. Two threads doing the same job, one
                // corrected and one not, is the defect this project keeps finding: the
                // fix went where the bug was seen instead of everywhere it lived.
                //
                // The generic message still has a job here, which is why this is a
                // fallback rather than a deletion: a bind failure happens inside the host
                // thread after this returns and refuses nothing through onRejected.
                status = "Failed to start host";
            }
        }, "market-host-start").start();
    }

    /**
     * Hands this world's market to the host in the address field, which credits what we
     * hold there. Two-click, because it ends with discarding this market.
     */
    private void onMigrate() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        MarketState mine = MarketStateHolder.get();
        if (mine == null || mine.marketId() == null) {
            status = "You hold no market to migrate — use Connect";
            return;
        }

        UUID me = MinecraftIds.userIdOf(mc.player);

        Address parsed = parseAddress(hostField.getText().trim());
        if (parsed.error != null) {
            status = parsed.error;
            return;
        }
        final String host = parsed.host;
        final int port = parsed.port;

        Path worldDir = MarketStateHolder.worldDirOrNull();
        if (worldDir == null) {
            // Was a bare return. Correct and silent, which is the pair this project keeps
            // paying for: a button that does nothing teaches nothing, and once Create and
            // Host started explaining themselves this was the only one left that did not.
            status = NO_WORLD;
            return;
        }
        String myName = mc.getSession().getUsername();

        showDanger("Migrate to " + host + ":" + port + "?",
                "Your position in '" + mine.marketName() + "' ("
                        + MarketStateHolder.describeLoss(me) + ") is verified by that"
                        + " host and credited to you there. This market is then"
                        + " discarded. Only use this for a market with no history in"
                        + " common with theirs; if you have diverged from the same"
                        + " market, Reset is what you want instead.",
                "Migrate", () -> startMigration(host, port, me, myName));
    }

    private void startMigration(String host, int port, UUID me, String myName) {
        status = "Verifying your market with " + host + "...";
        new Thread(() -> {
            if (!MarketStateHolder.migrateTo(host, port, me)) return;   // reports its own reason

            // Only now is it safe to discard: the destination has recorded the claim.
            MarketStateHolder.resetLog();
            MarketStateHolder.connect(host, port, me, myName);
            status = MarketStateHolder.isConnected()
                    ? "Migrated — your balance is here. Old orders listed below to re-place."
                    : "Migrated, but could not connect — press Connect";
        }, "market-migrate").start();
    }

    private void onStopHosting() {
        MarketStateHolder.stopHosting();
        status = "Stopped hosting";
    }

    // ─────────── discovery ───────────

    private static volatile List<PeerPoll.HostInfo> discovered = Collections.emptyList();
    private static volatile boolean polling = false;
    private static final int DISCOVERY_ROW_HEIGHT = 12;

    /** When the last poll finished. A host list with no age on it is a list of claims
     *  about the past being read as claims about the present. */
    private static volatile long lastPollAt = 0;
    private static final long POLL_INTERVAL_MS = 10_000;
    /** Past this, the list is too old to base a decision on. */
    private static final long POLL_STALE_MS = 20_000;

    private static boolean pollIsFresh() {
        return lastPollAt != 0 && System.currentTimeMillis() - lastPollAt < POLL_STALE_MS;
    }

    private void startPoll() {
        if (polling) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        PeerCache cache = MarketStateHolder.peers();
        if (cache == null) {
            discovered = Collections.emptyList();
            return;
        }

        polling = true;
        String me = MinecraftIds.userIdOf(mc.player).toString();

        new Thread(() -> {
            try {
                List<PeerCache.Peer> others = new ArrayList<>();
                for (PeerCache.Peer p : cache.all()) {
                    if (!me.equals(p.userId)) others.add(p);
                }
                discovered = PeerPoll.findHosts(others, cache, 2000);

                // Note how far the market has been seen to reach. This is the only
                // moment we learn it, and it has to outlive the poll — by the time
                // someone hosts while behind, the peer that knew better is usually
                // offline.
                for (PeerPoll.HostInfo h : discovered) {
                    if (h.reply.marketId == null) continue;
                    try {
                        // Also compares their head against our chain — the poll already
                        // carries a signed (seq, hash), so a split is detectable here
                        // without anyone attempting a connection first.
                        // The address rides along because the comparison is no longer
                        // always local: a peer claiming a head above ours has to be
                        // asked for their hash at ours, and the probe reply cannot
                        // carry a point it was not asked about.
                        MarketStateHolder.observeHostHead(
                                UUID.fromString(h.reply.marketId), h.reply.lastSeq,
                                h.reply.lastHash, h.reply.userId, h.reply.hostName,
                                h.peer.address, h.peer.port);
                    } catch (IllegalArgumentException ignored) {
                        // malformed marketId from a peer — not ours to fix
                    }
                }
            } finally {
                lastPollAt = System.currentTimeMillis();
                polling = false;
            }
        }, "market-discovery").start();
    }

    /**
     * Top of the list area under the controls.
     *
     * Below the tallest tab's fourth row, so it is clear of every tab's controls —
     * they all share the same rows, so one clearance works for all of them.
     */
    /**
     * The header row of a list in the left panel; its entries start one row below.
     *
     * Both the host list and the re-place list measure from here, and both their hit
     * tests do too. When this meant "header" to one of them and "first entry" to the
     * other, every click on a host landed one row low and the last one was unreachable.
     */
    /**
     * Top of host row {@code index}. The single source for drawing one and for hitting it.
     *
     * There were two of these — the render walked a running cursor down from the "Hosts:"
     * heading, and the click test recomputed the same walk from `discoveryStartY()`. They
     * agreed only because nothing had ever been inserted between the heading and the
     * first row. Adding one line of explanation above the list moved every drawn row down
     * and left every clickable row where it was, which is this file's oldest bug and the
     * one its own comments keep warning about.
     *
     * Anything that appears above the rows belongs in here, so both callers move together.
     */
    private int discoveryRowY(int index) {
        int y = discoveryStartY() + DISCOVERY_ROW_HEIGHT + 2;   // below "Hosts:"
        if (dedicatedServesThisMarket()) y += DISCOVERY_ROW_HEIGHT + 2;
        return y + index * DISCOVERY_ROW_HEIGHT;
    }

    private int discoveryStartY() {
        return panelTop();
    }

    // ─────────── home ───────────

    /**
     * The dashboard.
     *
     * Hosts is live because discovery already polls for it; the other three are drawn
     * as empty panels rather than left out, so the layout is real and adding a panel
     * later is filling one in rather than finding room for it.
     */
    /**
     * Three widget slots around one fixture.
     *
     * Hosts is permanent and central because it is the only thing here that is always
     * worth knowing — who is serving, and whether anyone is. The three slots either
     * side are deliberately empty for now; what belongs in them is a question best
     * answered by using the thing, not by guessing up front.
     */
    private void renderHome(MatrixStack m) {
        int gap = COL_GAP;
        // Same frame line the other screens use, so switching between them does not
        // shift everything by a few pixels — via the helpers rather than repeating
        // their arithmetic, which is what left Home's titles under the alert band.
        int top = frameTop();
        int height = frameH();
        int total = contentW;

        int sideW = (total - gap * 2) / 3;
        int midW = total - gap * 2 - sideW * 2;
        int midX = listX + sideW + gap;
        int rightX = midX + midW + gap;

        panel(m, listX, top, sideW, height, "Most traded");
        renderMostTraded(m, listX + 8, top + 18, sideW - 16, height - 24);

        int hostsH = height / 2;
        panel(m, midX, top, midW, hostsH, "Hosts");
        renderHostsPanel(m, midX + 8, top + 18, midW - 16, hostsH - 24);

        int priceY = top + hostsH + gap;
        int priceH = height - hostsH - gap;
        panel(m, midX, priceY, midW, priceH, "Price");
        renderHomePrice(m, midX + 8, priceY + 18, midW - 16, priceH - 24);

        panel(m, rightX, top, sideW, height, "Activity");
        renderActivity(m, rightX + 8, top + 18, sideW - 16, height - 24);
    }

    /** An item that has traded, with how much of it and what it last went for. */
    private static final class TradedItem {
        final String itemId;
        final long volume;
        final long lastPrice;

        TradedItem(String itemId, long volume, long lastPrice) {
            this.itemId = itemId;
            this.volume = volume;
            this.lastPrice = lastPrice;
        }
    }

    /**
     * What the market is busiest with, by units traded.
     *
     * Ranked by volume rather than by number of trades: one player moving a stack is
     * more of a market than six people swapping single items, and volume is the figure
     * a price means anything next to.
     */
    private List<TradedItem> mostTraded(MarketState market, int limit) {
        if (market == null) return Collections.emptyList();
        TradeHistory trades = market.trades();
        List<TradedItem> out = new ArrayList<>();
        for (String id : trades.tradedItems()) {
            out.add(new TradedItem(id, trades.volumeFor(id), trades.lastPrice(id)));
        }
        out.sort((a, b) -> Long.compare(b.volume, a.volume));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private void renderMostTraded(MatrixStack m, int x, int y, int w, int h) {
        MarketState market = MarketStateHolder.get();
        List<TradedItem> top = mostTraded(market, Math.max(1, h / INV_ROW_H));

        if (top.isEmpty()) {
            label(m, "nothing has traded yet", x, y, 0x808080);
            return;
        }

        int row = y;
        for (TradedItem t : top) {
            if (row + INV_ROW_H > y + h) break;
            Item item = MinecraftIds.idToItem(t.itemId);
            drawItemCell(m, new ItemStack(item), x, row, null, false);

            String name = item == Items.AIR ? t.itemId : item.getName().getString();
            label(m, trim(name, w - 66), x + 22, row + 1, 0xFFFFFF);
            label(m, t.volume + " traded", x + 22, row + 11, 0x808080);

            if (t.lastPrice >= 0) {
                String price = String.valueOf(t.lastPrice);
                label(m, price, x + w - this.textRenderer.getWidth(price), row + 6, 0xFFFF88);
            }
            row += INV_ROW_H;
        }
    }

    /**
     * The chart for whatever item is selected, falling back to the busiest one.
     *
     * The fallback matters more than it looks: on Home, nothing has been picked yet the
     * first time anyone arrives, and an empty panel on the landing screen says the
     * feature is broken rather than that the player has not chosen anything.
     */
    private void renderHomePrice(MatrixStack m, int x, int y, int w, int h) {
        MarketState market = MarketStateHolder.get();
        if (market == null) {
            label(m, "no market", x, y, 0x808080);
            return;
        }

        Item chosen = MinecraftIds.itemFromName(itemField.getText().trim());
        String itemId = chosen != Items.AIR ? MinecraftIds.itemToId(chosen) : null;
        boolean fellBack = false;
        if (itemId == null || market.trades().countFor(itemId) < 2) {
            List<TradedItem> busiest = mostTraded(market, 1);
            if (!busiest.isEmpty()) {
                itemId = busiest.get(0).itemId;
                fellBack = chosen != Items.AIR;
            }
        }

        if (itemId == null) {
            label(m, "nothing has traded yet", x, y, 0x808080);
            return;
        }

        List<Trade> recent = market.trades().recentFor(itemId, 60);
        Item item = MinecraftIds.idToItem(itemId);
        String name = item == Items.AIR ? itemId : item.getName().getString();
        label(m, trim(name, w) + (fellBack ? " (busiest)" : ""), x, y, 0x88CCFF);

        int chartY = y + 12;
        int chartH = h - 24;
        if (recent.size() < 2 || chartH < 8) {
            label(m, "not enough trades yet", x, chartY, 0x808080);
            return;
        }

        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (Trade t : recent) {
            min = Math.min(min, t.price);
            max = Math.max(max, t.price);
        }
        long span = Math.max(1, max - min);

        // Capped like the Trading view's chart, or three trades draw three bars the
        // width of the panel and it reads as a rendering fault rather than sparse data.
        int barW = Math.max(2, Math.min(12, w / recent.size()));
        fill(m, x, chartY, x + w, chartY + chartH, 0x40000000);
        for (int i = 0; i < recent.size(); i++) {
            int barH = (int) ((recent.get(i).price - min) * (chartH - 2) / span) + 1;
            int bx = x + i * barW;
            if (bx + barW > x + w) break;
            fill(m, bx, chartY + chartH - barH, bx + barW - 1, chartY + chartH, 0xFF55FFFF);
        }

        label(m, "low " + min + "  high " + max + "  last "
                + recent.get(recent.size() - 1).price, x, chartY + chartH + 2, 0x909090);
    }

    /** The tail of the log in words. Newest first — the top of a feed is where you look. */
    private void renderActivity(MatrixStack m, int x, int y, int w, int h) {
        List<SequencedEvent> recent = MarketStateHolder.recentActivity();
        if (recent.isEmpty()) {
            label(m, "nothing yet", x, y, 0x808080);
            return;
        }

        int row = y;
        for (int i = recent.size() - 1; i >= 0; i--) {
            if (row + 10 > y + h) break;
            label(m, trim(describeEvent(recent.get(i)), w), x, row, 0x9090A0);
            row += 10;
        }
    }

    /** One line for an event, in the terms a player thinks in rather than class names. */
    private String describeEvent(SequencedEvent se) {
        Event e = se.event;
        if (e instanceof Event.PlaceOrder) {
            Event.PlaceOrder p = (Event.PlaceOrder) e;
            return (p.isBid ? "buy " : "sell ") + p.volume + " "
                    + shortName(p.itemId) + " @ " + p.price;
        }
        if (e instanceof Event.DepositAndList) {
            Event.DepositAndList d = (Event.DepositAndList) e;
            return "listed " + d.quantity + " " + shortName(d.itemId) + " @ " + d.price;
        }
        if (e instanceof Event.Deposit) {
            Event.Deposit d = (Event.Deposit) e;
            return "deposited " + d.quantity + " " + shortName(d.itemId);
        }
        if (e instanceof Event.Withdraw) {
            Event.Withdraw wd = (Event.Withdraw) e;
            return "withdrew " + wd.quantity + " " + shortName(wd.itemId);
        }
        if (e instanceof Event.CancelOrder) return "cancelled an order";
        if (e instanceof Event.KeyRegistered) return "someone joined";
        if (e instanceof Event.MarketCreated) return "market created";
        return e.getClass().getSimpleName();
    }

    private String shortName(String itemId) {
        Item item = MinecraftIds.idToItem(itemId);
        return item == Items.AIR ? itemId : item.getName().getString();
    }

    /**
     * Cuts a string to fit a pixel width, with an ellipsis when it had to.
     *
     * Uses vanilla's own trimToWidth rather than walking characters off the end: this is
     * called for every row of two panels every frame, and the font is not fixed-width.
     */
    private String trim(String s, int maxWidth) {
        if (this.textRenderer.getWidth(s) <= maxWidth) return s;
        int room = Math.max(0, maxWidth - this.textRenderer.getWidth("..."));
        return this.textRenderer.trimToWidth(s, room) + "...";
    }

    /**
     * The vanilla tooltip frame — near-black fill, violet gradient edge.
     *
     * Moved to {@link Panels} when the inventory listings panel needed the same look. The
     * colours are still used directly by the tab bar below, which draws its own shape out
     * of them rather than a box, so they are imported rather than re-declared: two
     * definitions of this violet is how the tabs and the panel they sit on start
     * disagreeing after somebody adjusts one.
     */
    private static final int PANEL_BG = Panels.PANEL_BG;
    private static final int PANEL_EDGE_TOP = Panels.PANEL_EDGE_TOP;
    private static final int PANEL_EDGE_BOTTOM = Panels.PANEL_EDGE_BOTTOM;

    private void vanillaPanel(MatrixStack m, int x, int y, int w, int h) {
        Panels.vanillaPanel(m, x, y, w, h);
    }

    /** A titled empty box. The border makes the layout legible before the content exists. */
    private void panel(MatrixStack m, int x, int y, int w, int h, String title) {
        vanillaPanel(m, x + 4, y + 4, w - 8, h - 8);
        label(m, title, x + 6, y + 5, 0xFFAA00);
    }

    private void renderHostsPanel(MatrixStack m, int x, int y, int w, int h) {
        if (polling && discovered.isEmpty()) {
            label(m, "searching...", x, y, 0x808080);
            return;
        }
        if (discovered.isEmpty()) {
            label(m, "nobody hosting", x, y, 0x808080);
            return;
        }
        int row = y;
        for (PeerPoll.HostInfo host : discovered) {
            if (row + 10 > y + h) break;
            // A player-chosen name in a third-width panel; nothing bounds its length
            // but this.
            String name = host.reply.hostName == null ? "?" : host.reply.hostName;
            label(m, trim(name, w), x, row, 0x88CCFF);
            row += 10;
            if (row + 10 > y + h) break;
            label(m, "  " + host.reply.lastSeq + " events, "
                    + host.reply.clientCount + " online", x, row, 0x808080);
            row += 12;
        }
    }

    private void renderSettingsPlaceholder(MatrixStack m) {
        label(m, "Notifications", listX + 4, panelTop(), 0xFFAA00);

        String body = "Told when one of your resting orders fills, so you can set an"
                + " order, log off, and find out later that it happened. The limit"
                + " batches them once they come faster than that — on a busy market"
                + " you lose the detail, never the news.";

        int y = panelTop() + 14;
        for (OrderedText line : this.textRenderer.wrapLines(new LiteralText(body), listW - 12)) {
            this.textRenderer.drawWithShadow(m, line, listX + 4, y, 0xC0C0C0);
            y += 10;
        }

        y += 8;
        label(m, "Saved for " + MinecraftClient.getInstance().getSession().getUsername(),
                listX + 4, y, 0x707070);
        label(m, "The hosting port lives on Network.", listX + 4, y + 10, 0x707070);
    }

    // ─────────── the Market screen ───────────
    //
    // Five buttons that were always all visible, with nothing saying which one applied.
    // During testing that produced Connect clicked when Migrate was meant, twice — the
    // buttons were equally available and equally unexplained, and two of them are
    // irreversible.
    //
    // The situation is derived instead, and only what applies is offered.

    private static final int MS_DAMAGED = 0;
    private static final int MS_NO_MARKET = 1;
    private static final int MS_FORKED = 2;
    private static final int MS_BEHIND = 3;
    private static final int MS_CONNECTED = 4;
    private static final int MS_OFFLINE = 5;

    /** Ordered by how much it matters: a damaged log makes everything else moot. */
    private int marketSituation() {
        if (MarketStateHolder.chainBrokenAt() != -1) return MS_DAMAGED;
        if (!MarketStateHolder.hasMarket()) return MS_NO_MARKET;
        if (MarketStateHolder.divergence() != null) return MS_FORKED;
        if (MarketStateHolder.eventsBehind() > 0) return MS_BEHIND;
        if (MarketStateHolder.isConnected()) return MS_CONNECTED;
        return MS_OFFLINE;
    }

    /**
     * A host serving a market that isn't ours, if discovery has seen one.
     *
     * This is what makes Migrate meaningful — it is only ever the right answer when
     * somewhere else is running a genuinely different economy. Offering it otherwise
     * is what let it be confused with Reset.
     */
    private PeerPoll.HostInfo foreignHost() {
        MarketState mine = MarketStateHolder.get();
        if (mine == null || mine.marketId() == null) return null;
        String myMarket = mine.marketId().toString();

        // Prefers one you could actually migrate to. There can be several foreign
        // markets on the network and this picks one of them for the whole Market
        // column — which used to be arbitrary and harmless, because every answer led to
        // the same offer. It stopped being harmless when a dedicated server started
        // meaning "no Migrate at all": whichever host the poll happened to list first
        // then decided whether the button existed, so a box could hide a migration to
        // somebody else's game entirely.
        PeerPoll.HostInfo fallback = null;
        for (PeerPoll.HostInfo h : discovered) {
            if (h.reply.marketId == null || myMarket.equals(h.reply.marketId)) continue;
            if (!h.reply.dedicated) return h;
            if (fallback == null) fallback = h;
        }
        return fallback;
    }

    /**
     * Whether the foreign market on offer is served by a dedicated server.
     *
     * From the discovery reply rather than from a connection, because the question has
     * to be answerable before deciding whether to offer Migrate — and `dedicated` is on
     * QueryReply for exactly that reason, with a comment saying a badge that only
     * appeared after connecting would answer too late to be of use in choosing.
     *
     * Self-reported and signed, so nobody can change it in transit and a host can still
     * describe itself however it likes. That is enough for advice, which is all this is.
     */
    /**
     * Whether a dedicated server is serving the market this world holds.
     *
     * Two sources because they answer at different times and neither covers the other.
     * Being connected to one is certain and needs no poll. The discovery list covers the
     * case that actually bites — holding the market offline, or connected to nobody, and
     * about to press Host on something a box is already sequencing.
     *
     * Used to grey Host, which a comment beside the Migrate button has claimed happens
     * since before it did. Hosting a market a dedicated server also serves is the one
     * reliable way to fork a market permanently: the box does not stop, so both of you
     * keep sequencing, and two branches of one market cannot be merged.
     */
    private boolean dedicatedServesThisMarket() {
        if (MarketStateHolder.isConnected() && MarketStateHolder.hostIsDedicated()) {
            return true;
        }
        MarketState mine = MarketStateHolder.get();
        if (mine == null || mine.marketId() == null) return false;
        String myMarket = mine.marketId().toString();
        for (PeerPoll.HostInfo h : discovered) {
            if (h.reply != null && h.reply.dedicated
                    && myMarket.equals(h.reply.marketId)) {
                return true;
            }
        }
        return false;
    }

    private boolean foreignIsDedicated() {
        PeerPoll.HostInfo h = foreignHost();
        return h != null && h.reply != null && h.reply.dedicated;
    }

    /** Lays out only the applicable actions, top to bottom with no gaps. */
    private void refreshMarketActions() {
        if (activeScreen != SCREEN_MARKET) return;

        int situation = marketSituation();
        boolean foreign = foreignHost() != null;

        boolean create = situation == MS_NO_MARKET;
        boolean importFile = situation == MS_NO_MARKET;
        boolean export = situation == MS_CONNECTED || situation == MS_OFFLINE
                || situation == MS_BEHIND;
        // Only where there is somewhere to migrate TO, and never as an answer to a
        // fork — the host refuses that, because our position already includes the
        // history their copy has and crediting it again would pay us twice.
        //
        // And not towards a dedicated server, which by default does not take them: the
        // balance a migrant carries was set by a welcome grant they chose in a world
        // they control, which is fine among people who know each other and is not what
        // a public box wants. The guidance beside this offers the route that costs
        // nothing instead — add a market slot and connect from it.
        //
        // Read from the discovery reply, which carries `dedicated` precisely so it can
        // be known before connecting. It is advice, not the answer: the server's own
        // acceptsMigration is what actually decides, and an operator may have turned it
        // back on. Erring this way hides a button that would have worked; erring the
        // other way sends somebody's whole log over the wire to be refused.
        boolean migrate = foreign && situation != MS_NO_MARKET && situation != MS_FORKED
                && situation != MS_DAMAGED && !foreignIsDedicated();
        boolean reset = situation != MS_NO_MARKET;

        // Hidden rather than greyed, unlike the Host button on a dedicated market.
        // That one is demoted because every player could plausibly host and the greying
        // teaches why they should not here; this is an action all but one person in any
        // market can never take, and a permanently dead control teaches nothing. The
        // About block says who does set the fee, so its absence is still explained.
        boolean canSetFee = amCreator()
                && situation != MS_NO_MARKET && situation != MS_DAMAGED;

        // One running cursor for everything in this column. The name field used to be
        // repositioned in a block after the buttons had been placed, which left y
        // describing a layout that no longer existed — and the next thing placed from
        // it landed on top of Import.
        // Band-aware, unlike the widgets placed once in init(): this runs every frame
        // and sets absolute positions, so it has to arrive at the same answer the shift
        // in reflowForAlerts gives everything else. Two rules for one column is how the
        // buttons ended up through the tab row.
        int top = rowY + 4 + alertBandH();
        int y = top - scrollOf("marketcol");

        placeField(marketNameField, create, y);
        // Above the button that consumes it.
        if (create) y += ROW_STEP;

        y = place(createButton, create, y);
        y = place(importButton, importFile, y);
        y = place(exportButton, export, y);
        y = place(migrateButton, migrate, y);
        y = place(resetButton, reset, y);

        if (canSetFee) {
            // Each control on one row, field beside its button. Stacking the field above
            // the button cost 58px a control, and four of those ran the column past the
            // bottom of its own panel. One padding before the group, not before each.
            y += 6;
            placeField(feeField, true, y);
            y = place(feeButton, true, y);
            placeField(listingFeeField, true, y);
            y = place(listingFeeButton, true, y);
            placeField(stipendField, true, y);
            y = place(stipendButton, true, y);
            placeField(grantField, true, y);
            y = place(grantButton, true, y);
        }  else {
            placeField(feeField, false, y);
            placeField(listingFeeField, false, y);
            placeField(stipendField, false, y);
            placeField(grantField, false, y);
            y = place(feeButton, false, y);
            y = place(listingFeeButton, false, y);
            y = place(stipendButton, false, y);
            y = place(grantButton, false, y);
        }

        // Offered to anyone the market owes, not only its creator, and only while it
        // owes them — a button that is always there and usually does nothing teaches
        // people to stop reading it.
        y = place(claimStipendButton, stipendClaimable(), y);

        // Offered wherever there is a world to put one in — including a world with no
        // market at all, since "another" is only ever one more than however many there
        // are. Not offered on a damaged log, where the answer is to fix that first.
        y = place(addMarketButton, situation != MS_DAMAGED, y + 6);

        // Only where there is more than one to choose between, for the same reason the
        // list itself is hidden then: a world with a single market has nothing to leave.
        y = place(deleteMarketButton, MarketStateHolder.availableSlots().size() > 1, y);

        // What the column would need if nothing were hidden, measured from where it
        // started rather than assumed — the rows shown depend on the situation, so a
        // fixed figure would be wrong for most of them.
        //
        // Measured from panelTop, and to the bottom edge of the last row rather than to
        // the cursor sitting a whole ROW_STEP past it. Both corrections are the same
        // point: this figure is only ever compared against what place() will accept, and
        // place() asks whether y + FIELD_HEIGHT fits between panelTop and panelBottom.
        // Measured any other way the two disagree, and they disagreed by nine pixels —
        // exactly enough that the last row could never be scrolled into view.
        marketColumnHeight = (y + scrollOf("marketcol")) - ROW_STEP + FIELD_HEIGHT
                - panelTop();
    }

    /**
     * Set by refreshMarketActions, read by render to size the scrollable region.
     *
     * From panelTop to the bottom of the last row, which is the span place() clips
     * against. Not from the frame, and not to the cursor past the last row.
     */
    private int marketColumnHeight;

    /** Whether the player at this keyboard is the identity named in the genesis event. */
    private boolean amCreator() {
        MarketState market = MarketStateHolder.get();
        if (market == null || market.creator() == null) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;
        return market.creator().equals(MinecraftIds.userIdOf(mc.player));
    }

    /**
     * Sets the market's trading fee.
     *
     * Confirmed rather than immediate: it changes what everyone else's trades settle
     * at, and the fee is the one number here that silently takes money. Not a DANGER
     * overlay though — nothing is destroyed and the next event can put it back, which
     * is the line the overlay kinds are drawn on.
     *
     * The welcome grant has a control too now, but a DANGER one rather than this plain
     * confirm. It used to have none, on the grounds that a fat-fingered grant has more
     * consequence and less feedback than a fat-fingered fee. The first half holds and is
     * why the overlay is the harsher kind; the second half did not survive being looked
     * at, because hasBeenGranted means a grant only ever reaches identities that have
     * not joined yet. That makes a mistake forward-only and correctable before the next
     * person arrives — the same recoverability the stipend has.
     *
     * What the omission actually cost was worse than what it prevented. A market made
     * in-game is created with the built-in default and nothing could change it
     * afterwards, so every rotating market granted exactly a thousand credits against
     * items trading for one or two. The largest lever on what credits are worth was the
     * one nobody could reach.
     *
     * Restricting the amount by hosting mode was considered and rejected: core has no
     * concept of dedicated vs rotating — that is a self-reported per-connection flag on
     * Sync/QueryReply, not a property of the log — so there is nowhere honest to enforce
     * such a split, and the creator's key could author the event by hand regardless of
     * what the shipped UI offers. Every control here is a guardrail, never a boundary.
     */
    private void onSetFee() {
        String raw = feeField.getText().trim();
        if (raw.isEmpty()) {
            status = "Type a fee first, as a percentage";
            return;
        }

        int bps = MarketState.bpsFromPercent(raw);
        if (bps < 0) {
            status = "Fee must be a percentage, at most 2 decimal places (e.g. 2.5)";
            return;
        }
        if (bps > MarketState.MAX_TAX_BPS) {
            status = "The most a market may charge is "
                    + (MarketState.MAX_TAX_BPS / 100) + "%";
            return;
        }

        MarketState market = MarketStateHolder.get();
        int current = market == null ? 0 : market.taxBps();
        if (bps == current) {
            status = "The fee is already " + formatBps(bps);
            return;
        }

        String body;
        if (bps == 0) {
            body = "Selling will cost nothing from the next trade onward. Trades already"
                    + " made keep the fee they settled at — this is not backdated.";
        } else {
            body = "From the next trade onward, " + formatBps(bps) + " of every sale is"
                    + " taken from the seller and destroyed. Trades already made keep"
                    + " the fee they settled at, and everyone connected sees this"
                    + " change as soon as it is sequenced.";

            // What the rate is worth at the prices this market actually trades at. A
            // percentage of a small sale rounds to nothing, and finding that out by
            // watching a fee take zero is a bad way to learn it.
            long floor = MarketState.smallestTaxableSale(bps);
            long typical = typicalSaleValue();
            body += " Because it rounds down, it takes nothing from a sale under "
                    + floor + " credits";
            if (typical > 0) {
                body += typical < floor
                        ? ". Sales here have been worth about " + typical
                                + ", so at this rate most would be untaxed."
                        : ", which recent sales here clear comfortably.";
            } else {
                body += ".";
            }
        }

        showConfirm(bps == 0 ? "Remove the trading fee?" : "Set the trading fee to "
                + formatBps(bps) + "?", body, "Set fee",
                () -> submitPolicy(p -> p.taxBps = bps));
    }

    /**
     * Roughly what a sale is worth in this market, or 0 when nothing has traded.
     *
     * The median rather than the mean, because one large trade should not make a market
     * of one-credit sales look like it clears a fee comfortably. Only used to warn
     * somebody that a rate would round to nothing here, so approximate is fine.
     */
    private long typicalSaleValue() {
        MarketState market = MarketStateHolder.get();
        if (market == null) return 0;

        List<Long> values = new ArrayList<>();
        for (String itemId : market.activeItems()) {
            for (Trade t : market.trades().recentFor(itemId, 20)) {
                values.add(t.price * t.quantity);
            }
        }
        if (values.isEmpty()) return 0;
        Collections.sort(values);
        return values.get(values.size() / 2);
    }

    /**
     * Sets the flat charge for placing an order.
     *
     * Confirmed like the trading fee, and for a sharper reason: this one is not
     * refunded when an order is cancelled, so it is the only charge here that can be
     * paid for nothing. The confirmation says so rather than leaving it to be
     * discovered.
     */
    private void onSetListingFee() {
        String raw = listingFeeField.getText().trim();
        if (raw.isEmpty()) {
            status = "Type a listing fee first, in credits";
            return;
        }

        // "2" is a flat fee; "2/3" is a fee of 2 with three orders free of escalation.
        // One field for both because they are one decision — see listingFeeFromText,
        // which is also where this is tested, since it cannot be tested from here.
        MarketState.ListingFeeSetting parsed = MarketState.listingFeeFromText(raw);
        if (parsed == null) {
            status = "Type a fee like 2, or 2/3 for a fee of 2 with 3 orders free"
                    + " before it climbs";
            return;
        }

        final long fee = parsed.fee;
        final int free = parsed.freeOrders;

        if (fee > MarketState.MAX_LISTING_FEE) {
            status = "The most a market may charge to list is "
                    + MarketState.MAX_LISTING_FEE;
            return;
        }
        if (free > MarketState.MAX_LISTING_FREE_ORDERS) {
            status = "The largest allowance is "
                    + MarketState.MAX_LISTING_FREE_ORDERS + " orders";
            return;
        }
        // An allowance is a number of orders you escalate *past*. With no fee there is
        // nothing to escalate, so this would set a figure that does nothing and read
        // like it did something.
        if (fee == 0 && free > 0) {
            status = "An allowance needs a fee to climb from — type 0 on its own to"
                    + " remove the listing fee";
            return;
        }

        MarketState market = MarketStateHolder.get();
        long currentFee = market == null ? 0 : market.listingFee();
        int currentFree = market == null ? 0 : market.listingFreeOrders();
        if (fee == currentFee && free == currentFree) {
            status = free > 0
                    ? "The listing fee is already " + fee + " with " + free + " free"
                    : "The listing fee is already " + fee;
            return;
        }

        String body;
        if (fee == 0) {
            body = "Placing orders will be free again from the next one onward.";
        } else {
            body = "Placing an order will cost " + fee + " credits, whether it is a buy"
                    + " or a sell, and whether or not it ever trades. It is not"
                    + " returned if the order is cancelled — that is what makes it"
                    + " discourage flooding the book, and also what makes it cost"
                    + " something to reprice, so keep it small. Sellers pay it too,"
                    + " so anyone holding goods and no credits will not be able to"
                    + " list at all.";
            body += free > 0
                    ? " The first " + free + " orders somebody is holding open cost that"
                            + " much each; the next costs double, the one after triple,"
                            + " and so on. Cancelling one brings the cost back down, so"
                            + " it prices what you are holding open rather than what you"
                            + " have ever placed."
                    : " The same for everyone, however many orders they are already"
                            + " holding open. Add an allowance — 2/3 — to make it climb"
                            + " for whoever is holding the most.";
        }

        String title;
        if (fee == 0) {
            title = "Remove the listing fee?";
        } else if (free > 0) {
            title = "Charge " + fee + " credits to place an order, after " + free
                    + " free?";
        } else {
            title = "Charge " + fee + " credits to place an order?";
        }

        showConfirm(title, body, "Set fee", () -> submitPolicy(p -> {
            p.listingFee = fee;
            p.listingFreeOrders = free;
        }));
    }

    /**
     * Writes the market's whole policy, with one field changed.
     *
     * A MarketPolicy event carries every setting, so both controls go through here
     * rather than each building their own — leaving a field at its zero default would
     * turn off whatever it governs as a side effect of changing something else, which
     * would have set the welcome grant to nothing every time somebody edited a fee.
     * One place to restate them means one place to get that right.
     */
    /**
     * Sends a policy change, carrying every field that is not being changed.
     *
     * A MarketPolicy event is the whole policy, not a patch — so anything this does not
     * restate is set to zero by the event it writes. That has already cost this project
     * once, when setting a fee would have wiped the welcome grant, and it caught me
     * again the moment the stipend fields were added.
     *
     * So the policy is built from what the market currently publishes and the caller is
     * handed it to change. Forgetting a field now means it keeps its value, which is the
     * failure that does no harm.
     */
    /**
     * Sets what a newcomer is handed on joining.
     *
     * There deliberately was no control for this, on the grounds that a fat-fingered
     * grant has more consequence and less feedback than a fat-fingered fee. The first
     * half is true and is why this is a DANGER rather than a plain confirm. The second
     * half did not survive being looked at: hasBeenGranted means a grant only ever
     * reaches identities that have not joined yet, so a mistake is forward-only and can
     * be corrected before the next person arrives — the same recoverability the stipend
     * has, and the stipend has a control.
     *
     * What the omission actually cost was worse. A market made in-game is created with
     * the built-in default and nothing anywhere can change it, so every rotating market
     * grants exactly a thousand — against items that trade for one or two. The largest
     * lever on the money supply was the one nobody could reach.
     */
    private void onSetGrant() {
        String raw = grantField.getText().trim();
        if (raw.isEmpty()) {
            status = "Type a welcome grant first, in credits";
            return;
        }

        long amount;
        try {
            amount = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            status = "A welcome grant must be a whole number of credits";
            return;
        }
        if (amount < 0) {
            status = "A welcome grant cannot be negative";
            return;
        }
        // The figure a host will actually sequence, not the compiled backstop. Which
        // host decides is not knowable from here — it is a host rule, like admission —
        // so this quotes the one that applies to somebody hosting in their own game,
        // which is who is reading it. A dedicated server allows more and says so if you
        // ask it for more.
        if (amount > ServerConfig.ROTATING_MAX_WELCOME_GRANT) {
            status = "The most a market hosted from a game may grant is "
                    + ServerConfig.ROTATING_MAX_WELCOME_GRANT
                    + ". A grant far above what things trade for is the largest single"
                    + " lever on what credits are worth";
            return;
        }

        MarketState market = MarketStateHolder.get();
        if (market == null) { status = "No market"; return; }
        if (amount == market.welcomeGrant()) {
            status = "The welcome grant is already " + amount;
            return;
        }

        String body = "Anyone who joins from here on receives " + amount
                + " instead of " + market.welcomeGrant() + ". Nobody who has already"
                + " joined is affected — a grant is once per identity, so this reaches"
                + " only people who are not here yet, and can be changed again before"
                + " they arrive."
                + (amount == 0 ? " At zero, newcomers arrive with nothing and cannot"
                        + " place a buy order until somebody sells them something."
                        : "")
                + " This is the largest single lever on what credits are worth: set it"
                + " far above what things trade for and prices stop meaning much.";

        showDanger("Grant newcomers " + amount + " credits?", body, "Set grant",
                () -> submitPolicy(p -> p.grantAmount = amount));
    }

    /** Whether this market currently owes the local player a stipend. */
    private boolean stipendClaimable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        MarketState market = MarketStateHolder.get();
        if (mc.player == null || market == null) return false;
        if (market.stipendAmount() <= 0) return false;

        UUID me = MinecraftIds.userIdOf(mc.player);
        if (!market.isRegistered(me)) return false;
        return market.fillsEver() - market.stipendedAtFill(me) >= market.stipendEveryFills();
    }

    /**
     * Sets what the market pays, and how often, as one decision.
     *
     * The interval is not offered separately. It is one of three numbers that have to
     * agree — the third being the listing fee — and a control that lets somebody set one
     * of them to a value the other two refuse is a control that mostly produces
     * rejections. The default interval is deliberately long; a creator who wants a
     * different one can author the policy directly.
     */
    private void onSetStipend() {
        String raw = stipendField.getText().trim();
        if (raw.isEmpty()) {
            status = "Type a stipend first, in credits";
            return;
        }

        long amount;
        try {
            amount = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            status = "A stipend must be a whole number of credits";
            return;
        }
        if (amount < 0) {
            status = "A stipend cannot be negative";
            return;
        }
        if (amount > MarketState.MAX_STIPEND) {
            status = "The most a market may pay is " + MarketState.MAX_STIPEND;
            return;
        }

        MarketState market = MarketStateHolder.get();
        if (market == null) { status = "No market"; return; }
        if (amount == market.stipendAmount()) {
            status = amount == 0 ? "This market already pays no stipend"
                    : "The stipend is already " + amount;
            return;
        }

        long every = MarketState.DEFAULT_STIPEND_EVERY_FILLS;

        // The interlock, asked here so it is explained rather than merely refused.
        // Every replica asks it again in EventApplier — this is the courtesy, not the
        // enforcement.
        //
        // Asked, not reimplemented. This had its own copy of the arithmetic, and the
        // copy was the version from before the rule was corrected twice: it costed a
        // fill at two listing fees and counted one claimant, so it advertised a ceiling
        // four times the real one and passed figures the engine then refused. The head
        // count is what a second copy can never keep up with — it moves every time
        // somebody joins.
        String unsafe = EventApplier.stipendOutpacesItsFees(
                amount, every, market.listingFee(), market.registeredCount());
        if (unsafe != null) {
            status = unsafe;
            return;
        }

        String body = amount == 0
                ? "Nobody will be paid a stipend from here on. Anything already claimed"
                        + " stays where it is."
                : "Every registered player may claim " + amount + " credits once per "
                        + every + " trades this market settles. It exists because the"
                        + " welcome grant is otherwise the only way credits ever enter,"
                        + " so goods pile up against a money supply that never grows and"
                        + " prices sink. Paid per trade rather than per minute, so it"
                        + " follows the market being used rather than the clock — and so"
                        + " it cannot be farmed by somebody trading with themselves,"
                        + " which the listing fee makes cost more than it pays.";

        showConfirm(amount == 0 ? "Stop paying a stipend?"
                        : "Pay " + amount + " credits every " + every + " trades?",
                body, "Set stipend", () -> submitPolicy(p -> {
                    p.stipendAmount = amount;
                    p.stipendEveryFills = every;
                }));
    }

    /** Claims what the market owes. No confirmation — nothing is spent or lost. */
    private void onClaimStipend() {
        MinecraftClient mc = MinecraftClient.getInstance();
        MarketState market = MarketStateHolder.get();
        if (mc.player == null || market == null) { status = "No market"; return; }

        Event.Stipend claim = new Event.Stipend();
        claim.userId = MinecraftIds.userIdOf(mc.player);
        claim.amount = market.stipendAmount();
        claim.timestamp = System.currentTimeMillis();

        MarketStateHolder.Submission s = MarketStateHolder.submit(claim);
        if (s.pending) {
            status = "Stipend claim sent...";
        } else if (s.accepted) {
            status = "Claimed " + claim.amount + " credits";
        } else {
            status = "Rejected: " + s.reason;
        }
        refreshMarketButtons();
    }

    private void submitPolicy(java.util.function.Consumer<Event.MarketPolicy> change) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) { status = "No player"; return; }

        MarketState market = MarketStateHolder.get();
        if (market == null) { status = "No market"; return; }

        Event.MarketPolicy policy = new Event.MarketPolicy();
        policy.userId = MinecraftIds.userIdOf(mc.player);
        policy.taxBps = market.taxBps();
        policy.grantAmount = market.welcomeGrant();
        policy.listingFee = market.listingFee();
        policy.listingFreeOrders = market.listingFreeOrders();
        policy.stipendAmount = market.stipendAmount();
        policy.stipendEveryFills = market.stipendEveryFills();
        policy.timestamp = System.currentTimeMillis();

        change.accept(policy);

        MarketStateHolder.Submission s = MarketStateHolder.submit(policy);
        if (s.pending) {
            status = "Policy change sent...";
        } else if (s.accepted) {
            status = "Trading fee " + formatBps(policy.taxBps)
                    + ", listing fee " + policy.listingFee
                    + (policy.listingFreeOrders > 0
                            ? " after " + policy.listingFreeOrders + " free" : "")
                    + (policy.stipendAmount > 0
                            ? ", stipend " + policy.stipendAmount + " every "
                                    + policy.stipendEveryFills + " trades"
                            : "");
            feeField.setText("");
            listingFeeField.setText("");
            stipendField.setText("");
            grantField.setText("");
        } else {
            status = "Rejected: " + s.reason;
        }
    }

    /**
     * Puts a button on the next row, or hides it if that row is off the panel.
     *
     * This used to stack without a bottom, which is how the Market column came to draw
     * its last controls below the frame they belong to. Hiding an out-of-view row rather
     * than drawing it means the panel can be scrolled instead: the cursor still advances
     * for hidden rows, so what is on screen depends only on the offset.
     */
    private int place(ButtonWidget button, boolean shown, int y) {
        if (button == null) return y;
        boolean inView = y >= panelTop() && y + FIELD_HEIGHT <= panelBottom();
        button.visible = shown && inView;
        button.active = button.visible;
        if (!shown) return y;
        button.y = y;
        return y + ROW_STEP;
    }

    /** The same for a text field, which is positioned rather than placed. */
    private void placeField(TextFieldWidget field, boolean shown, int y) {
        if (field == null) return;
        boolean inView = y >= panelTop() && y + FIELD_HEIGHT <= panelBottom();
        field.visible = shown && inView;
        field.active = field.visible;
        if (shown) field.y = y;
    }

    /** What is going on, in words, beside the actions that answer it. */
    private void renderMarketGuidance(MatrixStack m, int x) {
        MarketState market = MarketStateHolder.get();
        int situation = marketSituation();
        PeerPoll.HostInfo foreign = foreignHost();

        String heading;
        String body;

        switch (situation) {
            case MS_DAMAGED:
                heading = "This world's market log is unreadable";
                body = (MarketStateHolder.damageReason() == null
                        ? "The file is damaged." : MarketStateHolder.damageReason())
                        + " Nothing can be done until it is discarded. If somebody else"
                        + " still has this market, you get it all back when you"
                        + " reconnect.";
                break;
            case MS_NO_MARKET:
                heading = "This world has no market";
                // The reason before the instruction. This read "do NOT create one, since
                // two markets can never be merged", which shouted, and put the warning
                // where somebody who had already clicked Create would find it.
                body = "Two markets can never be merged, so if your friends already have"
                        + " one, join theirs from the Network screen. Otherwise create"
                        + " one here, or import a file somebody exported.";
                break;
            case MS_FORKED:
                heading = "You have diverged from this market";
                // "the host will refuse it" read as a fault rather than a safeguard, so
                // it says whose protection it is and why it exists.
                body = MarketStateHolder.divergence().describe()
                        + ". Discard and reconnect to rejoin them. Only what you did"
                        + " after the split is lost, since everything before it is in"
                        + " their copy. Migrating is for joining a different market, and"
                        + " a host declines it here on purpose.";
                break;
            case MS_BEHIND:
                heading = "Your copy is behind";
                body = MarketStateHolder.eventsBehind() + " events have happened that you"
                        + " do not have. Connect to whoever is serving it from the"
                        + " Network screen and you catch up automatically. Do not host"
                        + " until you have.";
                break;
            case MS_CONNECTED:
                heading = "Connected to '" + (market == null ? "?" : market.marketName()) + "'";
                // This opened with "Everything is in order", which tells somebody nothing
                // they could not see, and then spent the paragraph on exporting to a
                // file. This is the one state a working market gets to explain itself in,
                // so it says what is live and where to go next, and keeps export as the
                // afterthought it is.
                body = "Trading is live, and anything you buy or sell settles for"
                        + " everybody at once. Your balance and resting orders are on the"
                        + " Trading tab. You can also export a copy here, for somebody"
                        + " who cannot be online while anyone is hosting.";
                break;
            default:
                heading = "You hold '" + (market == null ? "?" : market.marketName()) + "'";
                body = "Nobody is serving it. Host it from the Network screen so others"
                        + " can trade, or connect to someone who already is.";
                break;
        }

        // Scrolls, for the reason the controls column does: this panel stacks a heading,
        // a paragraph, sometimes a second paragraph about a host on the network, the
        // market's own facts, and the switcher for the other markets in this world —
        // and it had no bottom. What did not fit was silently dropped, and the first
        // thing to go was the switcher, which is the only *control* in the column. A
        // longer paragraph about dedicated servers was enough to make it disappear.
        //
        // Everything below measures from `top` and clips through guideLine(), so the
        // offset is applied in one place and no drawing can escape the panel.
        int top = panelTop() - scrollOf("marketguide");

        guideLabel(m, heading, x, top, 0xFFAA00);

        int y = top + 14;
        for (OrderedText line : this.textRenderer.wrapLines(new LiteralText(body), listW - 12)) {
            guideLine(m, line, x, y, 0xC0C0C0);
            y += 10;
        }

        if (foreign != null && situation != MS_NO_MARKET && situation != MS_FORKED) {
            y += 6;
            // Two different pieces of advice, because a dedicated server is a different
            // proposition. Migration suits people who know each other; a public box does
            // not take them by default, and saying only "you cannot migrate" would leave
            // somebody thinking they cannot join at all. The route that costs nothing is
            // the one worth naming — slots are separate logs, so joining from a new one
            // leaves the market they already have exactly where it is.
            String advice = foreignIsDedicated()
                    ? foreign.reply.hostName + " is a dedicated server running a separate"
                            + " market ('" + foreign.reply.marketName + "'), and it does"
                            + " not take migrations. Use Add another market and connect"
                            + " from that one. This market stays exactly as it is, and"
                            + " you arrive there on their welcome grant like anyone else."
                    : foreign.reply.hostName + " is running a separate market ('"
                            + foreign.reply.marketName + "'). Migrating carries your"
                            + " whole position there and abandons this one. Add another"
                            + " market to join without giving this one up.";
            for (OrderedText line : this.textRenderer.wrapLines(
                    new LiteralText(advice), listW)) {
                guideLine(m, line, x, y, 0x88CCFF);
                y += 10;
            }
        }

        y = renderMarketFacts(m, x, y + 10);
        y = renderMarketSlots(m, x, y + 10);

        // What the column would need if nothing were clipped, measured from where it
        // started — read by render next frame to size the scroll. Same arrangement as
        // marketColumnHeight, including the one-frame lag, which nobody can see.
        marketGuideHeight = (y + scrollOf("marketguide")) - panelTop();
    }

    /** Set by renderMarketGuidance, read by render to size its scrollable region. */
    private int marketGuideHeight;

    /**
     * Draws one line of the guidance column, or skips it if it falls outside the panel.
     *
     * The single clip for everything in this column. Vanilla's text renderer has no
     * scissor here, so "scrolled out of view" has to mean "not drawn" — and having one
     * function decide that is what stops the panel growing another way to lose things
     * quietly, which is what it did before it scrolled at all.
     */
    private void guideLine(MatrixStack m, OrderedText line, int x, int y, int colour) {
        if (y < panelTop() || y + 9 > panelBottom()) return;
        this.textRenderer.drawWithShadow(m, line, x, y, colour);
    }

    /** The same for a plain string. */
    private void guideLabel(MatrixStack m, String text, int x, int y, int colour) {
        if (y < panelTop() || y + 9 > panelBottom()) return;
        // Trimmed to the same width the body beneath it wraps to. It clipped vertically
        // and not horizontally, so a heading that grew — and these are chosen by
        // situation, so they do grow — ran out through the side of the panel it is
        // supposed to be inside. The lines under it were wrapped from the first day;
        // only the heading was left measuring nothing.
        label(m, trim(text, listW - 12), x, y, colour);
    }

    /**
     * What this market charges and who runs it.
     *
     * Here because a fee that exists and is never shown makes the first fill a nasty
     * surprise — the tax could not ship without somewhere to read it. Below the
     * guidance rather than above it: when something is wrong, the thing that is wrong
     * outranks the reference card.
     */
    private int renderMarketFacts(MatrixStack m, int x, int y) {
        MarketState market = MarketStateHolder.get();
        if (market == null || market.marketId() == null) return y;
        // No "no room" bail any more. The column scrolls, so running out of panel is a
        // reason to be scrolled to rather than a reason to vanish — and this block
        // vanishing took the switcher below it with it, since y never advanced past here.

        guideLabel(m, "About this market", x, y, 0xFFDD66);
        y += 12;

        y = wrapped(m, "Name: " + market.marketName(), x, y, 0xAAAAAA);

        // Rotating or dedicated. Only known while connected — a Sync is where it is
        // told — so the offline case says nothing rather than guessing "rotating".
        if (MarketStateHolder.isConnected()) {
            y = wrapped(m, MarketStateHolder.hostIsDedicated()
                            ? "Host: dedicated server — always up, nobody takes turns"
                            : "Host: another player's game — up while they are",
                    x, y, 0xAAAAAA);
        }

        int bps = market.taxBps();
        if (bps <= 0) {
            y = wrapped(m, "Trading fee: none", x, y, 0xAAAAAA);
        } else {
            // Both forms, because the rate is set in basis points and felt in credits.
            y = wrapped(m, "Trading fee: " + formatBps(bps) + " of each sale, taken from"
                    + " the seller and destroyed", x, y, 0xFFAA55);
            // The fee rounds down, so below this it comes to nothing. Said plainly,
            // because a rate that quietly takes zero looks like a rate that is broken.
            y = wrapped(m, "Takes nothing from sales under "
                    + MarketState.smallestTaxableSale(bps) + " credits.",
                    x, y, 0x909090);
        }

        long listing = market.listingFee();
        if (listing > 0) {
            int freeOrders = market.listingFreeOrders();
            y = wrapped(m, "Listing fee: " + listing + " credits to place any order,"
                    + " kept even if you cancel", x, y, 0xFFAA55);
            // Only when it is on. A market with no allowance charges the flat fee and
            // has nothing extra to explain.
            if (freeOrders > 0) {
                MinecraftClient mc = MinecraftClient.getInstance();
                String mine = "";
                if (mc.player != null) {
                    // What the next order would actually cost this player, which is the
                    // question anybody reads this line to answer.
                    long next = market.listingFeeFor(MinecraftIds.userIdOf(mc.player));
                    if (next > listing) mine = " Yours would be " + next + ".";
                }
                y = wrapped(m, "Climbs above " + freeOrders + " orders held open: the"
                        + " next costs double, the one after triple. Cancel one and it"
                        + " falls again." + mine, x, y, 0x909090);
            }
        }

        long stipend = market.stipendAmount();
        if (stipend > 0) {
            MinecraftClient mc = MinecraftClient.getInstance();
            // Only speak of "yours" to somebody who has one. An unregistered viewer has
            // no last claim on record, which reads as fill zero — so in any market that
            // had traded at all the countdown came out negative and the line announced a
            // payment waiting, next to a Claim button stipendClaimable() will never show
            // them. Registration is the same test the button uses.
            String when = "";
            if (mc.player != null) {
                UUID me = MinecraftIds.userIdOf(mc.player);
                if (market.isRegistered(me)) {
                    long owedIn = market.stipendEveryFills()
                            - (market.fillsEver() - market.stipendedAtFill(me));
                    when = owedIn <= 0 ? ", and yours is waiting"
                            : ", yours in " + owedIn + " more";
                }
            }
            y = wrapped(m, "Stipend: " + stipend + " credits every "
                    + market.stipendEveryFills() + " trades this market settles" + when,
                    x, y, 0x88FF88);
        }

        // Says who can change it, to whoever cannot. Without this the fee reads as a
        // property of the software rather than a decision somebody made, and the
        // absence of any control to change it looks like an omission.
        if (!amCreator()) {
            y = wrapped(m, "Set by whoever created this market.", x, y, 0x707070);
        }
        return y;
    }

    /**
     * Adds a market to this world and moves to it.
     *
     * Confirmed, but lightly: nothing is destroyed and the market being left keeps
     * everything, which is the whole point. What is worth saying is where you end up,
     * because landing on an empty Market screen having pressed a button labelled "add"
     * would otherwise look like it had failed.
     */
    private void onAddMarket() {
        showConfirm("Add another market to this world?",
                "This world can hold several markets and use one at a time. The one you"
                        + " are in keeps its history, balances and orders, and you can"
                        + " switch back whenever you like. The new one starts empty:"
                        + " create it, import one, or connect to somebody hosting.",
                "Add", () -> {
                    if (MarketStateHolder.addMarketSlot()) {
                        status = "Now using '" + MarketStateHolder.activeSlot()
                                + "' — empty until you create or join a market";
                        refreshMarketButtons();
                    }
                });
    }

    /**
     * Removes the market in use from this world.
     *
     * DANGER rather than CONFIRM, and worded around what is lost rather than what is
     * removed: this destroys a history that cannot be recovered from anywhere unless
     * somebody else is holding it, which is the same thing Discard does and deserves
     * the same treatment.
     */
    private void onDeleteMarket() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        UUID me = MinecraftIds.userIdOf(mc.player);

        String slot = MarketStateHolder.activeSlot();
        String name = MarketStateHolder.slotMarketName(slot);

        showDanger("Remove " + (name == null ? "this empty market" : "'" + name + "'")
                        + " from this world?",
                "You would lose " + MarketStateHolder.describeLoss(me) + ", and this"
                        + " world's copy of its history goes with it. If somebody else"
                        + " still hosts this market you can join them again and get"
                        + " everything back; if nobody does, it is gone. Your other"
                        + " markets in this world are untouched.",
                "Remove", () -> {
                    if (MarketStateHolder.deleteActiveMarketSlot()) {
                        status = "Removed — back on the first market in this world";
                        refreshMarketButtons();
                    }
                });
    }

    private static final int SLOT_ROW_H = 11;

    /** Top of one market row. The single source for drawing and for hit-testing it. */
    private int slotRowY(int index, int top) {
        return top + 12 + index * SLOT_ROW_H;
    }

    /** Where the slot list starts, remembered from the last frame for the hit test. */
    private int slotListTop = -1;

    /**
     * The other markets this world holds, and which one is in use.
     *
     * Shown only when there is more than one, because a world with a single market has
     * nothing to choose between and a list of one reads as a setting somebody forgot to
     * finish. Switching is not destructive — the market being left keeps its own log,
     * its own high-water mark and its own everything — which is the point of the
     * feature: leaving a market no longer has to mean destroying it.
     */
    private int renderMarketSlots(MatrixStack m, int x, int y) {
        List<String> slots = MarketStateHolder.availableSlots();
        if (slots.size() < 2) { slotListTop = -1; return y; }
        // The "no room, give up" guard that used to be here is gone. It was the reason
        // the switcher disappeared whenever the prose above it grew — and this is the
        // only control in the column, so it was the one thing that had to survive. The
        // column scrolls now; being below the fold means being scrolled to.

        slotListTop = y;
        guideLabel(m, "Markets in this world — click to switch", x, y, 0xFFDD66);

        String active = MarketStateHolder.activeSlot();
        for (int i = 0; i < slots.size(); i++) {
            int rowY = slotRowY(i, y);

            String slot = slots.get(i);
            boolean here = slot.equalsIgnoreCase(active);

            // What the market calls itself, not the folder it happens to sit in — a
            // list of "market-2" and "market-3" says nothing about which is which.
            String name = MarketStateHolder.slotMarketName(slot);
            String shown = name != null ? name : slot + " (empty)";

            if (!slotRowVisible(rowY)) continue;
            label(m, trim((here ? "> " : "  ") + shown, listW - 12), x, rowY,
                    here ? 0xFFFFFF : 0x88CCFF);
        }

        // The bottom of the last row, so the column's height includes this list. Left
        // out, the scroll would stop at the heading above it and the rows below could
        // never be reached — which is the fault this whole change is about, moved down
        // by one element.
        return slotRowY(slots.size() - 1, y) + SLOT_ROW_H;
    }

    /**
     * Whether a market row is actually on screen.
     *
     * Asked by the drawing and by the hit test, which is the whole point of it existing.
     * Now that the column scrolls, a row can sit above or below the panel while its
     * position is still perfectly computable — and a row you cannot see that still
     * switches your market when clicked is the exact defect this project keeps finding.
     * One question, one answer, two callers.
     */
    private boolean slotRowVisible(int rowY) {
        return rowY >= panelTop() && rowY + SLOT_ROW_H <= panelBottom();
    }

    /** True when the click landed on a market row and was acted on. */
    private boolean slotClicked(double mouseX, double mouseY) {
        if (slotListTop < 0 || activeScreen != SCREEN_MARKET) return false;

        List<String> slots = MarketStateHolder.availableSlots();
        if (slots.size() < 2) return false;

        for (int i = 0; i < slots.size(); i++) {
            int rowY = slotRowY(i, slotListTop);
            if (!slotRowVisible(rowY)) continue;      // scrolled away is not clickable
            if (mouseX >= listX && mouseX < listX + listW
                    && mouseY >= rowY && mouseY < rowY + SLOT_ROW_H) {
                String slot = slots.get(i);
                if (slot.equalsIgnoreCase(MarketStateHolder.activeSlot())) return true;
                if (MarketStateHolder.switchTo(slot)) {
                    status = "Now using '" + slot + "'";
                    refreshMarketButtons();
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Draws text across as many lines as the panel needs, returning the next free y.
     *
     * These are sentences, not row labels — trimming one leaves it saying something
     * other than what it means, and "Trading fee: 2.5% of each sale, taken from…" is
     * exactly the half that must not be cut. Rows in a clickable list stay trimmed
     * instead, because wrapping those changes their height and their hit test with it.
     */
    private int wrapped(MatrixStack m, String text, int x, int y, int colour) {
        for (OrderedText line : this.textRenderer.wrapLines(
                new LiteralText(text), listW - 12)) {
            // Advances whether or not it draws. Returning early here stopped y where the
            // panel ended, so the column's measured height was the height of the visible
            // part — which is a scroll extent that can never reach what it is hiding.
            // guideLine decides what is on screen; this only decides where things go.
            guideLine(m, line, x, y, colour);
            y += 10;
        }
        return y;
    }

    /** 250 reads as "2.5%", 100 as "1%". Trailing ".0" is noise on a fee. */
    private static String formatBps(int bps) {
        if (bps % 100 == 0) return (bps / 100) + "%";
        return String.format("%.2f%%", bps / 100.0).replace(".00", "");
    }

    /**
     * Orders carried over from a migrated market, waiting to be re-placed.
     *
     * Shown with the destination's current price beside each one. The prices you set in
     * a dead economy mean nothing here, and the difference is usually invisible until
     * after you've clicked — so it goes on the row.
     */
    /**
     * The carried-over orders, in a box of their own.
     *
     * Previously drawn as bare labels at the left panel's first row, which is where the
     * active tab had already put its own content — on the Market tab, the one you are
     * necessarily looking at right after a migration, it landed directly on top of the
     * guidance text. The rows also ran their full untrimmed width, so a long item name
     * plus a "best bid here" suffix spilled clear across the middle and right columns.
     *
     * A framed box fixes both: it owns its area rather than sharing it, and its width
     * is what the rows are trimmed to. Icons because an item is quicker to recognise
     * by its sprite than by the tail of a registry id.
     */
    private static final int REPLACE_ROW_H = 20;

    /**
     * The third column when there is one, the left panel when there is not.
     *
     * Deliberately not a function of the active tab. It used to fall back to the left
     * panel on Trading, on the reasoning that Trading is the one screen whose third
     * column is already occupied. What that produced was a box that jumped across the
     * screen on every tab change while it existed, which reads as a rendering fault
     * rather than as a considered layout — and the list outlives any one tab, so it is
     * the thing that has to stay still.
     *
     * It draws after every panel, so on Trading it covers the inventory rather than
     * fighting it. That is the right one to cover: re-placing an order needs its price
     * and volume, both of which are on the row itself.
     *
     * Still switches on width, because a window too narrow for a third column has no
     * third column to sit in — but that is a resize, where things are expected to move.
     */
    private boolean replaceInSideColumn() {
        return invX >= 0;
    }

    /**
     * Whether the re-place box is sitting on the left panel and hiding what is there.
     *
     * The one place that decides it, because rendering and hit-testing have to agree.
     * They did not: discovery stopped drawing whenever any orders were waiting, while
     * its click handler went on testing rows regardless, so the host list vanished and
     * stayed clickable. Only ever true on a window too narrow for a third column.
     */
    private boolean replaceCoversLeftPanel() {
        return !MarketStateHolder.pendingReplace().isEmpty() && !replaceInSideColumn();
    }

    private int replaceBoxX() { return replaceInSideColumn() ? invX : listX; }

    private int replaceBoxW() { return replaceInSideColumn() ? invW : listW; }

    /**
     * Flush with the other columns, not inset and sized to its contents.
     *
     * It sat at panelTop with a height derived from the row count, which made it a
     * short box floating inside the frame line the panels beside it share. Matching
     * frameTop/frameH is what makes it read as the third column rather than as
     * something dropped on top of one.
     */
    private int replaceBoxTop() { return frameTop(); }

    private int replaceBoxH() { return frameH(); }

    /** The first row's top, below the header that doubles as dismiss. */
    private int replaceListTop() {
        return replaceBoxTop() + 8 + DISCOVERY_ROW_HEIGHT + 4;
    }

    /** Where rows stop being drawn — the panel's own bottom, not the frame's. */
    private int replaceListBottom() {
        return Math.min(replaceBoxTop() + replaceBoxH() - 4, panelBottom());
    }

    /**
     * Where row i sits, scroll included.
     *
     * The scroll is subtracted here rather than at each caller because the render and
     * the hit test both come through this method, and that is the only reason they have
     * never disagreed about where a row is. Nine orders after a reset was the first time
     * the list outgrew its box: rows past the bottom were drawn nowhere and reachable by
     * nothing, and the list is the only record of what to put back.
     *
     * Fourth time in this file — the Market column, the market switcher, the reset
     * overlay, and now this. All four stacked content downwards with no bound and lost
     * the far end of it. The session log's note after the second one said the next thing
     * that stacks without a scroll would be next; it was.
     */
    private int replaceRowY(int index) {
        return replaceListTop() + index * REPLACE_ROW_H - scrollOf("replace");
    }

    /**
     * Whether a row at this y is on screen, asked by the drawing and the hit test both.
     *
     * A row that cannot be seen but still re-places an order when clicked is the defect
     * this file keeps producing, and scrolling creates it at the top as well as the
     * bottom — the earlier version only ever ran out of room downwards.
     */
    private boolean replaceRowVisible(int y) {
        return y >= replaceListTop() && y + REPLACE_ROW_H <= replaceListBottom();
    }

    private void renderReplaceList(MatrixStack matrices, int mouseX, int mouseY) {
        List<MarketStateHolder.OldOrder> old = MarketStateHolder.pendingReplace();
        if (old.isEmpty()) return;

        int boxX = replaceBoxX();
        int boxW = replaceBoxW();
        vanillaPanel(matrices, boxX, replaceBoxTop(), boxW, replaceBoxH());

        int textX = boxX + 26;
        int textW = boxW - 32;

        // The title row doubles as dismiss, so it carries a close mark rather than
        // spelling that out — the column is too narrow for the sentence it replaced.
        // Narrow enough that even the short form is cut, hence the hover: "click to
        // re-…" does not tell you what clicking does.
        String title = "Old orders — click to re-place, or this row to dismiss";
        String titleShown = trim("Old orders — click to re-place", boxW - 24);
        label(matrices, titleShown, boxX + 6, replaceBoxTop() + 8, 0xFFDD66);
        label(matrices, "x", boxX + boxW - 12, replaceBoxTop() + 8, 0xFFDD66);
        tipIfHovered(title, titleShown, boxX, replaceBoxTop() + 4, boxW,
                DISCOVERY_ROW_HEIGHT + 4, mouseX, mouseY);

        MarketState s = MarketStateHolder.get();
        List<MarketOldRow> rows = replaceRows();

        // The wheel is worth catching anywhere over the box; the rows are clipped to
        // the narrower band between the header and the panel bottom. Those are two
        // rectangles, which is the distinction noteScrollable was given a viewH for
        // after measuring the Market column against the wrong one left its last row
        // unreachable at every offset.
        noteScrollable("replace", boxX, replaceBoxTop(), boxW, replaceBoxH(),
                replaceListBottom() - replaceListTop(),
                rows.size() * REPLACE_ROW_H, mouseX, mouseY);

        for (int i = 0; i < rows.size(); i++) {
            MarketStateHolder.OldOrder o = rows.get(i).order;
            int y = replaceRowY(i);
            if (!replaceRowVisible(y)) continue;

            drawIcon(matrices, new ItemStack(MinecraftIds.idToItem(o.itemId)),
                    boxX + 6, y, "");

            // Trimmed with the rest on hover, like the host rows: an item name is as
            // long as its name and the column is as wide as the column, and re-placing
            // the wrong order because the name was cut is not a recoverable mistake.
            String full = (o.isBid ? "Buy " : "Sell ") + o.volume + " "
                    + shortItem(o.itemId) + " @ " + o.price;
            String shown = trim(full, textW);
            label(matrices, shown, textX, y + 1, 0x88CCFF);
            tipIfHovered(full, shown, boxX, y, boxW, REPLACE_ROW_H, mouseX, mouseY);

            // What the same order would meet here, so re-placing at the old price is a
            // decision rather than a guess.
            // peekBook, not bookFor. bookFor creates the book it fails to find, so
            // asking it from render quietly filled the map with empty books for items
            // nobody has traded — and did it from the render thread, writing to a map
            // the applier thread was also writing to. Its own javadoc says as much.
            String here = "";
            OrderBook peeked = s == null ? null : s.peekBook(o.itemId);
            if (peeked != null) {
                List<Order> book = o.isBid
                        ? peeked.restingAsks()
                        : peeked.restingBids();
                if (!book.isEmpty()) {
                    here = (o.isBid ? "best ask " : "best bid ") + book.get(0).value();
                }
            }
            if (!here.isEmpty()) {
                label(matrices, trim(here, textW), textX, y + 11, 0x808080);
            }
        }
    }

    private static String shortItem(String itemId) {
        int colon = itemId.indexOf(':');
        return colon < 0 ? itemId : itemId.substring(colon + 1);
    }

    /** Wrapper so the render and hit-test iterate the same thing. */
    private static class MarketOldRow {
        final MarketStateHolder.OldOrder order;
        MarketOldRow(MarketStateHolder.OldOrder order) { this.order = order; }
    }

    /**
     * Re-places one carried-over order at its original price.
     *
     * A plain PlaceOrder — the items and credits are already in the ledger from the
     * migration, so there is nothing to deposit and no special path for this.
     */
    private void replaceOrder(MarketStateHolder.OldOrder o) {
        if (!requireConnected()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        Event.PlaceOrder p = new Event.PlaceOrder();
        p.userId = MinecraftIds.userIdOf(mc.player);
        p.itemId = o.itemId;
        p.price = o.price;
        p.volume = o.volume;
        p.isBid = o.isBid;
        p.timestamp = System.currentTimeMillis();

        MarketStateHolder.pendingReplace().remove(o);
        report(MarketStateHolder.submit(p),
                "Re-placed " + o.volume + " " + shortItem(o.itemId) + " @ " + o.price,
                "Re-placing...");
    }

    private List<MarketOldRow> replaceRows() {
        List<MarketOldRow> rows = new ArrayList<>();
        for (MarketStateHolder.OldOrder o : MarketStateHolder.pendingReplace()) {
            rows.add(new MarketOldRow(o));
        }
        return rows;
    }

    /**
     * The y is taken from discoveryStartY rather than passed in, so it cannot drift
     * from the hit test that reads the same function — which is exactly how clicking a
     * host came to do nothing.
     */
    private void renderDiscovery(MatrixStack matrices, int px,
                                 double mouseX, double mouseY) {
        int py = discoveryStartY();
        // Only when the re-place box is genuinely on top of this. It used to stand
        // aside whenever the box existed at all, from when the box lived in the left
        // panel — but it sits in the third column wherever there is one, so the host
        // list was being hidden by something that was not covering it. The clicks kept
        // landing, which made the rows invisible and still joinable.
        if (replaceCoversLeftPanel()) return;

        int x = px;
        int y = py;

        // No running counter: it re-polls every 10s, so the age is almost always
        // uninteresting and a ticking number just pulls the eye. Say something only
        // when the list has actually gone stale, which now means polling is failing.
        boolean stale = !polling && lastPollAt != 0 && !pollIsFresh();
        label(matrices, stale ? "Hosts: (out of date)" : "Hosts:",
                x, y, stale ? 0xAA8844 : 0xFFFFFF);
        y += DISCOVERY_ROW_HEIGHT + 2;

        // Says why Host is dead, next to the list that explains it. A greyed control
        // with no reason beside it teaches nothing — it reads as broken, and somebody
        // who wants to host goes looking for a way around it rather than understanding
        // that there is nothing to work around.
        if (dedicatedServesThisMarket()) {
            // Trimmed with the rest on hover, the same as the host rows below it, and
            // for the same reason: this panel's width follows the window, so any fixed
            // string is one small window away from running off the edge — which is what
            // this line did on its first outing, stopping mid-word at "conn". Short
            // enough to survive the trim at a usable width, and complete on hover.
            String note = "a dedicated server has this market — connect, don't host";
            String shown = trim("  " + note, listW - 12);
            label(matrices, shown, x, y, 0xFFAA55);
            tipIfHovered(note, shown, x, y, listW - 8, DISCOVERY_ROW_HEIGHT,
                    (int) mouseX, (int) mouseY);
            y += DISCOVERY_ROW_HEIGHT + 2;
        }

        if (polling) {
            label(matrices, "  searching...", x, y, 0x808080);
            return;
        }

        List<PeerPoll.HostInfo> hosts = discovered;
        if (hosts.isEmpty()) {
            label(matrices, "  nobody hosting", x, y, 0x808080);
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        String myUuid = mc.player != null
                ? MinecraftIds.userIdOf(mc.player).toString() : null;

        MarketState mine = MarketStateHolder.get();
        String myMarket = mine != null && mine.marketId() != null
                ? mine.marketId().toString() : null;

        for (int i = 0; i < hosts.size(); i++) {
            PeerPoll.HostInfo h = hosts.get(i);
            // From discoveryRowY, not from a cursor walked down alongside the click
            // test's own copy of the same walk.
            y = discoveryRowY(i);
            boolean isSelf = h.reply.userId != null && h.reply.userId.equals(myUuid);
            // Whether this host serves the market we hold decides whether clicking it
            // will work at all, so it belongs on the row rather than in a failure later.
            boolean joinable = myMarket == null || myMarket.equals(h.reply.marketId);
            String marketLabel = h.reply.marketName != null ? h.reply.marketName : "unnamed";

            // A dedicated host is one that will still be there tomorrow and needs
            // nobody to take a turn hosting. That is the only thing the two modes
            // differ on from here, and it is worth knowing before choosing rather
            // than after connecting.
            String line = "  " + h.reply.hostName
                    + (isSelf ? " (you)" : "")
                    + (h.reply.dedicated ? " [server]" : "")
                    + "  [" + marketLabel + "]"
                    + (joinable ? "" : " (different market)")
                    + "  (" + h.reply.lastSeq + " events, "
                    + h.reply.clientCount + " online)";

            // Trimmed to the panel, with the remainder on hover. Rows stay one line
            // each so the click test keeps measuring in fixed steps — wrapping them
            // would make row heights variable, which is what every drifted hit test in
            // this file has had in common.
            int colour = isSelf ? 0xAAAAAA : (joinable ? 0x88CCFF : 0x996666);
            String shown = trim(line, listW - 12);
            label(matrices, shown, x, y, colour);
            tipIfHovered(line.trim(), shown, x, y, listW - 8, DISCOVERY_ROW_HEIGHT,
                    (int) mouseX, (int) mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // An overlay is modal — nothing behind it is reachable until it's answered.
        if (!overlays.isEmpty()) {
            return button == 0 ? overlayClicked(mouseX, mouseY) : true;
        }

        // The picker is modal, like an overlay.
        if (pickerOpen) {
            return button != 0 || pickerClicked(mouseX, mouseY);
        }

        // The nav sits above everything else, so it gets first refusal on a click.
        if (button == 0 && tabsClicked(mouseX, mouseY)) return true;

        // Drawn over the panels, so it claims clicks ahead of them too.
        if (button == 0 && alertClicked(mouseX, mouseY)) return true;

        if (button == 0 && leftSwitcherClicked(mouseX, mouseY)) return true;

        // Picking from what you're carrying is the fastest way to choose what to sell,
        // and it never involves knowing an item's registry id.
        if (button == 0) {
            InventoryBridge.Holding held = inventoryRowAt(mouseX, mouseY);
            if (held != null) {
                selectItem(held.item);
                return true;
            }
            String row = marketRowAt(mouseX, mouseY);
            if (row != null) {
                selectItem(MinecraftIds.idToItem(row));
                return true;
            }
        }

        // Any click acknowledges the recovery note — it describes something already
        // done, so it does not need to keep occupying a line.
        MarketStateHolder.clearRecoveryNote();

        // The re-place list occupies the discovery area while it exists, so it claims
        // clicks there first.
        if (button == 0 && !MarketStateHolder.pendingReplace().isEmpty()) {
            int boxTop = replaceBoxTop();
            int boxBottom = boxTop + replaceBoxH();
            boolean inBox = mouseX >= replaceBoxX()
                    && mouseX < replaceBoxX() + replaceBoxW()
                    && mouseY >= boxTop && mouseY < boxBottom;

            // Header doubles as dismiss — the list is a convenience, not an obligation.
            if (inBox && mouseY < boxTop + 8 + DISCOVERY_ROW_HEIGHT) {
                MarketStateHolder.clearPendingReplace();
                status = "Dismissed — your balance is unaffected";
                return true;
            }

            List<MarketOldRow> rows = replaceRows();
            for (int i = 0; i < rows.size(); i++) {
                int y = replaceRowY(i);
                if (!replaceRowVisible(y)) continue;
                if (inBox && mouseY >= y && mouseY < y + REPLACE_ROW_H) {
                    replaceOrder(rows.get(i).order);
                    return true;
                }
            }

            // Swallow only clicks that actually landed on the box. This used to
            // return unconditionally, which meant that while any orders were waiting
            // to be re-placed — i.e. immediately after every migration — no button or
            // text field anywhere on the screen could be clicked at all.
            if (inBox) return true;
        }

        // Before the host list, and guarded to the Market tab: the two lists both live
        // in the left panel and only one of them is ever drawn.
        if (button == 0 && slotClicked(mouseX, mouseY)) return true;

        // Only where the host list is actually drawn — same condition renderDiscovery
        // uses, including the re-place box covering it. Hit-testing a row nobody can
        // see joins a host by accident, which is what happened here.
        if (button == 0 && !polling && activeScreen == SCREEN_NETWORK
                && !replaceCoversLeftPanel()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            String myUuid = mc.player != null
                    ? MinecraftIds.userIdOf(mc.player).toString() : null;

            int x = listX + 4;
            List<PeerPoll.HostInfo> hosts = discovered;

            for (int i = 0; i < hosts.size(); i++) {
                PeerPoll.HostInfo h = hosts.get(i);
                int y = discoveryRowY(i);      // the same function the drawing uses
                boolean isSelf = h.reply.userId != null && h.reply.userId.equals(myUuid);
                if (!isSelf && mouseX >= x && mouseX <= x + listW - 8
                        && mouseY >= y && mouseY < y + DISCOVERY_ROW_HEIGHT) {
                    joinHost(h);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void joinHost(PeerPoll.HostInfo h) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        try {
            MarketStateHolder.setMyHostPort(Integer.parseInt(hostPortField.getText().trim()));
        } catch (NumberFormatException ignored) { }

        UUID me = MinecraftIds.userIdOf(mc.player);
        String myName = mc.getSession().getUsername();
        String addr = h.peer.address;
        int port = h.peer.port;
        String hostLabel = h.reply.hostName;

        status = "Connecting to " + hostLabel + "...";
        new Thread(() -> {
            MarketStateHolder.connect(addr, port, me, myName);
            if (MarketStateHolder.isConnected()) {
                status = "Connected to " + hostLabel;
            }
        }, "market-connect").start();
    }

    // ─────────── actions ───────────

    private OrderRequest parseForm() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) { status = "No player"; return null; }

        long qty, price;
        try {
            qty = Long.parseLong(amountField.getText().trim());
            price = Long.parseLong(priceField.getText().trim());
        } catch (NumberFormatException e) {
            status = "Amount and price must be whole numbers";
            return null;
        }
        if (qty <= 0 || price <= 0) { status = "Amount and price must be positive"; return null; }
        if (qty > MAX_QTY) { status = "Amount too large (max " + MAX_QTY + ")"; return null; }

        Item item = MinecraftIds.itemFromName(itemField.getText().trim());
        if (item == Items.AIR) { status = "Unknown item (try minecraft:iron_ingot)"; return null; }

        OrderRequest req = new OrderRequest();
        req.item = item;
        req.itemId = MinecraftIds.itemToId(item);
        req.userId = MinecraftIds.userIdOf(mc.player);
        req.qty = qty;
        req.price = price;
        return req;
    }

    private void onSell() {
        if (!requireConnected()) return;
        OrderRequest req = parseForm();
        if (req == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();

        // Asked before the items leave the inventory, and asked of the same function the
        // host will ask. A seller who cannot afford the listing fee used to find out
        // only after their goods had been taken and proposed — the refusal came back,
        // the items came back with it, and the round trip existed for no reason. The
        // host still decides; this only saves taking something it is going to refuse.
        MarketState here = MarketStateHolder.get();
        if (here != null) {
            MarketState.SubmitResult listable =
                    here.canDepositAndList(req.userId, req.itemId, req.qty, req.price);
            if (!listable.accepted) {
                status = "Cannot list: " + listable.reason;
                return;
            }
        }

        // Journal the operation BEFORE the items leave, keyed by the id the event will
        // carry. Removing first and proposing second is deliberate — never credit before
        // removing — but it leaves a window where a crash loses the items with nothing
        // anywhere recording they were owed. This entry is that record; the log settles
        // it on the next start.
        String clientEventId = UUID.randomUUID().toString();
        PendingOps journal = MarketStateHolder.pendingOps();
        if (journal != null) {
            journal.recordDeposit(req.userId, clientEventId, req.itemId, req.qty);
        }

        // Remove physical items FIRST — never credit before removing.
        if (!InventoryBridge.remove(mc.player, req.item, (int) req.qty)) {
            if (journal != null) journal.clearDeposit(clientEventId);
            status = "You don't have " + req.qty + " of that";
            return;
        }

        // Deposit and list as one atomic event — two separate proposals could be
        // interleaved, leaving items deposited but never listed.
        Event.DepositAndList e = new Event.DepositAndList();
        e.userId = req.userId;
        e.itemId = req.itemId;
        e.quantity = req.qty;
        e.price = req.price;
        e.timestamp = System.currentTimeMillis();
        e.clientEventId = clientEventId;

        // Read before submitting. In LOCAL mode the event applies synchronously, so
        // asking afterwards would describe a book this order has already changed.
        String outlook = outlookFor(req.itemId, req.price, req.qty, false);

        MarketStateHolder.Submission s = MarketStateHolder.submit(e);
        // A submission that fails outright never becomes an event, so nothing will ever
        // clear this entry — settle it here rather than leaving a false refund waiting.
        if (journal != null && !s.pending && !s.accepted) {
            journal.clearDeposit(clientEventId);
            if (!InventoryBridge.give(mc.player, req.item, (int) req.qty)) {
                // Cleared, then not handed back. Put the record in again so the journal
                // is what it claims to be — the note of something owed — rather than
                // nothing at all. Give refuses before touching an inventory, so this
                // cannot pay twice.
                journal.recordDeposit(MinecraftIds.userIdOf(mc.player), clientEventId,
                        req.itemId, req.qty);
                status = "Could not hand those back yet — still recorded as owed";
            }
        }
        report(s, "Listed " + req.qty + " at " + req.price + outlook,
                "Sell sent" + outlook);
    }

    private void onBuy() {
        if (!requireConnected()) return;
        OrderRequest req = parseForm();
        if (req == null) return;

        Event.PlaceOrder order = new Event.PlaceOrder();
        order.userId = req.userId;
        order.itemId = req.itemId;
        order.price = req.price;
        order.volume = req.qty;
        order.isBid = true;
        order.timestamp = System.currentTimeMillis();

        String outlook = outlookFor(req.itemId, req.price, req.qty, true);
        report(MarketStateHolder.submit(order),
                "Bid placed for " + req.qty + " at " + req.price + outlook,
                "Buy sent" + outlook);
    }

    private void onWithdraw() {
        if (!requireConnected()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) { status = "No player"; return; }

        long qty;
        try {
            qty = Long.parseLong(amountField.getText().trim());
        } catch (NumberFormatException e) {
            status = "Amount must be a whole number";
            return;
        }
        if (qty <= 0) { status = "Amount must be positive"; return; }
        if (qty > MAX_QTY) { status = "Amount too large"; return; }

        Item item = MinecraftIds.itemFromName(itemField.getText().trim());
        if (item == Items.AIR) { status = "Unknown item"; return; }

        Event.Withdraw w = new Event.Withdraw();
        w.userId = MinecraftIds.userIdOf(mc.player);
        w.itemId = MinecraftIds.itemToId(item);
        w.quantity = qty;
        w.timestamp = System.currentTimeMillis();

        MarketStateHolder.Submission s = MarketStateHolder.submit(w);
        if (s.pending) {
            status = "Withdraw sent...";
        } else if (s.accepted) {
            status = "Withdrew " + qty;
        } else {
            status = "Rejected: " + s.reason;
        }
    }

    private void onCancel() {
        if (!requireConnected()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        long orderId;
        try {
            orderId = Long.parseLong(cancelField.getText().trim().replace("#", ""));
        } catch (NumberFormatException e) {
            status = "Order ID must be a number";
            return;
        }

        Item item = MinecraftIds.itemFromName(itemField.getText().trim());
        if (item == Items.AIR) { status = "Unknown item"; return; }

        // The event needs the side; find the order to work out which book it's in.
        // peekBook: an item with no book has no order to cancel, and creating one to
        // discover that writes to the market from a keypress.
        String itemId = MinecraftIds.itemToId(item);
        OrderBook book = MarketStateHolder.get().peekBook(itemId);
        if (book == null) { status = "No such order in this item's book"; return; }
        Order o = book.find(orderId, true);
        boolean isBid = o != null;
        if (o == null) o = book.find(orderId, false);
        if (o == null) { status = "No such order in this item's book"; return; }

        Event.CancelOrder c = new Event.CancelOrder();
        c.userId = MinecraftIds.userIdOf(mc.player);
        c.itemId = itemId;
        c.orderId = orderId;
        c.isBid = isBid;
        c.timestamp = System.currentTimeMillis();

        report(MarketStateHolder.submit(c), "Cancelled #" + orderId, "Cancel sent...");
    }

    private void report(MarketStateHolder.Submission s, String successMsg, String pendingMsg) {
        if (s.pending) {
            status = pendingMsg;
        } else if (s.accepted) {
            status = s.result.fills.isEmpty()
                    ? successMsg
                    : "Traded: " + s.result.fills.size() + " fill(s)";
        } else {
            status = "Rejected: " + s.reason;
        }
    }

    /**
     * What an order is about to do, said before it is sent.
     *
     * Connected and hosting both answer "pending" and never come back, so the status
     * line stops at "Sell sent..." whatever happens next. That reads identically for an
     * order that traded and one that will sit in the book for a week, which is how
     * somebody ends up watching their credits and wondering whether the market is
     * broken.
     *
     * A prediction, not a promise: the host sequences, and the book here may be a moment
     * stale. Worded as an expectation for that reason. It is drawn from the same replica
     * the order book on screen is drawn from, so it can never contradict what the player
     * is looking at.
     */
    private String outlookFor(String itemId, long price, long qty, boolean isBid) {
        MarketState market = MarketStateHolder.get();
        if (market == null) return "";

        OrderBook book = market.peekBook(itemId);
        if (book == null) return ". Nothing on the other side yet, so it waits";

        List<Order> other = isBid ? book.restingAsks() : book.restingBids();
        if (other.isEmpty()) {
            return isBid
                    ? ". Nobody is selling yet, so it waits"
                    : ". Nobody is buying yet, so it waits";
        }

        long best = other.get(0).value();
        boolean crosses = isBid ? best <= price : best >= price;
        if (crosses) return ". Should trade now";

        return isBid
                ? ". Best ask is " + best + ", so it waits until somebody sells at "
                        + price + " or less"
                : ". Best bid is " + best + ", so it waits until somebody buys at "
                        + price + " or more";
    }

    // ─────────── rendering ───────────

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);

        // Whether you hold a market changes from the network thread — connecting adopts
        // one, disconnecting or resetting can drop it. Recompute here rather than only
        // in init(), or the buttons stay wrong until the screen is reopened.
        // A dropped host is noticed on a network thread; fold it into the mode here so
        // the rest of the UI isn't reading state from a connection that's gone.
        MarketStateHolder.pollConnection();

        // Before anything positions a widget. The band decides where the panels start,
        // and a layout computed from last frame's answer is a layout that disagrees with
        // what is about to be drawn.
        frameAlerts = alerts();
        reflowForAlerts();

        refreshMarketButtons();

        // Re-poll on a timer. Without this the host list only ever reflects the moment
        // the screen was opened, so a host that has since stopped still looks live.
        if (!polling && System.currentTimeMillis() - lastPollAt > POLL_INTERVAL_MS) {
            startPoll();
        }

        // Cleared before the panels re-register, or a region the cursor has since left
        // keeps eating the scroll wheel.
        hoveredScrollKey = null;

        renderHeader(matrices);

        int listX = this.listX;

        // Frames around each column. Without them the controls float in the middle of
        // an empty screen with nothing saying where one grouping ends and the next
        // begins — the panels are most of what makes the layout read as a layout.
        if (activeScreen != SCREEN_HOME) {
            vanillaPanel(matrices, listX, frameTop(), listW, frameH());
            vanillaPanel(matrices, rowX - PAD, frameTop(), controlsW + PAD * 2, frameH());
            if (invX >= 0 && activeScreen == SCREEN_TRADING) {
                vanillaPanel(matrices, invX, frameTop(), invW, frameH());
            }
        }

        // Only the current destination's left column, and everything in it placed from
        // panelTop rather than from the control rows beside it.
        if (activeScreen == SCREEN_TRADING) {
            renderSelectedItem(matrices, listX + 4, panelTop(), mouseX, mouseY);
            renderLeftSwitcher(matrices, mouseX, mouseY);
            int viewTop = panelTop() + 36;
            if (leftView == LEFT_MARKETS) {
                renderMarkets(matrices, listX + 4, viewTop, mouseX, mouseY);
            } else if (leftView == LEFT_CHART) {
                renderPriceChart(matrices, listX + 4, viewTop);
            } else {
                renderBook(matrices, listX + 4, viewTop, mouseX, mouseY);
            }
            if (invX >= 0) renderInventory(matrices, invX + 4, panelTop(), mouseX, mouseY);
        } else if (activeScreen == SCREEN_NETWORK) {
            // In the left panel, not stacked under the buttons — that put it outside
            // the frame drawn around the controls.
            renderDiscovery(matrices, listX + 4, mouseX, mouseY);
        } else if (activeScreen == SCREEN_MARKET) {
            renderMarketGuidance(matrices, listX + 4);
            // The guidance column, which stacks a heading, a paragraph, sometimes a
            // second one about a host on the network, this market's facts, and the
            // switcher for the other markets in this world. It used to drop whatever
            // did not fit — the switcher first, being last — so it scrolls now, on the
            // same terms as the controls column beside it: wheel caught over the frame,
            // extent measured against the panel's interior, because that is what the
            // drawing is clipped to.
            noteScrollable("marketguide", listX, frameTop(), listW, frameH(),
                    panelBottom() - panelTop(), marketGuideHeight, mouseX, mouseY);
            // The controls column, not the panel beside it. It holds more rows than the
            // frame can show once a creator has policy controls and there are markets to
            // switch between, and place() now hides what falls outside rather than
            // drawing past the bottom — so without this the hidden rows would be
            // unreachable rather than merely off screen.
            //
            // The wheel is caught over the whole frame; the extent is measured against
            // the panel's interior, because that is what place() clips a row to. The
            // two are not the same rectangle and saying so is the fix.
            noteScrollable("marketcol", rowX - PAD, frameTop(), controlsW + PAD * 2,
                    frameH(), panelBottom() - panelTop(), marketColumnHeight,
                    mouseX, mouseY);
        } else if (activeScreen == SCREEN_HOME) {
            renderHome(matrices);
        } else {
            renderSettingsPlaceholder(matrices);
        }

        // The re-place checklist belongs to whichever tab you are on: it appears right
        // after a migration and is the only thing you should be doing next.
        if (!MarketStateHolder.pendingReplace().isEmpty()) {
            renderReplaceList(matrices, mouseX, mouseY);
        }

        // After every panel on every destination, including Home's, so the current tab
        // can overlap the border below it and read as joined to the panel.
        renderTabs(matrices, mouseX, mouseY);

        // After the panels rather than before them. These used to be drawn straight
        // after the header, which put them underneath a panel frame that starts as high
        // as y=52 on a short window — the one message that must not be missed was the
        // one thing that could be covered.
        renderAlerts(matrices, mouseX, mouseY);

        // Shown the first time the screen is opened after a recovery, then dismissed by
        // any click — it explains something that already happened, so it only has to be
        // seen once.
        // Both are sentences and both are bounded only by the window — a refusal reason
        // carries its remedy, which is the longest text this screen produces.
        int footerW = this.width - listX - 8;

        String note = MarketStateHolder.recoveryNote();
        if (!note.isEmpty()) {
            label(matrices, trim(note, footerW), listX, this.height - 36, 0x88CCFF);
        }

        if (!status.isEmpty()) {
            label(matrices, trim(status, footerW), listX, this.height - 24, 0xFFDD66);
        }

        // Your identity is a file, and it doesn't follow you to a new computer. Moving
        // machines without it makes you a stranger to every market you were part of —
        // same username, same UUID, unrecognised key. Cheaper to say so than to debug.
        Path identity = MarketStateHolder.identityPath();
        if (identity != null) {
            label(matrices, trim("identity: config/" + identity.getFileName()
                            + "  — copy this to move computers, never share it", footerW),
                    listX, this.height - 12, 0x707070);
        }

        // The text fields are registered with addButton now, so super.render draws
        // them — and skips the ones belonging to a tab that isn't showing, which
        // hand-rolled render calls could not do.
        super.render(matrices, mouseX, mouseY, delta);

        // Dead last, after super.render and every panel. A tooltip drawn where it was
        // requested would be painted over by whatever came next, which is the whole
        // reason it is queued rather than drawn in place.
        if (hoverTip != null) {
            this.renderTooltip(matrices, new LiteralText(hoverTip), mouseX, mouseY);
            hoverTip = null;
        }

        renderPicker(matrices, mouseX, mouseY);

        Overlay overlay = overlays.peekFirst();
        if (overlay != null) renderOverlay(matrices, overlay, mouseX, mouseY);
    }

    // ─────────── alerts ───────────
    //
    // The guided Market screen exists to say what is wrong and what to do about it, but
    // you had to already suspect something was wrong to go and look at it. These are
    // drawn on every destination, and each one carries you to the screen that actually
    // answers it — which is not always Market: falling behind is fixed by connecting,
    // and connecting lives on Network.

    private static final int ALERT_ROW_H = 12;

    /**
     * A band above the tab row, spanning the whole content box.
     *
     * Sits exactly where the tabs would be with no alerts; frameTop pushes the tabs and
     * panels down to make room. That keeps it clear of the header at every window size,
     * which is what stopped it going here the first time, and it gets the full box
     * width rather than one column — the messages are sentences, and the left panel was
     * too narrow to hold one without the text running across the trade controls.
     */
    private int alertTop() { return rowY - 6 - TAB_H - 2; }

    /**
     * Height reserved at the top of every panel for the alerts, or 0 when there are
     * none. Read from the cached list rather than rebuilding it: panelTop is called
     * many times per frame and alerts() walks the holder's state to answer.
     */
    private int alertBandH() {
        return frameAlerts.isEmpty() ? 0 : frameAlerts.size() * ALERT_ROW_H + 6;
    }

    /**
     * This frame's alerts, refreshed once at the top of render().
     *
     * Cached so that layout, drawing and hit-testing all describe the same band — a
     * list rebuilt per call could change size between the render and the click that
     * follows it, which is the same class of bug as discoveryStartY's.
     */
    private List<Alert> frameAlerts = new ArrayList<>();

    /**
     * The alert band the widgets are currently laid out for.
     *
     * Every widget's y is set once in init(), from rowY, and rowY does not know about
     * alerts. The band moves frameTop — and therefore the panels and the tab row — so
     * without this the panels slid down when an alert appeared and the controls stayed
     * where they were, which on the Market tab put the buttons through the tabs.
     *
     * Shifting them by the difference keeps one layout rather than two: init still owns
     * where things sit relative to each other, and this owns only how far the whole
     * column has moved.
     */
    private int laidOutForBand = 0;

    private void reflowForAlerts() {
        int band = alertBandH();
        if (band == laidOutForBand) return;

        int delta = band - laidOutForBand;
        laidOutForBand = band;
        for (List<ClickableWidget> widgets : screenWidgets) {
            for (ClickableWidget w : widgets) w.y += delta;
        }
    }

    /**
     * Full text for a row the cursor is over, drawn at the very end of the frame.
     *
     * Queued rather than drawn where it is discovered: a tooltip painted mid-render is
     * covered by every panel that comes after it. Cleared each time it is drawn, so a
     * row that stops being hovered stops claiming the tip.
     */
    private String hoverTip;

    /**
     * Records the full text of a trimmed row when the cursor is on it.
     *
     * The alternative to trimming is wrapping, which for a list of one-line rows means
     * variable row heights — and every hit test in this file that has drifted from what
     * was drawn has drifted over exactly that. Keeping rows one line and putting the
     * remainder in a tooltip leaves the geometry fixed.
     */
    private void tipIfHovered(String full, String shown, int x, int y, int w, int h,
                              int mouseX, int mouseY) {
        if (full.equals(shown)) return;              // nothing was cut, nothing to say
        if (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h) {
            hoverTip = full;
        }
    }

    /** Something wrong with the market, and where the answer to it lives. */
    private static final class Alert {
        final String text;
        final int colour;
        /** A SCREEN_ constant, or -1 when there is nowhere useful to go. */
        final int target;

        Alert(String text, int colour, int target) {
            this.text = text;
            this.colour = colour;
            this.target = target;
        }
    }

    /**
     * Shown wherever a control needs a world of ours and there is not one.
     *
     * Short enough to survive the footer's trim, and it names the two places the mod does
     * work rather than only the place it does not — "singleplayer only" leaves somebody
     * playing with friends thinking it is useless to them, when Open to LAN is exactly
     * what they want.
     */
    static final String NO_WORLD =
            "Singleplayer and Open to LAN only — no market on somebody else's server";

    /** What is currently wrong, worst first. Empty when there is nothing to say. */
    private List<Alert> alerts() {
        List<Alert> out = new ArrayList<>();

        // Before everything, including a damaged log: on somebody else's server there is
        // no market here to be damaged, and every other line this method could produce
        // would be answering a question the player cannot act on. Said plainly and once,
        // because the alternative — which is what shipped until now — is a screen full of
        // live buttons, two of which crashed the game.
        if (!MarketStateHolder.hasOwnWorld()) {
            out.add(new Alert(NO_WORLD, 0xFFDD66, -1));
            return out;
        }

        if (MarketStateHolder.chainBrokenAt() != -1) {
            String why = MarketStateHolder.damageReason();
            out.add(new Alert("LOG UNUSABLE: " + (why == null ? "damaged" : why),
                    0xFF6666, SCREEN_MARKET));
            // A log that can't be read makes every other reading of it meaningless.
            return out;
        }

        long behind = MarketStateHolder.eventsBehind();
        if (behind > 0) {
            out.add(new Alert(behind + " events behind — connect to catch up",
                    0xFFAA55, SCREEN_NETWORK));
        }

        // Alongside the behind-warning rather than instead of it: they're different
        // problems and both can be true at once. Found passively by discovery, so this
        // can be showing before anyone has tried to connect.
        MarketStateHolder.Divergence split = MarketStateHolder.divergence();
        if (split != null) {
            out.add(new Alert("FORKED: " + split.describe(), 0xFF8844, SCREEN_MARKET));
        }

        return out;
    }

    private boolean alertLeadsSomewhere(Alert a) {
        return a.target >= 0 && a.target != activeScreen;
    }

    /**
     * The text as drawn — the hit region is measured from this, so it is computed once.
     *
     * The "open X" half is a hint about where the answer lives; the other half is the
     * problem itself. When both will not fit the panel the hint goes, rather than the
     * message being truncated into something that no longer says what is wrong. The
     * strip stays clickable either way.
     */
    private String alertText(Alert a) {
        if (!alertLeadsSomewhere(a)) return a.text;
        String full = a.text + "  — open " + SCREEN_NAMES[a.target];
        return this.textRenderer.getWidth(full) + 10 <= contentW - 8 ? full : a.text;
    }

    /**
     * Both the frame and the hit-test measure from here.
     *
     * Deliberately one helper taking the index, after a click handler and a renderer
     * once disagreed about whether a start offset already included a row height and
     * made an entire list unclickable.
     */
    /** Bounded by the content box, which is the whole width available up here. */
    private int[] alertRect(int index, String text) {
        int max = contentW - 8;
        return new int[]{listX + 4, alertTop() + index * ALERT_ROW_H,
                Math.min(this.textRenderer.getWidth(text) + 10, max), ALERT_ROW_H};
    }

    private void renderAlerts(MatrixStack m, int mouseX, int mouseY) {
        List<Alert> list = frameAlerts;
        for (int i = 0; i < list.size(); i++) {
            Alert a = list.get(i);
            String text = alertText(a);
            int[] r = alertRect(i, text);
            boolean hot = alertLeadsSomewhere(a)
                    && mouseX >= r[0] && mouseX < r[0] + r[2]
                    && mouseY >= r[1] && mouseY < r[1] + r[3];

            // A ground and an accent bar, so it reads as one object worth clicking
            // rather than a loose red line among the labels around it.
            fill(m, r[0], r[1], r[0] + r[2], r[1] + r[3], hot ? 0xC0000000 : 0x90000000);
            fill(m, r[0], r[1], r[0] + 2, r[1] + r[3], 0xFF000000 | a.colour);
            label(m, trim(text, r[2] - 10), r[0] + 6, r[1] + 2, a.colour);
        }
    }

    private boolean alertClicked(double mouseX, double mouseY) {
        List<Alert> list = frameAlerts;
        for (int i = 0; i < list.size(); i++) {
            Alert a = list.get(i);
            if (!alertLeadsSomewhere(a)) continue;
            int[] r = alertRect(i, alertText(a));
            if (mouseX >= r[0] && mouseX < r[0] + r[2]
                    && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                selectScreen(a.target);
                return true;
            }
        }
        return false;
    }

    private void renderConnectionStatus(MatrixStack matrices, int x, int y) {
        if (MarketStateHolder.mode() == MarketStateHolder.Mode.HOSTING) {
            label(matrices, "● hosting", x, y, 0xFFDD66);
        } else if (MarketStateHolder.isConnected()) {
            label(matrices, "● connected to host", x, y, 0x88FF88);
        } else if (MarketStateHolder.mode() == MarketStateHolder.Mode.CONNECTED) {
            label(matrices, "● connection lost", x, y, 0xFF8888);
        } else {
            label(matrices, "● market closed — not connected", x, y, 0xAAAAAA);
        }
    }

    // ─────────── the left panel ───────────
    //
    // One column, several things worth looking at while trading. Which one is showing
    // is its own choice, separate from where you are in the app — you stay on Trading
    // either way, you are just looking at a different aspect of it.

    private static final int LEFT_BOOK = 0;
    private static final int LEFT_MARKETS = 1;
    private static final int LEFT_CHART = 2;
    private static final String[] LEFT_VIEW_NAMES = {"Order book", "Markets", "Price"};

    /** Static, like the destination: the view you chose is a preference, not a mode. */
    private static int leftView = LEFT_BOOK;

    private int[] leftSwitcherRect() {
        return new int[]{listX + 4, panelTop() + 22, listW - 8, 12};
    }

    private void renderLeftSwitcher(MatrixStack m, int mouseX, int mouseY) {
        int[] r = leftSwitcherRect();
        boolean hot = within(mouseX, mouseY, r);

        // The three-line glyph, as on the mockup — the same affordance as the main nav,
        // meaning the same thing one level down.
        for (int i = 0; i < 3; i++) {
            int y = r[1] + 2 + i * 3;
            fill(m, r[0], y, r[0] + 8, y + 1, hot ? 0xFFFFFFFF : 0xFFAAAAAA);
        }
        label(m, LEFT_VIEW_NAMES[leftView], r[0] + 12, r[1] + 2,
                hot ? 0xFFFFFF : 0xFFDD66);
    }

    private boolean leftSwitcherClicked(double mouseX, double mouseY) {
        if (activeScreen != SCREEN_TRADING) return false;
        if (!within(mouseX, mouseY, leftSwitcherRect())) return false;
        leftView = (leftView + 1) % LEFT_VIEW_NAMES.length;
        return true;
    }

    /** Every item worth listing: traded here, currently on the book, or in your ledger. */
    private List<String> marketItems() {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        MarketState market = MarketStateHolder.get();
        if (market == null) return new ArrayList<>(ids);

        ids.addAll(market.activeItems());
        ids.addAll(market.trades().tradedItems());

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            ids.addAll(market.itemBalances().heldBy(MinecraftIds.userIdOf(mc.player)).keySet());
        }
        return new ArrayList<>(ids);
    }

    private static final int MARKET_ROW_H = 20;

    /**
     * Every item at once, rather than one book at a time.
     *
     * The screen could only ever answer "what is happening with this one item I already
     * thought to ask about". This answers "what is happening", which is the question you
     * actually have when you sit down.
     */
    private void renderMarkets(MatrixStack m, int x, int y, double mouseX, double mouseY) {
        MarketState market = MarketStateHolder.get();
        List<String> ids = marketItems();
        if (market == null || ids.isEmpty()) {
            label(m, "(nothing trading yet)", x, y, 0x808080);
            return;
        }

        int viewH = panelBottom() - y;
        noteScrollable("markets", x, y, listW - 8, viewH,
                ids.size() * MARKET_ROW_H, mouseX, mouseY);
        int rowTop = y - scrollOf("markets");

        beginClip(x, y, listW - 8, viewH);
        for (String id : ids) {
            Item item = MinecraftIds.idToItem(id);
            boolean hot = mouseX >= x && mouseX < x + listW - 8
                    && mouseY >= rowTop && mouseY < rowTop + MARKET_ROW_H
                    && mouseY >= y && mouseY < y + viewH;

            drawItemCell(m, item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item),
                    x, rowTop, null, hot);

            String name = this.textRenderer.trimToWidth(
                    item == Items.AIR ? id : item.getName().getString(), listW - 24);
            label(m, name, x + 21, rowTop + 1, hot ? 0xFFFFFF : 0xC0C0C0);

            // peekBook: this runs per item per frame, and bookFor would create a book
            // for every item anyone merely holds.
            OrderBook book = market.peekBook(id);
            List<Order> bids = book == null ? Collections.emptyList() : book.restingBids();
            List<Order> asks = book == null ? Collections.emptyList() : book.restingAsks();
            long last = market.trades().lastPrice(id);

            StringBuilder line = new StringBuilder();
            line.append("bid ").append(bids.isEmpty() ? "-" : bids.get(0).value());
            line.append("  ask ").append(asks.isEmpty() ? "-" : asks.get(0).value());
            // What it last actually changed hands for — the honest number, as opposed to
            // what someone is currently hoping for.
            if (last >= 0) line.append("  last ").append(last);
            label(m, line.toString(), x + 21, rowTop + 11, 0x909090);

            rowTop += MARKET_ROW_H;
        }
        endClip();
    }

    private String marketRowAt(double mouseX, double mouseY) {
        if (activeScreen != SCREEN_TRADING || leftView != LEFT_MARKETS) return null;
        int y = panelTop() + 36;
        int viewH = panelBottom() - y;
        if (mouseX < listX + 4 || mouseX >= listX + listW
                || mouseY < y || mouseY >= y + viewH) {
            return null;
        }
        List<String> ids = marketItems();
        int index = (int) ((mouseY - y + scrollOf("markets")) / MARKET_ROW_H);
        return index >= 0 && index < ids.size() ? ids.get(index) : null;
    }

    /**
     * Recent trade prices for the selected item.
     *
     * Deliberately a plain shape rather than a chart with axes — at this size the only
     * questions it can honestly answer are "which way" and "how volatile", and gridlines
     * would imply a precision the data does not have.
     */
    private void renderPriceChart(MatrixStack m, int x, int y) {
        Item item = MinecraftIds.itemFromName(itemField.getText().trim());
        if (item == Items.AIR) {
            label(m, "(pick an item)", x, y, 0x808080);
            return;
        }

        MarketState market = MarketStateHolder.get();
        List<Trade> recent = market == null
                ? Collections.emptyList()
                : market.trades().recentFor(MinecraftIds.itemToId(item), 60);

        if (recent.size() < 2) {
            label(m, "(not enough trades yet)", x, y, 0x808080);
            if (recent.size() == 1) {
                label(m, "one trade, at " + recent.get(0).price, x, y + 12, 0x909090);
            }
            return;
        }

        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (Trade t : recent) {
            min = Math.min(min, t.price);
            max = Math.max(max, t.price);
        }
        long span = Math.max(1, max - min);

        int h = contentH - 74;
        int w = listW - 8;
        // Capped, or a market with three trades draws three bars the width of the
        // panel, which looks like a rendering fault rather than like sparse data.
        int barW = Math.max(2, Math.min(12, w / recent.size()));

        fill(m, x, y, x + w, y + h, 0x40000000);
        for (int i = 0; i < recent.size(); i++) {
            long p = recent.get(i).price;
            int barH = (int) ((p - min) * (h - 2) / span) + 1;
            int bx = x + i * barW;
            if (bx + barW > x + w) break;
            fill(m, bx, y + h - barH, bx + barW - 1, y + h, 0xFF55FFFF);
        }

        label(m, "high " + max, x, y + h + 2, 0x909090);
        label(m, "low " + min + "   last " + recent.get(recent.size() - 1).price,
                x, y + h + 12, 0x909090);
    }

    private void renderBook(MatrixStack matrices, int x, int startY,
                            double mouseX, double mouseY) {
        MinecraftClient mc = MinecraftClient.getInstance();
        UUID myUuid = mc.player != null ? MinecraftIds.userIdOf(mc.player) : null;

        Item item = MinecraftIds.itemFromName(itemField.getText().trim());
        if (item == Items.AIR) {
            label(matrices, "(pick an item to see its book)", x, startY, 0x808080);
            return;
        }

        String itemId = MinecraftIds.itemToId(item);
        MarketState market = MarketStateHolder.get();

        // peekBook, not bookFor: this runs every frame for whatever is in the field,
        // and bookFor would create an empty book for every item anyone ever types.
        OrderBook book = market.peekBook(itemId);
        List<Order> asks = book == null ? Collections.emptyList() : book.restingAsks();
        List<Order> bids = book == null ? Collections.emptyList() : book.restingBids();

        if (asks.isEmpty() && bids.isEmpty()) {
            label(matrices, "(no orders)", x, startY, 0x808080);
            return;
        }

        int rowHeight = 11;
        int viewH = panelBottom() - startY - 10;
        int contentH = (asks.size() + bids.size()) * rowHeight + 4;

        noteScrollable("book", x, startY, listW - 8, viewH, contentH, mouseX, mouseY);
        int y = startY - scrollOf("book");

        // Clipped rather than truncated. The book used to stop at six a side with no
        // indication there was more, which on a busy item hid most of the market.
        beginClip(x, startY, listW - 8, viewH);
        for (Order o : asks) {
            boolean mine = myUuid != null && o.userID().equals(myUuid);
            label(matrices, (mine ? "* " : "  ") + "#" + o.orderId()
                            + " SELL " + o.volume() + " @ " + o.value(),
                    x, y, mine ? 0xFFCC66 : 0xFF8888);
            y += rowHeight;
        }
        y += 4;
        for (Order o : bids) {
            boolean mine = myUuid != null && o.userID().equals(myUuid);
            label(matrices, (mine ? "* " : "  ") + "#" + o.orderId()
                            + " BUY  " + o.volume() + " @ " + o.value(),
                    x, y, mine ? 0xFFCC66 : 0x88FF88);
            y += rowHeight;
        }
        endClip();

        if (contentH > viewH) {
            label(matrices, "scroll for more", x, startY + viewH + 1, 0x606060);
        }
    }

    /**
     * What you are actually carrying, as opposed to what the ledger holds for you.
     *
     * Those two are constantly confused — "I have 500 iron" is true of your pockets and
     * false of the market, or the reverse, and until now the screen only ever showed
     * the second. Clicking a row selects that item to trade.
     */
    private void renderInventory(MatrixStack m, int x, int y, double mouseX, double mouseY) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        // Title inside the panel, not above it — placing it at y-11 was what left it
        // hanging over the frame's top edge.
        label(m, "You are carrying", x, y, 0xFFFFFF);

        InventoryBridge.Holdings held = InventoryBridge.held(mc.player);
        if (held.items.isEmpty()) {
            label(m, "(nothing)", x, y + 12, 0x808080);
            return;
        }

        int w = invW - 8;
        int listTop = y + 12;
        // Reserve the footer's line only when there is a footer, so the list uses the
        // whole panel when there is nothing to say.
        int footer = held.skipped > 0 ? 11 : 0;
        int viewH = panelBottom() - listTop - footer;

        noteScrollable("inv", x, listTop, w, viewH,
                held.items.size() * INV_ROW_H, mouseX, mouseY);
        int rowTop = listTop - scrollOf("inv");

        beginClip(x, listTop, w, viewH);
        for (InventoryBridge.Holding h : held.items) {
            boolean hot = mouseY >= rowTop && mouseY < rowTop + INV_ROW_H
                    && mouseX >= x && mouseX < x + w
                    && mouseY >= listTop && mouseY < listTop + viewH;
            drawItemCell(m, new ItemStack(h.item), x, rowTop, null, hot);
            label(m, String.valueOf(h.count), x + 21, rowTop + 1, 0xFFFFFF);
            String name = this.textRenderer.trimToWidth(
                    h.item.getName().getString(), w - 24);
            label(m, name, x + 21, rowTop + 11, 0xA0A0A0);
            rowTop += INV_ROW_H;
        }
        endClip();

        if (held.skipped > 0) {
            label(m, held.skipped + " with NBT hidden", x, listTop + viewH + 1, 0x606060);
        }
    }

    private static final int INV_ROW_H = 20;

    /** Which carried item a click landed on, or null. */
    private InventoryBridge.Holding inventoryRowAt(double mouseX, double mouseY) {
        if (invX < 0 || activeScreen != SCREEN_TRADING) return null;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return null;

        int y = panelTop() + 12;
        int viewH = panelBottom() - y - 12;
        if (mouseX < invX + 4 || mouseX >= invX + invW || mouseY < y || mouseY >= y + viewH) {
            return null;
        }

        List<InventoryBridge.Holding> items = InventoryBridge.held(mc.player).items;
        int index = (int) ((mouseY - y + scrollOf("inv")) / INV_ROW_H);
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    /**
     * The selected item, as an icon rather than a string of text.
     *
     * A first use of the item cell, and the beginning of the slot that will replace the
     * typed item field entirely — seeing what you have selected should not require
     * reading a registry id back to yourself.
     */
    private void renderSelectedItem(MatrixStack m, int x, int y, double mouseX, double mouseY) {
        Item item = MinecraftIds.itemFromName(itemField.getText().trim());
        boolean hovered = mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18;

        MinecraftClient mc = MinecraftClient.getInstance();
        long held = 0;
        MarketState market = MarketStateHolder.get();
        if (item != Items.AIR && mc.player != null && market != null) {
            held = market.itemBalances().getBalance(
                    MinecraftIds.userIdOf(mc.player), MinecraftIds.itemToId(item));
        }

        drawItemCell(m, item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item),
                x, y, null, hovered);

        if (item == Items.AIR) {
            label(m, "no item selected", x + 22, y + 5, 0x808080);
        } else {
            label(m, item.getName().getString(), x + 22, y + 1, 0xFFFFFF);
            label(m, held + " in market", x + 22, y + 11, 0xFFFF88);
        }
    }

    private void label(MatrixStack m, String s, int x, int y, int colour) {
        drawTextWithShadow(m, this.textRenderer, new LiteralText(s), x, y, colour);
    }

    /**
     * Grey placeholder text that disappears once something is typed.
     *
     * setSuggestion on its own draws after whatever is in the field and never stops,
     * so typing 5 into an amount box left it reading "5qty". The suggestion has to be
     * cleared and restored as the field fills and empties.
     */
    private void hint(TextFieldWidget field, String hint) {
        field.setSuggestion(field.getText().isEmpty() ? hint : null);
        field.setChangedListener(text -> field.setSuggestion(text.isEmpty() ? hint : null));
    }

    /**
     * Chooses what to trade.
     *
     * Still routed through the item field for now, because every handler reads the
     * selection from there. That field is on its way out — this is the seam the item
     * slot and picker will replace it behind, without those handlers changing.
     */
    private void selectItem(Item item) {
        if (item == null || item == Items.AIR) return;
        itemField.setText(MinecraftIds.itemToId(item));
        scrollOffsets.put("book", 0);   // a different item, a different book
    }

    // ─────────── settings widgets ───────────
    //
    // Neither vanilla control reports a change on its own: CheckboxWidget takes no
    // callback, and SliderWidget is abstract. Both are subclassed here so a setting
    // saves the moment it is changed, rather than needing an Apply button that people
    // forget to press.

    private static final class Toggle extends CheckboxWidget {
        private final java.util.function.Consumer<Boolean> onChange;

        Toggle(int x, int y, int w, int h, String message, boolean checked,
               java.util.function.Consumer<Boolean> onChange) {
            super(x, y, w, h, new LiteralText(message), checked);
            this.onChange = onChange;
        }

        @Override
        public void onPress() {
            super.onPress();
            onChange.accept(isChecked());
        }
    }

    private static final class IntSlider extends SliderWidget {
        private final String label;
        private final int min;
        private final int max;
        private final java.util.function.IntConsumer onChange;

        IntSlider(int x, int y, int w, int h, String label, int min, int max,
                  int initial, java.util.function.IntConsumer onChange) {
            super(x, y, w, h, new LiteralText(""),
                    max == min ? 0.0 : (double) (initial - min) / (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.onChange = onChange;
            updateMessage();
        }

        private int current() {
            return min + (int) Math.round(this.value * (max - min));
        }

        @Override
        protected void updateMessage() {
            setMessage(new LiteralText(label + ": " + describe(current())));
        }

        @Override
        protected void applyValue() {
            onChange.accept(current());
        }

        /** Zero is a real setting here, not "off" — it means always batch. */
        private String describe(int v) {
            return v == 0 ? "always batch" : v + " / min";
        }
    }

    // ─────────── drawing primitives ───────────

    /**
     * Clips drawing to a rectangle given in GUI coordinates.
     *
     * RenderSystem.enableScissor takes raw framebuffer pixels with the origin at the
     * BOTTOM-left and applies no transformation of its own, so this needs both a scale
     * conversion and a Y flip. Getting the flip wrong clips the complement of the
     * intended region, which reads as content vanishing rather than as a clipping bug —
     * worth doing in exactly one place.
     *
     * Scissor is a fragment test, so it clips item icons too, regardless of the
     * z-offset they are drawn at.
     */
    private void beginClip(int x, int y, int w, int h) {
        double scale = MinecraftClient.getInstance().getWindow().getScaleFactor();
        RenderSystem.enableScissor(
                (int) (x * scale),
                (int) ((this.height - (y + h)) * scale),
                (int) (w * scale),
                (int) (h * scale));
    }

    private void endClip() {
        RenderSystem.disableScissor();
    }

    /**
     * An item icon in an 18x18 cell, with an optional count in the corner.
     *
     * renderInGui ignores the MatrixStack — it draws through the legacy RenderSystem
     * matrix at absolute screen coordinates — and lands behind anything filled after
     * it unless the z-offset is raised first. This is the same dance vanilla's
     * HandledScreen.drawSlot performs, and without it the icons are invisible rather
     * than merely misplaced.
     *
     * countLabel is explicit because renderGuiItemOverlay silently omits the number
     * when a stack holds exactly one, and these cells show aggregate totals that have
     * nothing to do with stack sizes.
     */
    /**
     * A container slot: body, dark top-left, light bottom-right.
     *
     * That inset bevel is the whole reason a Minecraft slot reads as a slot. A flat
     * square is the same information and none of the recognition.
     *
     * Darkened out of vanilla's own palette (0x8B8B8B body between 0x373737 and white).
     * Those values are correct in an inventory, which is drawn on a light stone
     * texture; here they sit on PANEL_BG, which is near-black, and a grid of them read
     * as a slab of light rather than as slots. What makes the shape recognisable is
     * the *ratio* between the three tones, not their absolute values, so those are
     * preserved — body/dark 2.5 and light/body 2.0 against vanilla's 2.5 and 1.8 —
     * and the hue is pulled into the panel's violet so the grid belongs to the frame
     * around it.
     */
    private static final int SLOT_BODY = 0xFF2E2836;
    private static final int SLOT_EDGE_DARK = 0xFF120F17;
    private static final int SLOT_EDGE_LIGHT = 0xFF5F5469;

    private void drawItemCell(MatrixStack m, ItemStack stack, int x, int y,
                              String countLabel, boolean hovered) {
        fill(m, x, y, x + 18, y + 18, SLOT_BODY);
        fill(m, x, y, x + 17, y + 1, SLOT_EDGE_DARK);
        fill(m, x, y + 1, x + 1, y + 17, SLOT_EDGE_DARK);
        fill(m, x + 17, y, x + 18, y + 18, SLOT_EDGE_LIGHT);
        fill(m, x + 1, y + 17, x + 18, y + 18, SLOT_EDGE_LIGHT);

        // Before the icon, as vanilla's drawSlot does — the item is drawn at a raised
        // z-offset, so a highlight painted afterwards would sit behind it.
        if (hovered) fill(m, x + 1, y + 1, x + 17, y + 17, 0x80FFFFFF);

        drawIcon(m, stack, x + 1, y + 1, countLabel);
    }

    /** The icon on its own, for places that want the item without a slot around it. */
    private void drawIcon(MatrixStack m, ItemStack stack, int x, int y, String countLabel) {
        if (stack == null || stack.isEmpty()) return;

        ItemRenderer items = MinecraftClient.getInstance().getItemRenderer();
        this.setZOffset(100);
        items.zOffset = 100.0F;
        RenderSystem.enableDepthTest();
        items.renderInGui(stack, x, y);
        items.renderGuiItemOverlay(this.textRenderer, stack, x, y, countLabel);
        items.zOffset = 0.0F;
        this.setZOffset(0);
    }

    /**
     * How far a scrollable region has been scrolled, keyed by a name.
     *
     * Kept on the screen rather than in each panel so the scroll survives a re-render
     * without every panel having to own state, and is reset deliberately (on switching
     * item, say) rather than by accident.
     */
    private final java.util.Map<String, Integer> scrollOffsets = new java.util.HashMap<>();

    private int scrollOf(String key) {
        Integer v = scrollOffsets.get(key);
        return v == null ? 0 : v;
    }

    /** The region under the cursor last frame, so mouseScrolled knows what to move. */
    private String hoveredScrollKey;
    private int hoveredScrollMax;

    private void noteScrollable(String key, int x, int y, int w, int h,
                                int contentHeight, double mouseX, double mouseY) {
        noteScrollable(key, x, y, w, h, h, contentHeight, mouseX, mouseY);
    }

    /**
     * The same, where the rectangle that catches the wheel is not the rectangle the
     * content is clipped to.
     *
     * They were assumed to be one thing, and for most panels they are. The Market
     * column is the exception: place() clips a row against panelTop/panelBottom, which
     * is the frame inset by five pixels at each end, while the wheel is worth catching
     * anywhere over the frame. Measuring the scroll extent against the frame made it
     * nine pixels short of what the clip needs — enough to leave the bottom row
     * permanently unreachable, which is the whole failure the scroll was added to fix.
     *
     * So the caller says both, and this stops guessing that one implies the other.
     */
    private void noteScrollable(String key, int x, int y, int w, int h, int viewH,
                                int contentHeight, double mouseX, double mouseY) {
        int max = Math.max(0, contentHeight - viewH);
        // Clamp every frame: the content can shrink under a scrolled view — an order
        // fills, a host stops — and a stale offset would leave the panel looking empty.
        if (scrollOf(key) > max) scrollOffsets.put(key, max);
        if (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h) {
            hoveredScrollKey = key;
            hoveredScrollMax = max;
        }
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (pickerOpen) {
            if (chr >= ' ' && chr != 127) {
                pickerQuery += chr;
                scrollOffsets.put("picker", 0);   // a new query, back to the top
            }
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!overlays.isEmpty()) return true;
        if (hoveredScrollKey != null && hoveredScrollMax > 0) {
            int next = (int) (scrollOf(hoveredScrollKey) - amount * 12);
            scrollOffsets.put(hoveredScrollKey,
                    Math.max(0, Math.min(hoveredScrollMax, next)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    // ─────────── item picker ───────────
    //
    // The thing that finally retires typing "minecraft:iron_ingot". Search is over the
    // item's DISPLAY name, so "iron ing" finds Iron Ingot — nobody should have to know
    // that a registry id exists, let alone what one looks like.
    //
    // Its search box is a plain String rather than a TextFieldWidget: widgets are drawn
    // by super.render at one fixed point, so a registered field would render underneath
    // the panel it belongs to.

    private boolean pickerOpen = false;
    private String pickerQuery = "";
    private String pickerCachedFor = null;
    private List<Item> pickerCache = Collections.emptyList();

    private static final int PICK_COLS = 9;
    private static final int PICK_CELL = 20;
    private static final int PICK_ROWS_VISIBLE = 6;

    private void openPicker() {
        pickerOpen = true;
        pickerQuery = "";
        pickerCachedFor = null;
        scrollOffsets.put("picker", 0);
    }

    /**
     * What to offer.
     *
     * With no query, the items this market already trades and the ones you are carrying
     * — which between them cover almost every real selection, so the common case needs
     * no typing at all. Only once someone types do we go looking through every item in
     * the game, capped, because that list is thousands long and nobody scrolls it.
     */
    private List<Item> pickerItems() {
        String q = pickerQuery.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.equals(pickerCachedFor)) return pickerCache;

        List<Item> out = new ArrayList<>();
        if (q.isEmpty()) {
            java.util.LinkedHashSet<Item> seed = new java.util.LinkedHashSet<>();
            MarketState market = MarketStateHolder.get();
            if (market != null) {
                for (String id : market.activeItems()) {
                    Item it = MinecraftIds.idToItem(id);
                    if (it != Items.AIR) seed.add(it);
                }
            }
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                for (InventoryBridge.Holding h : InventoryBridge.held(mc.player).items) {
                    seed.add(h.item);
                }
            }
            out.addAll(seed);
        } else {
            for (Item it : Registry.ITEM) {
                if (it == Items.AIR) continue;
                if (it.getName().getString().toLowerCase(java.util.Locale.ROOT).contains(q)) {
                    out.add(it);
                    if (out.size() >= 180) break;
                }
            }
        }

        pickerCachedFor = q;
        pickerCache = out;
        return out;
    }

    private int[] pickerBox() {
        int w = PICK_COLS * PICK_CELL + 16;
        int h = PICK_ROWS_VISIBLE * PICK_CELL + 46;
        return new int[]{(this.width - w) / 2, Math.max(20, (this.height - h) / 2), w, h};
    }

    private int[] pickerGridRect() {
        int[] box = pickerBox();
        return new int[]{box[0] + 8, box[1] + 34, PICK_COLS * PICK_CELL,
                PICK_ROWS_VISIBLE * PICK_CELL};
    }

    private void renderPicker(MatrixStack m, int mouseX, int mouseY) {
        if (!pickerOpen) return;

        // Depth testing off for the panel itself. Item icons leave it enabled — both
        // renderInGui and drawItemCell turn it on — so a fill drawn afterwards gets
        // depth-rejected against text already on screen, and the buttons underneath
        // show straight through the panel covering them.
        RenderSystem.disableDepthTest();

        fill(m, 0, 0, this.width, this.height, 0xE0101010);

        int[] box = pickerBox();
        vanillaPanel(m, box[0], box[1], box[2], box[3]);

        label(m, "Pick an item", box[0] + 8, box[1] + 8, 0xFFAA00);

        // Search box, drawn rather than widgeted. It always has focus — the picker is
        // modal and there is nothing else here to type into — so it shows a caret from
        // the moment it opens rather than waiting for a click that does nothing.
        int searchY = box[1] + 20;
        fill(m, box[0] + 7, searchY - 1, box[0] + box[2] - 7, searchY + 13, 0xFF88CCFF);
        fill(m, box[0] + 8, searchY, box[0] + box[2] - 8, searchY + 12, 0xFF101010);
        if (pickerQuery.isEmpty()) {
            label(m, "type to search by name...", box[0] + 11, searchY + 2, 0x606060);
        } else {
            label(m, pickerQuery, box[0] + 11, searchY + 2, 0xFFFFFF);
        }
        if ((System.currentTimeMillis() / 500) % 2 == 0) {
            int caretX = box[0] + 11 + this.textRenderer.getWidth(pickerQuery);
            fill(m, caretX, searchY + 2, caretX + 1, searchY + 11, 0xFFFFFFFF);
        }

        RenderSystem.enableDepthTest();

        List<Item> items = pickerItems();
        int[] grid = pickerGridRect();
        int rows = (items.size() + PICK_COLS - 1) / PICK_COLS;
        noteScrollable("picker", grid[0], grid[1], grid[2], grid[3],
                rows * PICK_CELL, mouseX, mouseY);
        int offset = scrollOf("picker");

        if (items.isEmpty()) {
            label(m, "nothing matches", grid[0], grid[1] + 4, 0x808080);
        }

        beginClip(grid[0], grid[1], grid[2], grid[3]);
        for (int i = 0; i < items.size(); i++) {
            int cx = grid[0] + (i % PICK_COLS) * PICK_CELL;
            int cy = grid[1] + (i / PICK_COLS) * PICK_CELL - offset;
            boolean hot = mouseX >= cx && mouseX < cx + 18 && mouseY >= cy && mouseY < cy + 18
                    && mouseY >= grid[1] && mouseY < grid[1] + grid[3];
            drawItemCell(m, new ItemStack(items.get(i)), cx, cy, null, hot);
        }
        endClip();

        // Name of whatever is under the cursor, so the grid is readable without
        // clicking things to find out what they are. Depth off again — the icons above
        // left it on, and this sits over the panel.
        RenderSystem.disableDepthTest();
        Item hovered = pickerItemAt(mouseX, mouseY);
        String footer = hovered != null ? hovered.getName().getString()
                : items.size() + " item(s) — Esc to cancel";
        label(m, footer, box[0] + 8, box[1] + box[3] - 14,
                hovered != null ? 0xFFFFFF : 0x808080);
        RenderSystem.enableDepthTest();
    }

    private Item pickerItemAt(double mouseX, double mouseY) {
        if (!pickerOpen) return null;
        int[] grid = pickerGridRect();
        if (mouseX < grid[0] || mouseX >= grid[0] + grid[2]
                || mouseY < grid[1] || mouseY >= grid[1] + grid[3]) {
            return null;
        }
        List<Item> items = pickerItems();
        int col = (int) ((mouseX - grid[0]) / PICK_CELL);
        int row = (int) ((mouseY - grid[1] + scrollOf("picker")) / PICK_CELL);
        int index = row * PICK_COLS + col;
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    /** Returns true while the picker is up, which is any click at all. */
    private boolean pickerClicked(double mouseX, double mouseY) {
        Item chosen = pickerItemAt(mouseX, mouseY);
        if (chosen != null) {
            selectItem(chosen);
            pickerOpen = false;
        } else if (!within(mouseX, mouseY, pickerBox())) {
            pickerOpen = false;   // clicking away cancels
        }
        return true;
    }

    // ─────────── chrome ───────────

    /**
     * The one strip that is the same wherever you are: what this client is doing, what
     * you're worth, and the way out to everything else.
     */
    private void renderHeader(MatrixStack m) {
        drawCenteredText(m, this.textRenderer, this.title, this.width / 2, 8, 0xFFFFFF);

        renderConnectionStatus(m, 8, 8);

        MinecraftClient mc = MinecraftClient.getInstance();
        MarketState market = MarketStateHolder.get();
        if (mc.player != null && market != null) {
            UUID me = MinecraftIds.userIdOf(mc.player);

            // An emerald marks the money, and only the money. Putting the item's own
            // icon beside the second line made two icons compete when only one of them
            // was saying anything the words did not already say.
            drawIcon(m, new ItemStack(Items.EMERALD), 8, 16, null);
            label(m, "Credits: " + market.wallets().getBalance(me), 28, 20, 0xFFFF88);

            // Only where an item is selected and the number means something.
            if (activeScreen == SCREEN_TRADING) {
                Item item = MinecraftIds.itemFromName(itemField.getText().trim());
                if (item != Items.AIR) {
                    long held = market.itemBalances()
                            .getBalance(me, MinecraftIds.itemToId(item));
                    label(m, item.getName().getString() + " market credit: " + held,
                            28, 32, 0xFFFF88);
                }
            }
        }
    }

    /**
     * The tab row.
     *
     * Vanilla's tabs bleed into the panel below to read as joined to it, which works
     * because there is one panel under them. There are three here with gaps between, so
     * a bleeding tab would as often as not run into empty space — the current tab is
     * raised and lit instead, which says the same thing without depending on what
     * happens to be underneath it.
     */
    private void renderTabs(MatrixStack m, int mouseX, int mouseY) {
        for (int i = 0; i < SCREEN_NAMES.length; i++) {
            int[] r = tabRect(i);
            boolean here = i == activeScreen;
            boolean hot = !here && within(mouseX, mouseY, r);

            // The inactive ones sit lower, so the current one reads as standing forward
            // of the row rather than merely being a different colour in it.
            int top = here ? r[1] : r[1] + 2;
            int bottom = r[1] + r[3];
            int right = r[0] + r[2];

            fill(m, r[0], top, right, bottom, here ? PANEL_BG : 0xC0080008);

            fillGradient(m, r[0], top, r[0] + 1, bottom, PANEL_EDGE_TOP, PANEL_EDGE_BOTTOM);
            fillGradient(m, right - 1, top, right, bottom, PANEL_EDGE_TOP, PANEL_EDGE_BOTTOM);
            fillGradient(m, r[0], top, right, top + 1, PANEL_EDGE_TOP, PANEL_EDGE_TOP);
            fillGradient(m, r[0], bottom - 1, right, bottom,
                    PANEL_EDGE_BOTTOM, PANEL_EDGE_BOTTOM);

            drawCenteredText(m, this.textRenderer, new LiteralText(SCREEN_NAMES[i]),
                    r[0] + r[2] / 2, top + (bottom - top - 8) / 2,
                    here ? 0xFFAA00 : (hot ? 0xFFFFFF : 0x909090));
        }
    }

    /** Returns true if the click landed on a tab. */
    private boolean tabsClicked(double mouseX, double mouseY) {
        for (int i = 0; i < SCREEN_NAMES.length; i++) {
            if (within(mouseX, mouseY, tabRect(i))) {
                selectScreen(i);
                return true;
            }
        }
        return false;
    }

    // ─────────── overlays ───────────

    /**
     * A message that has to be dealt with before anything else.
     *
     * Replaces the "click the button again to confirm" flags this screen used to
     * carry. Those had to be disarmed by hand on close, could not say what they were
     * asking without hijacking the status line, and left the question visible for
     * exactly as long as nothing else wrote a status — which on a busy market was not
     * long. A modal asks once, blocks until answered, and cannot be half-armed.
     *
     * Deliberately NOT a separate Screen: opening one calls removed() on this screen
     * and re-runs init() on the way back, which would clear the amount, price and
     * order-id fields and re-fire a discovery poll every time anyone confirmed
     * anything.
     */
    private static final class Overlay {
        static final int CONFIRM = 1;
        static final int DANGER = 2;

        final int kind;
        final String title;
        final String body;
        final String confirmLabel;
        final Runnable onConfirm;
        final long shownAt = System.currentTimeMillis();

        Overlay(int kind, String title, String body, String confirmLabel, Runnable onConfirm) {
            this.kind = kind;
            this.title = title;
            this.body = body;
            this.confirmLabel = confirmLabel;
            this.onConfirm = onConfirm;
        }

        /** Destructive answers ignore clicks for a moment, so a double-click aimed at
         *  the button underneath cannot carry through into confirming. */
        boolean armed() {
            return kind != DANGER || System.currentTimeMillis() - shownAt > 400;
        }
    }

    /** Queued rather than replaced: a second warning must not erase the first unread one. */
    private final Deque<Overlay> overlays = new ArrayDeque<>();

    /**
     * Drawn by renderOverlay, never registered with the screen.
     *
     * They exist for their appearance — vanilla's button texture and its hover and
     * disabled states — while the overlay's own hit-testing decides what a click does.
     * Their press actions are therefore empty on purpose.
     */
    private final ButtonWidget overlayConfirmButton = new ButtonWidget(
            0, 0, OVERLAY_BTN_W, OVERLAY_BTN_H, new LiteralText(""), b -> {});
    private final ButtonWidget overlayDismissButton = new ButtonWidget(
            0, 0, OVERLAY_BTN_W, OVERLAY_BTN_H, new LiteralText(""), b -> {});

    private void showConfirm(String title, String body, String confirmLabel, Runnable action) {
        overlays.addLast(new Overlay(Overlay.CONFIRM, title, body, confirmLabel, action));
    }

    private void showDanger(String title, String body, String confirmLabel, Runnable action) {
        overlays.addLast(new Overlay(Overlay.DANGER, title, body, confirmLabel, action));
    }

    private static final int OVERLAY_W = 300;
    private static final int OVERLAY_PAD = 10;
    private static final int OVERLAY_BTN_H = 20;
    private static final int OVERLAY_BTN_W = 90;

    /**
     * The body, wrapped, with paragraph breaks honoured.
     *
     * Split here rather than handed to wrapLines whole. Vanilla's wrapper is a wrapper —
     * what it does with a newline is its business and has changed between versions, and
     * the reset confirmation now depends on its paragraphs surviving, because separating
     * "comes back", "comes back if you act" and "is gone" is the entire point of that
     * rewrite. A blank line between them is cheap to guarantee and not worth trusting
     * somebody else's code for.
     */
    private List<OrderedText> overlayLines(Overlay o) {
        List<OrderedText> out = new ArrayList<>();
        String[] paragraphs = o.body.split("\n\n");
        for (int i = 0; i < paragraphs.length; i++) {
            if (i > 0) out.add(OrderedText.EMPTY);
            out.addAll(this.textRenderer.wrapLines(
                    new LiteralText(paragraphs[i].replace("\n", " ")),
                    OVERLAY_W - OVERLAY_PAD * 2));
        }
        return out;
    }

    /**
     * Geometry for the current overlay: {left, top, width, height}.
     *
     * A pure function of the window and the message, so render and hit-testing derive
     * the same rectangles from the same inputs rather than one trusting coordinates
     * the other happened to leave in a field.
     *
     * top was Math.max(20, centred), which centres a box that fits and pins a taller one
     * to y=20 — where it keeps growing downwards and takes its own buttons off the
     * bottom of the screen with it. Nothing anchored the bottom, and the reset
     * confirmation had just been given room to grow with a list of items. That is the
     * failure this file has now had three times: content stacked downwards with no
     * bound, hiding the one control that had to survive. Clamped so the bottom stays on
     * screen whenever the box fits at all, and the body itself is capped at its source —
     * describeRefunds names three kinds and counts the rest.
     */
    private int[] overlayBox(Overlay o) {
        int lines = overlayLines(o).size();
        int h = OVERLAY_PAD + 12                       // title
                + lines * 10 + OVERLAY_PAD             // body
                + OVERLAY_BTN_H + OVERLAY_PAD;         // buttons
        int left = (this.width - OVERLAY_W) / 2;
        int centred = (this.height - h) / 2;
        int top = Math.max(4, Math.min(centred, this.height - h - 4));
        return new int[]{left, top, OVERLAY_W, h};
    }

    /** {x, y, w, h} of the confirm button, or null when the overlay only says "OK". */
    private int[] overlayConfirmRect(Overlay o) {
        if (o.onConfirm == null) return null;
        int[] box = overlayBox(o);
        return new int[]{box[0] + OVERLAY_PAD, box[1] + box[3] - OVERLAY_PAD - OVERLAY_BTN_H,
                OVERLAY_BTN_W, OVERLAY_BTN_H};
    }

    /** {x, y, w, h} of the dismiss button — "Cancel", or "OK" when there is nothing to confirm. */
    private int[] overlayDismissRect(Overlay o) {
        int[] box = overlayBox(o);
        return new int[]{box[0] + box[2] - OVERLAY_PAD - OVERLAY_BTN_W,
                box[1] + box[3] - OVERLAY_PAD - OVERLAY_BTN_H,
                OVERLAY_BTN_W, OVERLAY_BTN_H};
    }

    private static boolean within(double mx, double my, int[] r) {
        return r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    private void renderOverlay(MatrixStack m, Overlay o, int mouseX, int mouseY) {
        // Depth off for the same reason the picker turns it off: item icons leave depth
        // testing enabled, and a panel filled afterwards is otherwise rejected against
        // text already drawn, letting the buttons underneath show through.
        RenderSystem.disableDepthTest();

        // Dim everything behind it, so it reads as blocking rather than as another
        // widget competing for attention with the rest of the screen.
        fill(m, 0, 0, this.width, this.height, 0xE0101010);

        int[] box = overlayBox(o);
        // Vanilla's own severity colours: red for destructive, yellow for a choice.
        int accent = o.kind == Overlay.DANGER ? 0xFFFF5555 : 0xFFFFAA00;

        vanillaPanel(m, box[0], box[1], box[2], box[3]);

        int y = box[1] + OVERLAY_PAD;
        label(m, o.title, box[0] + OVERLAY_PAD, y, accent);
        y += 14;

        for (OrderedText line : overlayLines(o)) {
            this.textRenderer.drawWithShadow(m, line, box[0] + OVERLAY_PAD, y, 0xFFDDDDDD);
            y += 10;
        }

        // Real ButtonWidgets, drawn by hand rather than registered.
        //
        // Registering them would put them in super.render, underneath the panel they
        // belong to. Drawing them here keeps vanilla's texture, hover and disabled
        // states — hand-filled rectangles could imitate the shape but never the way a
        // Minecraft button actually looks next to the ones on the screen behind.
        // Clicks are still hit-tested against the same rectangles, so render and input
        // cannot disagree.
        RenderSystem.enableDepthTest();

        int[] confirm = overlayConfirmRect(o);
        if (confirm != null) {
            overlayConfirmButton.setMessage(new LiteralText(o.confirmLabel));
            overlayConfirmButton.x = confirm[0];
            overlayConfirmButton.y = confirm[1];
            overlayConfirmButton.setWidth(confirm[2]);
            overlayConfirmButton.active = o.armed();
            overlayConfirmButton.visible = true;
            overlayConfirmButton.render(m, mouseX, mouseY, 0.0F);
        }

        int[] dismiss = overlayDismissRect(o);
        overlayDismissButton.setMessage(
                new LiteralText(o.onConfirm == null ? "OK" : "Cancel"));
        overlayDismissButton.x = dismiss[0];
        overlayDismissButton.y = dismiss[1];
        overlayDismissButton.setWidth(dismiss[2]);
        overlayDismissButton.active = true;
        overlayDismissButton.visible = true;
        overlayDismissButton.render(m, mouseX, mouseY, 0.0F);
    }

    /** Returns true if the click was the overlay's, which is any click at all while one is up. */
    private boolean overlayClicked(double mouseX, double mouseY) {
        Overlay o = overlays.peekFirst();
        if (o == null) return false;

        if (within(mouseX, mouseY, overlayDismissRect(o))) {
            overlays.pollFirst();
            return true;
        }
        int[] confirm = overlayConfirmRect(o);
        if (confirm != null && o.armed() && within(mouseX, mouseY, confirm)) {
            overlays.pollFirst();
            if (o.onConfirm != null) o.onConfirm.run();
            return true;
        }
        // Swallow everything else. Scoped to "an overlay is open" rather than leaking
        // out to the whole screen, which is how the re-place list used to disable
        // every button on it.
        return true;
    }

    private static class OrderRequest {
        Item item;
        String itemId;
        UUID userId;
        long qty;
        long price;
    }

    @Override
    public void removed() {
        // Every two-click confirm disarms on close, not just these two. A flag left
        // armed means the next single click on that button acts immediately, with the
        // "click again to confirm" prompt having scrolled away in a previous session —
        // and three of the five guard something irreversible.
        // Nothing to disarm any more — confirmations are modal and answered in place,
        // so none of them can survive a screen close half-armed.
        overlays.clear();

        // The handler registered in init() writes this screen's status field, so it
        // holds a reference to this screen. The holder outlives every screen, so
        // leaving it pointed here would keep a closed screen — and the whole widget
        // tree hanging off it — reachable until the next one opens. Reset to the same
        // no-op the holder starts with, not null: every call site accepts() unguarded.
        MarketStateHolder.setOnRejected(reason -> {});

        // Persisted rather than parked in statics, so these survive quitting the game
        // and not merely closing the screen. The market name is saved when a market is
        // actually created, not here — a half-typed name is not a preference.
        Settings s = settings();
        if (s != null) {
            s.setLastItem(itemField.getText().trim());
            s.setLastHostAddress(hostField.getText().trim());
            try {
                s.setHostPort(Integer.parseInt(hostPortField.getText().trim()));
            } catch (NumberFormatException ignored) {
                // Not a number, so not worth remembering. setHostPort rejects
                // out-of-range values on the same principle.
            }
        }
        super.removed();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Escape answers the overlay rather than closing the screen out from under it.
        if (!overlays.isEmpty()) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                overlays.pollFirst();
            }
            return true;
        }

        // The picker eats typing, since its search box isn't a widget and so gets no
        // key events of its own.
        if (pickerOpen) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                pickerOpen = false;
            } else if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE
                    && !pickerQuery.isEmpty()) {
                pickerQuery = pickerQuery.substring(0, pickerQuery.length() - 1);
                scrollOffsets.put("picker", 0);
            }
            return true;
        }

        // Whatever opens the market closes it, asked of the keybind rather than
        // hardcoded to M — otherwise this would go on closing the screen for a key the
        // mod no longer claims, and would ignore whatever the player actually bound.
        // Unbound matches nothing, which leaves Escape as the way out, as it always was.
        //
        // Still gated on focus rather than a list of fields: the old enumeration left
        // out marketNameField, so typing "m" into it closed the screen.
        if (MarketKeybinds.openMarketKey != null
                && MarketKeybinds.openMarketKey.matchesKey(keyCode, scanCode)
                && !(this.getFocused() instanceof TextFieldWidget)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

}