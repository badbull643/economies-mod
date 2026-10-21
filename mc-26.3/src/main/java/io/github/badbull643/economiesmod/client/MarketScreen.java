package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.*;
import io.github.badbull643.economiesmod.core.net.PeerPoll;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

//this needs a massive refactor horrible class
public class MarketScreen extends Screen {

    private EditBox amountField;
    private EditBox itemField;
    private EditBox priceField;
    private EditBox hostField;
    /** The item view's one action button; what it does follows the Buy / Sell / Withdraw switch. */
    private Button placeButton;
    private EditBox hostPortField;
    private EditBox marketNameField;
    private Button hostButton;
    private Button disconnectButton;
    private Button stopHostButton;
    private Button createButton;
    private Button importButton;
    private Button migrateButton;
    /** Browse's search box, in the footer. */
    private EditBox searchField;
    private Button exportButton;
    private Button resetButton;
    private EditBox feeField;
    private Button feeButton;
    private EditBox listingFeeField;
    private Button listingFeeButton;
    private EditBox stipendField;
    private Button stipendButton;
    private Button claimStipendButton;
    private EditBox grantField;
    private Button grantButton;
    private Button addMarketButton;
    private Button deleteMarketButton;

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

    // ─── the trading screens' own columns ───
    //
    // Browse is the item list with what you are carrying beside it; an item's view is its
    // book on the left and the order form on the right. Measured from the same content box
    // as everything else, so the frame lines up across every tab.

    /** Item view: the book column's width, and the order form's left edge and width. */
    private int itemLeftW;
    private int formX;
    private int formW;

    /** Where the order form's labels end and its fields begin. */
    private static final int FORM_LABEL_W = 58;

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
    private int panelBottom() { return frameTop() + frameH() - 5 - FOOTER_H; }

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



    // One panel, and a menu in its top corner choosing what it shows. Trading first,
    // because that is what the screen is opened for: what is on offer (Browse, and an
    // item's own view from there), what you could sell (Sell), what you have open, and
    // what you have done. Connecting, the market's own setup and preferences come after a
    // divider, being used once a session or less. A row of tabs offered all seven at the
    // same weight, which is most of what made the screen look busy.
    //
    // The Info tab's dashboard and its live feed of every order are gone. On a busy
    // market — bots make every market busy — the feed was a blur of orders nobody could
    // act on, and Browse answers the question it was there for.
    static final int SCREEN_BROWSE = 0;
    static final int SCREEN_SELL = 1;
    static final int SCREEN_ORDERS = 2;
    static final int SCREEN_HISTORY = 3;
    static final int SCREEN_LEADERBOARD = 4;
    static final int SCREEN_NETWORK = 5;
    static final int SCREEN_MARKET = 6;
    static final int SCREEN_SETTINGS = 7;
    /** One item's view. Not in the menu: it is reached from a row, and the menu still says where from. */
    static final int SCREEN_ITEM = 8;

    private static final String[] SCREEN_NAMES =
            {"Browse", "Sell", "Your orders", "History", "Leaderboard", "Network", "Market", "Settings", "Item"};

    /** Static so the screen you were on survives closing and reopening. */
    private static int activeScreen = SCREEN_BROWSE;

    /** The list an item's view was opened from, which its back arrow and the menu return to. */
    private static int itemOpenedFrom = SCREEN_BROWSE;

    private static boolean isSetupScreen(int screen) {
        return screen == SCREEN_NETWORK || screen == SCREEN_MARKET || screen == SCREEN_SETTINGS;
    }

    /** One widget list per destination, indexed by the SCREEN_ constants. */
    private final List<List<AbstractWidget>> screenWidgets = new ArrayList<>();

    private <T extends AbstractWidget> T onScreen(int screen, T widget) {
        screenWidgets.get(screen).add(widget);
        return this.addRenderableWidget(widget);
    }

    private void selectScreen(int screen) {
        if (screen == SCREEN_ITEM && activeScreen != SCREEN_ITEM) itemOpenedFrom = activeScreen;
        activeScreen = screen;
        menuOpen = false;
        applyScreenVisibility();
    }

    private void applyScreenVisibility() {
        for (int i = 0; i < screenWidgets.size(); i++) {
            setShown(screenWidgets.get(i), i == activeScreen);
        }
    }

    private static void setShown(List<AbstractWidget> widgets, boolean shown) {
        for (AbstractWidget w : widgets) {
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

    // ─── the panel's frame ───
    //
    // A header where the tab row used to be, so everything inside the panel stays exactly
    // where it was, and a footer for the search box and your credits. Measured from the
    // same frameTop as the content, so the alert band moves the whole panel together.

    private static final int HEADER_H = 20;
    private static final int FOOTER_H = 18;

    private int panelOuterTop() { return frameTop() - HEADER_H; }
    private int panelOuterBottom() { return frameTop() + frameH(); }
    private int footerTop() { return panelOuterBottom() - FOOTER_H; }

    /** The menu's entries, in order. -1 is the divider between trading and setup. */
    private static final int[] MENU = {SCREEN_BROWSE, SCREEN_SELL, SCREEN_ORDERS, SCREEN_HISTORY,
            SCREEN_LEADERBOARD, -1, SCREEN_NETWORK, SCREEN_MARKET, SCREEN_SETTINGS};
    private static final int MENU_ROW_H = 12;
    private static final int MENU_DIVIDER_H = 5;

    private boolean menuOpen = false;

    /** What the menu says you are looking at. An item's view belongs to the list it came from. */
    private static int menuScreen() {
        return activeScreen == SCREEN_ITEM ? itemOpenedFrom : activeScreen;
    }

    private int[] menuButtonRect() {
        int w = this.font.width(SCREEN_NAMES[menuScreen()]) + 14;
        return new int[]{listX + contentW - 6 - w, panelOuterTop() + 4, w, 12};
    }

    private int[] menuRect() {
        int w = 0, h = 4;
        for (int s : MENU) {
            if (s < 0) h += MENU_DIVIDER_H;
            else { w = Math.max(w, this.font.width(SCREEN_NAMES[s])); h += MENU_ROW_H; }
        }
        w += 16;
        int[] b = menuButtonRect();
        return new int[]{b[0] + b[2] - w, b[1] + b[3] + 2, w, h};
    }

    /** Top of each menu entry. The drawing and the click test both read this. */
    private int menuEntryY(int index) {
        int y = menuRect()[1] + 2;
        for (int i = 0; i < index; i++) y += MENU[i] < 0 ? MENU_DIVIDER_H : MENU_ROW_H;
        return y;
    }

    /** Translucent black, so the world shows through behind the market as it does behind a chat line. */
    private static final int PANEL_FILL = 0xC8000000;
    private static final int PANEL_LINE = 0x30FFFFFF;
    /**
     * This market's own colour: emerald, after the emerald that marks credits. On the line
     * under the header, the view you are on, links and headings — the places a neutral
     * grey panel would otherwise have nothing of its own.
     */
    private static final int ACCENT = 0x4CD07A;
    private static final int ACCENT_LINE = 0xFF000000 | ACCENT;
    /** Connection state: green when a host is there for you, red when it is not. */
    private static final int STATE_GOOD = 0x55FF55;
    private static final int STATE_BAD = 0xFF5555;

    /** A vertical line between two columns, header to footer. */
    private void divider(GuiGraphicsExtractor m, int x) {
        m.fill(x, frameTop(), x + 1, footerTop(), PANEL_LINE);
    }

    private void renderFrame(GuiGraphicsExtractor m, int mouseX, int mouseY) {
        int top = panelOuterTop(), bottom = panelOuterBottom(), right = listX + contentW;
        m.fill(listX, top, right, bottom, PANEL_FILL);
        m.fill(listX, top, right, top + HEADER_H, 0x40000000);
        m.fill(listX, top + HEADER_H - 1, right, top + HEADER_H, ACCENT_LINE);
        m.fill(listX, footerTop(), right, footerTop() + 1, PANEL_LINE);

        int[] b = menuButtonRect();
        String state;
        int colour;
        if (MarketStateHolder.mode() == MarketStateHolder.Mode.HOSTING) { state = "hosting"; colour = STATE_GOOD; }
        else if (MarketStateHolder.isConnected()) { state = "connected"; colour = STATE_GOOD; }
        else if (MarketStateHolder.mode() == MarketStateHolder.Mode.CONNECTED) { state = "connection lost"; colour = STATE_BAD; }
        else { state = "not connected"; colour = STATE_BAD; }
        String stateText = "· " + state;
        MarketState named = MarketStateHolder.get();
        String name = named == null || named.marketName() == null ? "Market" : "Market - " + named.marketName();
        int room = b[0] - listX - 18 - this.font.width(stateText);
        String shownName = trim(name, Math.max(40, room));
        label(m, shownName, listX + 6, top + 6, 0xFFFFFF);
        label(m, stateText, listX + 10 + this.font.width(shownName), top + 6, colour);

        boolean hot = menuOpen || within(mouseX, mouseY, b);
        label(m, SCREEN_NAMES[menuScreen()], b[0], b[1] + 2, hot ? 0xFFFFFF : 0xC0C0C0);
        int tx = b[0] + b[2] - 7, ty = b[1] + 5;   // a small down-pointing triangle
        for (int i = 0; i < 3; i++) m.fill(tx - 2 + i, ty + i, tx + 3 - i, ty + i + 1, hot ? 0xFFFFFFFF : 0xFFC0C0C0);

        // Your credits, always in the same corner, with the emerald that marks money.
        MarketState market = MarketStateHolder.get();
        UUID me = myId();
        if (market != null && me != null) {
            String credits = market.wallets().getBalance(me) + " credits";
            int cw = this.font.width(credits);
            label(m, credits, right - 6 - cw, footerTop() + 5, 0xFFFF88);
            drawIcon(m, new ItemStack(Items.EMERALD), right - 6 - cw - 18, footerTop() + 1, null);

            // Your profit so far, beside the money it is measured in.
            long pnl = market.realizedPnl(me);
            String profit = "PnL " + MarketView.signed(pnl);
            int px = right - 6 - cw - 18 - 10 - this.font.width(profit);
            label(m, profit, px, footerTop() + 5, pnlColour(pnl));
            if (mouseX >= px && mouseX < right - cw - 24 && mouseY >= footerTop() && mouseY < panelOuterBottom()) {
                hoverTip = "Your profit: what you have made by selling, after fees and after what"
                        + " those goods cost you. History shows the rest.";
            }
        }
        String note = footerNote();
        if (!note.isEmpty()) label(m, trim(note, contentW / 2), listX + 6, footerTop() + 5, 0x909090);
    }

    private static int pnlColour(long v) {
        return v > 0 ? STATE_GOOD : v < 0 ? STATE_BAD : 0x909090;
    }

    /** The footer's left side, where Browse has its search box instead. */
    private String footerNote() {
        MarketState market = MarketStateHolder.get();
        UUID me = myId();
        if (market == null) return "";
        switch (activeScreen) {
            case SCREEN_SELL: return "click something to sell it";
            case SCREEN_ORDERS: {
                int n = yourOrdersCached().size();
                return n + (n == 1 ? " open order" : " open orders");
            }
            case SCREEN_HISTORY: {
                int n = historyRows().size();
                return n + (n == 1 ? " trade" : " trades") + (n >= HISTORY_LIMIT ? " (the latest)" : "");
            }
            case SCREEN_LEADERBOARD: {
                int n = market.registeredUsers().size();
                return n + (n == 1 ? " trader" : " traders");
            }
            default: return "";
        }
    }

    private void renderMenu(GuiGraphicsExtractor m, int mouseX, int mouseY) {
        if (!menuOpen) return;
        m.nextStratum();
        int[] r = menuRect();
        m.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0xF0080808);
        m.fill(r[0], r[1], r[0] + r[2], r[1] + 1, PANEL_LINE);
        m.fill(r[0], r[1] + r[3] - 1, r[0] + r[2], r[1] + r[3], PANEL_LINE);
        m.fill(r[0], r[1], r[0] + 1, r[1] + r[3], PANEL_LINE);
        m.fill(r[0] + r[2] - 1, r[1], r[0] + r[2], r[1] + r[3], PANEL_LINE);
        for (int i = 0; i < MENU.length; i++) {
            int y = menuEntryY(i);
            if (MENU[i] < 0) {
                m.fill(r[0] + 4, y + 2, r[0] + r[2] - 4, y + 3, PANEL_LINE);
                continue;
            }
            boolean here = MENU[i] == menuScreen();
            boolean hot = mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= y && mouseY < y + MENU_ROW_H;
            if (hot) m.fill(r[0] + 1, y, r[0] + r[2] - 1, y + MENU_ROW_H, 0x30FFFFFF);
            label(m, SCREEN_NAMES[MENU[i]], r[0] + 8, y + 2, here ? ACCENT : hot ? 0xFFFFFF : 0xC0C0C0);
        }
    }

    /** Clicks while the menu is open, or on its button. True when the click was the menu's. */
    private boolean menuClicked(double mouseX, double mouseY) {
        if (within(mouseX, mouseY, menuButtonRect())) {
            menuOpen = !menuOpen;
            return true;
        }
        if (!menuOpen) return false;
        int[] r = menuRect();
        for (int i = 0; i < MENU.length; i++) {
            int y = menuEntryY(i);
            if (MENU[i] >= 0 && mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= y && mouseY < y + MENU_ROW_H) {
                selectScreen(MENU[i]);
                return true;
            }
        }
        menuOpen = false;   // clicking away closes it, and does nothing else
        return true;
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
        super(Component.literal("Market"));
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

        // ─── BROWSE and the ITEM view ───
        // Narrower than the setup screens' control column: the form is three short rows,
        // and the book beside it is what needs the width.
        this.formW = Math.max(160, Math.min(230, contentW * 2 / 5));
        this.itemLeftW = contentW - formW - PAD * 2 - COL_GAP;
        this.formX = listX + itemLeftW + COL_GAP + PAD;

        // Built but deliberately NOT registered as a widget. It is pure storage for the
        // selection — every handler reads the item from here, and the rows, the picker and
        // the carrying list write to it. Nothing types into it.
        this.itemField = new EditBox(this.font,
                formX, rowY, formW, FIELD_HEIGHT, Component.literal("Item"));
        this.itemField.setMaxLength(64);
        this.itemField.setValue(savedItem());

        // Placed in the footer every frame (see refreshMarketButtons), since the footer
        // stays put when the alert band pushes everything else down.
        this.searchField = new EditBox(this.font,
                listX + 6, rowY, Math.min(150, contentW / 3), 14, Component.literal("Search"));
        this.searchField.setMaxLength(40);
        hint(this.searchField, "search");
        this.searchField.setResponder(text -> {
            this.searchField.setSuggestion(text.isEmpty() ? "search" : null);
            scrollOffsets.put("browse", 0);
        });
        onScreen(SCREEN_BROWSE, this.searchField);

        int fieldX = formX + FORM_LABEL_W;
        int fieldW = formW - FORM_LABEL_W;
        this.amountField = new EditBox(this.font,
                fieldX, rowY + 18, fieldW, FIELD_HEIGHT, Component.literal("Amount"));
        hint(this.amountField, "how many");
        onScreen(SCREEN_ITEM, this.amountField);

        this.priceField = new EditBox(this.font,
                fieldX, rowY + 18 + ROW_STEP - 2, fieldW, FIELD_HEIGHT, Component.literal("Price"));
        hint(this.priceField, "credits each");
        onScreen(SCREEN_ITEM, this.priceField);

        this.placeButton = onScreen(SCREEN_ITEM,
                Button.builder(Component.literal("Place buy order"), b -> onPlace())
                .bounds(formX, rowY + 98, formW, FIELD_HEIGHT).build());

        // ─── NETWORK ───
        this.hostField = new EditBox(this.font,
                rowX, rowY, controlsW - halfWS - PAD, FIELD_HEIGHT, Component.literal("Host"));
        this.hostField.setMaxLength(64);
        this.hostField.setValue(savedHost());
        onScreen(SCREEN_NETWORK, this.hostField);

        this.hostPortField = new EditBox(this.font,
                rowX + controlsW - halfWS, rowY, halfWS, FIELD_HEIGHT, Component.literal("Port"));
        this.hostPortField.setValue(savedPort());
        onScreen(SCREEN_NETWORK, this.hostPortField);

        onScreen(SCREEN_NETWORK, Button.builder(Component.literal("Connect"), b -> onConnect())
                .bounds(rowX, rowY + ROW_STEP, halfW, FIELD_HEIGHT).build());

        // Host serves the market this world already holds. With no market there is
        // nothing to serve, so the button is disabled rather than silently creating
        // one — that silent creation is what fragments a friend group into two
        // permanently incompatible economies.
        this.hostButton = Button.builder(Component.literal("Host"), b -> onHost())
                .bounds(rowX + halfW + PAD, rowY + ROW_STEP, halfW, FIELD_HEIGHT).build();
        onScreen(SCREEN_NETWORK, this.hostButton);

        // Both were always live, side by side, whatever mode you were in — so while
        // hosting the obvious-looking one was Disconnect, which dropped the
        // self-connection and left the server running. Greyed by mode now, so the pair
        // says which of the two things you are actually doing.
        this.disconnectButton = Button.builder(Component.literal("Disconnect"), b -> onDisconnect())
                .bounds(rowX, rowY + ROW_STEP * 2, halfW, FIELD_HEIGHT).build();
        onScreen(SCREEN_NETWORK, this.disconnectButton);

        this.stopHostButton = Button.builder(Component.literal("Stop hosting"), b -> onStopHosting())
                .bounds(rowX + halfW + PAD, rowY + ROW_STEP * 2, halfW, FIELD_HEIGHT).build();
        onScreen(SCREEN_NETWORK, this.stopHostButton);

        onScreen(SCREEN_NETWORK, Button.builder(Component.literal("Refresh hosts"), b -> startPoll())
                .bounds(rowX, rowY + ROW_STEP * 3, halfW, FIELD_HEIGHT).build());

        // ─── MARKET ───
        this.marketNameField = new EditBox(this.font,
                rowX, rowY, controlsW, FIELD_HEIGHT, Component.literal("Market name"));
        this.marketNameField.setMaxLength(32);
        this.marketNameField.setValue(savedMarketName());
        hint(this.marketNameField, "new market name");
        onScreen(SCREEN_MARKET, this.marketNameField);

        // Positions are assigned per frame by refreshMarketActions, since which of
        // these apply depends on the situation and gaps where a hidden button used to
        // be would read as something missing.
        this.createButton = onScreen(SCREEN_MARKET,
                Button.builder(Component.literal("Create a new market"), b -> onCreateMarket())
                .bounds(rowX, rowY, controlsW, FIELD_HEIGHT).build());

        // Sharing a market by file is how someone joins who was never online at the
        // same time as anyone holding it.
        this.importButton = onScreen(SCREEN_MARKET,
                Button.builder(Component.literal("Import one from a file"), b -> onImport())
                .bounds(rowX, rowY, controlsW, FIELD_HEIGHT).build());

        this.exportButton = onScreen(SCREEN_MARKET,
                Button.builder(Component.literal("Export to a file"), b -> onExport())
                .bounds(rowX, rowY, controlsW, FIELD_HEIGHT).build());

        this.migrateButton = onScreen(SCREEN_MARKET,
                Button.builder(Component.literal("Migrate my position"), b -> onMigrate())
                .bounds(rowX, rowY, controlsW, FIELD_HEIGHT).build());

        this.resetButton = onScreen(SCREEN_MARKET,
                Button.builder(Component.literal("Discard and start over"), b -> onReset())
                .bounds(rowX, rowY, controlsW, FIELD_HEIGHT).build());

        // Only the market's creator can author policy, so these appear for exactly one
        // person per market — see refreshMarketActions.
        this.feeField = new EditBox(this.font,
                rowX, rowY, halfW, FIELD_HEIGHT, Component.literal("Trading fee"));
        this.feeField.setMaxLength(6);
        hint(this.feeField, "e.g. 2.5");
        onScreen(SCREEN_MARKET, this.feeField);

        this.feeButton = onScreen(SCREEN_MARKET,
                Button.builder(Component.literal("Trading fee"), b -> onSetFee())
                .bounds(rowX + halfW + PAD, rowY, halfW, FIELD_HEIGHT).build());

        this.listingFeeField = new EditBox(this.font,
                rowX, rowY, halfW, FIELD_HEIGHT, Component.literal("Listing fee"));
        // Long enough for "1000/1000" — the field takes a fee, optionally followed by
        // the allowance that goes with it.
        this.listingFeeField.setMaxLength(9);
        hint(this.listingFeeField, "2 or 2/3");
        onScreen(SCREEN_MARKET, this.listingFeeField);

        this.listingFeeButton = onScreen(SCREEN_MARKET,
                Button.builder(Component.literal("Listing fee"), b -> onSetListingFee())
                .bounds(rowX + halfW + PAD, rowY, halfW, FIELD_HEIGHT).build());

        this.stipendField = new EditBox(this.font,
                rowX, rowY, halfW, FIELD_HEIGHT, Component.literal("Stipend"));
        this.stipendField.setMaxLength(6);
        hint(this.stipendField, "credits");
        onScreen(SCREEN_MARKET, this.stipendField);

        this.stipendButton = onScreen(SCREEN_MARKET,
                Button.builder(Component.literal("Set stipend"), b -> onSetStipend())
                .bounds(rowX + halfW + PAD, rowY, halfW, FIELD_HEIGHT).build());

        this.grantField = new EditBox(this.font,
                rowX, rowY, halfW, FIELD_HEIGHT, Component.literal("Welcome grant"));
        this.grantField.setMaxLength(7);
        hint(this.grantField, "credits");
        onScreen(SCREEN_MARKET, this.grantField);

        this.grantButton = onScreen(SCREEN_MARKET,
                Button.builder(Component.literal("Welcome grant"), b -> onSetGrant())
                .bounds(rowX + halfW + PAD, rowY, halfW, FIELD_HEIGHT).build());

        this.claimStipendButton = onScreen(SCREEN_MARKET,
                Button.builder(Component.literal("Claim stipend"), b -> onClaimStipend())
                .bounds(rowX, rowY, controlsW, FIELD_HEIGHT).build());

        this.addMarketButton = onScreen(SCREEN_MARKET,
                Button.builder(Component.literal("Add another market"), b -> onAddMarket())
                .bounds(rowX, rowY, controlsW, FIELD_HEIGHT).build());

        this.deleteMarketButton = onScreen(SCREEN_MARKET,
                Button.builder(Component.literal("Remove this market"), b -> onDeleteMarket())
                .bounds(rowX, rowY, controlsW, FIELD_HEIGHT).build());

        // ─── SETTINGS ───
        // Every control writes straight through to the persisted settings, which have
        // been saved since before there was anywhere to see them.
        Settings prefs = settings();

        onScreen(SCREEN_SETTINGS, toggle(rowX, rowY, controlsW, FIELD_HEIGHT,
                "Fill notices in chat", prefs != null && prefs.notifyChat(),
                on -> { if (settings() != null) settings().setNotifyChat(on); }));

        onScreen(SCREEN_SETTINGS, toggle(rowX, rowY + ROW_STEP, controlsW, FIELD_HEIGHT,
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
        Minecraft mc = Minecraft.getInstance();
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
                    .append(item == Items.AIR ? r.itemId : MinecraftIds.nameOf(item).getString());
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

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Address parsed = parseAddress(hostField.getValue().trim());
        if (parsed.error != null) {
            status = parsed.error;
            return;
        }
        final String host = parsed.host;
        final int port = parsed.port;

        // in onConnect, before spawning the thread
        try {
            MarketStateHolder.setMyHostPort(Integer.parseInt(hostPortField.getValue().trim()));
        } catch (NumberFormatException ignored) { }

        final String finalHost = host;
        final int finalPort = port;

        status = "Connecting to " + finalHost + ":" + finalPort + "...";
        UUID me = MinecraftIds.userIdOf(mc.player);
        String myName = mc.getUser().getName();

        new Thread(() -> {
            MarketStateHolder.connect(finalHost, finalPort, me, myName);
            // Only report success here. Failures already went through onRejected with
            // the actual reason and what to do about it — overwriting that with a
            // generic "Connect failed" throws away the only useful part.
            if (MarketStateHolder.isConnected()) {
                status = "Connected to " + finalHost + ":" + finalPort;
            } else if (MarketStateHolder.lastRefusedForDifferentMarket()) {
                // Shown on the render thread, which owns the overlays.
                offerNewSlot = new String[]{finalHost, String.valueOf(finalPort), finalHost + ":" + finalPort};
            }
        }, "market-connect").start();
    }

    /** Set from the connect thread when a typed address turned out to serve another market: {host, port, label}. */
    private volatile String[] offerNewSlot;

    /**
     * Offers to join a market in a new, empty slot of this world, keeping the market it is
     * using now. The empty slot is "no market": nothing to create and throw away first.
     */
    private void askToJoinInNewSlot(String host, int port, String label) {
        MarketState mine = MarketStateHolder.get();
        String current = mine == null || mine.marketName() == null ? "this world's market" : "'" + mine.marketName() + "'";
        showConfirm("Join " + label + "?",
                "It is a different market from " + current + ", which this world is using now."
                        + " Joining puts it in a new market slot of its own; " + current + " stays"
                        + " exactly as it is, and you can switch back from Market in the menu.",
                "Join", () -> {
                    if (!MarketStateHolder.addMarketSlot()) return;
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null) return;
                    try {
                        MarketStateHolder.setMyHostPort(Integer.parseInt(hostPortField.getValue().trim()));
                    } catch (NumberFormatException ignored) { }
                    UUID me = MinecraftIds.userIdOf(mc.player);
                    String myName = mc.getUser().getName();
                    status = "Joining " + label + " in a new market slot...";
                    new Thread(() -> {
                        MarketStateHolder.connect(host, port, me, myName);
                        if (MarketStateHolder.isConnected()) status = "Connected to " + label;
                    }, "market-connect").start();
                });
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
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getSingleplayerServer() == null) return;

        final String name = marketNameField.getValue().trim();
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
        // Pinned to the footer, which the alert band does not move.
        if (searchField != null) searchField.setY(footerTop() + 2);
        if (placeButton != null) placeButton.setY(footerTop() - 6 - FIELD_HEIGHT);

        if (placeButton != null && activeScreen == SCREEN_ITEM) {
            placeButton.setMessage(Component.literal(itemMode == MODE_SELL ? "List for sale"
                    : itemMode == MODE_WITHDRAW ? "Withdraw to inventory" : "Place buy order"));
            // Withdrawing has no price. Hidden rather than greyed, so the form says only
            // what the action needs.
            priceField.visible = itemMode != MODE_WITHDRAW;
            priceField.active = priceField.visible;
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
            Minecraft mcCheck = Minecraft.getInstance();
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

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getSingleplayerServer() == null) return;

        int hostPort;
        try {
            hostPort = Integer.parseInt(hostPortField.getValue().trim());
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
        String playerName = mc.getUser().getName();

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
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        MarketState mine = MarketStateHolder.get();
        if (mine == null || mine.marketId() == null) {
            status = "You hold no market to migrate — use Connect";
            return;
        }

        UUID me = MinecraftIds.userIdOf(mc.player);

        Address parsed = parseAddress(hostField.getValue().trim());
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
        String myName = mc.getUser().getName();

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
                    ? "Migrated — your balance is here. Your old orders are in Your orders to place again."
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

    /** Hosts that answered but serve another Minecraft version. They are left off the list,
     *  because they would refuse a join; counted so "nobody hosting" can say why it might
     *  not be true. */
    private static volatile int otherVersionHosts = 0;

    private static String otherVersionNote() {
        int n = otherVersionHosts;
        return n == 0 ? "" : " (" + n + " on other Minecraft versions)";
    }
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

        Minecraft mc = Minecraft.getInstance();
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
                List<PeerPoll.HostInfo> answered = PeerPoll.findHosts(others, cache, 2000);
                List<PeerPoll.HostInfo> joinable = new ArrayList<>();
                for (PeerPoll.HostInfo h : answered) {
                    if (h.servesVersion(GameVersion.current())) joinable.add(h);
                }
                otherVersionHosts = answered.size() - joinable.size();
                discovered = joinable;

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

    private String shortName(String itemId) {
        Item item = MinecraftIds.idToItem(itemId);
        return item == Items.AIR ? itemId : MinecraftIds.nameOf(item).getString();
    }

    /**
     * Cuts a string to fit a pixel width, with an ellipsis when it had to.
     *
     * Uses vanilla's own trimToWidth rather than walking characters off the end: this is
     * called for every row of two panels every frame, and the font is not fixed-width.
     */
    private String trim(String s, int maxWidth) {
        if (this.font.width(s) <= maxWidth) return s;
        int room = Math.max(0, maxWidth - this.font.width("..."));
        return this.font.plainSubstrByWidth(s, room) + "...";
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

    private void vanillaPanel(GuiGraphicsExtractor m, int x, int y, int w, int h) {
        Panels.vanillaPanel(m, x, y, w, h);
    }

    /** A titled empty box. The border makes the layout legible before the content exists. */
    private void panel(GuiGraphicsExtractor m, int x, int y, int w, int h, String title) {
        vanillaPanel(m, x + 4, y + 4, w - 8, h - 8);
        label(m, title, x + 6, y + 5, 0xFFAA00);
    }

    private void renderSettingsPlaceholder(GuiGraphicsExtractor m) {
        label(m, "Notifications", listX + 4, panelTop(), 0xFFAA00);

        String body = "Get a notification when one of your resting orders fills. "
                + "The limit batches them once they come faster than the limit on a busy market.";

        int y = panelTop() + 14;
        for (FormattedCharSequence line : this.font.split(Component.literal(body), listW - 12)) {
            Draw.text(m, this.font, line, listX + 4, y, 0xC0C0C0);
            y += 10;
        }

        y += 8;
        label(m, trim("Saved for " + Minecraft.getInstance().getUser().getName(), listW - 8),
                listX + 4, y, 0x707070);
        label(m, trim("The hosting port lives on Network.", listW - 8), listX + 4, y + 10, 0x707070);

        // Your identity is a file, and it doesn't follow you to a new computer. Moving
        // machines without it makes you a stranger to every market you were part of —
        // same username, same UUID, unrecognised key. Cheaper to say so than to debug.
        // It used to run along the foot of every screen; it belongs with your settings.
        // Under the controls on the right, which have room to spare; the left column is
        // full with the notification text.
        Path identity = MarketStateHolder.identityPath();
        if (identity != null) {
            int iy = rowY + ROW_STEP * 3 + 4 + alertBandH();
            if (iy + 9 <= panelBottom()) label(m, "Your identity", rowX, iy, 0xFFAA00);
            for (FormattedCharSequence line : this.font.split(Component.literal("config/"
                    + identity.getFileName() + " is who you are in every market. Copy it to move"
                    + " computers, and never share it."), controlsW)) {
                iy += 10;
                if (iy + 9 > panelBottom()) break;
                Draw.text(m, this.font, line, rowX, iy, 0xC0C0C0);
            }
        }
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
        Minecraft mc = Minecraft.getInstance();
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
        String raw = feeField.getValue().trim();
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
        String raw = listingFeeField.getValue().trim();
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
        String raw = grantField.getValue().trim();
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
        Minecraft mc = Minecraft.getInstance();
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
        String raw = stipendField.getValue().trim();
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
                amount, every, market.listingFee(), market.stipendClaimants());
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
        Minecraft mc = Minecraft.getInstance();
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
        Minecraft mc = Minecraft.getInstance();
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
            feeField.setValue("");
            listingFeeField.setValue("");
            stipendField.setValue("");
            grantField.setValue("");
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
    private int place(Button button, boolean shown, int y) {
        if (button == null) return y;
        boolean inView = y >= panelTop() && y + FIELD_HEIGHT <= panelBottom();
        button.visible = shown && inView;
        button.active = button.visible;
        if (!shown) return y;
        button.setY(y);
        return y + ROW_STEP;
    }

    /** The same for a text field, which is positioned rather than placed. */
    private void placeField(EditBox field, boolean shown, int y) {
        if (field == null) return;
        boolean inView = y >= panelTop() && y + FIELD_HEIGHT <= panelBottom();
        field.visible = shown && inView;
        field.active = field.visible;
        if (shown) field.setY(y);
    }

    /** What is going on, in words, beside the actions that answer it. */
    private void renderMarketGuidance(GuiGraphicsExtractor m, int x) {
        MarketState market = MarketStateHolder.get();
        int situation = marketSituation();
        PeerPoll.HostInfo foreign = foreignHost();

        String heading;
        String body;

        switch (situation) {
            case MS_DAMAGED:
                heading = "This market's log is damaged";
                String reason = MarketStateHolder.damageReason();
                body = (reason == null ? "The file is damaged."
                        : reason.endsWith(".") ? reason : reason + ".")
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
                heading = "Your copy has forked";
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
                        + " everybody at once. What is on offer is under Browse, and your"
                        + " open orders under Your orders. You can also export a copy here, for somebody"
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
        for (FormattedCharSequence line : this.font.split(Component.literal(body), listW - 12)) {
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
            for (FormattedCharSequence line : this.font.split(
                    Component.literal(advice), listW)) {
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
    private void guideLine(GuiGraphicsExtractor m, FormattedCharSequence line, int x, int y, int colour) {
        if (y < panelTop() || y + 9 > panelBottom()) return;
        Draw.text(m, this.font, line, x, y, colour);
    }

    /** The same for a plain string. */
    private void guideLabel(GuiGraphicsExtractor m, String text, int x, int y, int colour) {
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
    private int renderMarketFacts(GuiGraphicsExtractor m, int x, int y) {
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
                Minecraft mc = Minecraft.getInstance();
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
            Minecraft mc = Minecraft.getInstance();
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
        Minecraft mc = Minecraft.getInstance();
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
    private int renderMarketSlots(GuiGraphicsExtractor m, int x, int y) {
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
    private int wrapped(GuiGraphicsExtractor m, String text, int x, int y, int colour) {
        for (FormattedCharSequence line : this.font.split(
                Component.literal(text), listW - 12)) {
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

    private static String shortItem(String itemId) {
        int colon = itemId.indexOf(':');
        return colon < 0 ? itemId : itemId.substring(colon + 1);
    }

    /**
     * Re-places one carried-over order at its original price.
     *
     * A plain PlaceOrder — the items and credits are already in the ledger from the
     * migration, so there is nothing to deposit and no special path for this.
     */
    private void replaceOrder(MarketStateHolder.OldOrder o) {
        if (!requireConnected()) return;
        Minecraft mc = Minecraft.getInstance();
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

    /**
     * The y is taken from discoveryStartY rather than passed in, so it cannot drift
     * from the hit test that reads the same function — which is exactly how clicking a
     * host came to do nothing.
     */
    private void renderDiscovery(GuiGraphicsExtractor matrices, int px,
                                 double mouseX, double mouseY) {
        int py = discoveryStartY();
        // Only when the re-place box is genuinely on top of this. It used to stand
        // aside whenever the box existed at all, from when the box lived in the left
        // panel — but it sits in the third column wherever there is one, so the host
        // list was being hidden by something that was not covering it. The clicks kept
        // landing, which made the rows invisible and still joinable.
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
            label(matrices, "  nobody hosting" + otherVersionNote(), x, y, 0x808080);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
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
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
        // An overlay is modal — nothing behind it is reachable until it's answered.
        if (!overlays.isEmpty()) {
            return button == InputConstants.MOUSE_BUTTON_LEFT ? overlayClicked(mouseX, mouseY) : true;
        }

        // The picker is modal, like an overlay.
        if (pickerOpen) {
            return button != InputConstants.MOUSE_BUTTON_LEFT || pickerClicked(mouseX, mouseY);
        }

        // The menu sits above everything else, so it gets first refusal on a click.
        if (button == InputConstants.MOUSE_BUTTON_LEFT && menuClicked(mouseX, mouseY)) return true;

        // Drawn over the panels, so it claims clicks ahead of them too.
        if (button == InputConstants.MOUSE_BUTTON_LEFT && alertClicked(mouseX, mouseY)) return true;

        // Any click acknowledges the recovery note — it describes something already
        // done, so it does not need to keep occupying a line.
        if (button == InputConstants.MOUSE_BUTTON_LEFT) MarketStateHolder.clearRecoveryNote();

        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            // Picking from what you're carrying is the fastest way to choose what to sell,
            // and it never involves knowing an item's registry id.
            InventoryBridge.Holding held = sellRowAt(mouseX, mouseY);
            if (held != null) {
                openItem(held.item, MODE_SELL);
                return true;
            }
            if (browseClicked(mouseX, mouseY)) return true;
            if (itemViewClicked(mouseX, mouseY)) return true;
            if (ordersClicked(mouseX, mouseY)) return true;
            if (historyClicked(mouseX, mouseY)) return true;
        }

        // Before the host list, and guarded to the Market tab: the two lists both live
        // in the left panel and only one of them is ever drawn.
        if (button == InputConstants.MOUSE_BUTTON_LEFT && slotClicked(mouseX, mouseY)) return true;

        // Only where the host list is actually drawn — same condition renderDiscovery
        // uses, including the re-place box covering it. Hit-testing a row nobody can
        // see joins a host by accident, which is what happened here.
        if (button == InputConstants.MOUSE_BUTTON_LEFT && !polling && activeScreen == SCREEN_NETWORK) {
            Minecraft mc = Minecraft.getInstance();
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
        return super.mouseClicked(click, doubled);
    }

    private void joinHost(PeerPoll.HostInfo h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Joining a host that serves another market than the one this world is using
        // would be refused. The way in that costs nothing is a new, empty market slot:
        // offered here, rather than left for the refusal message to explain afterwards.
        MarketState mine = MarketStateHolder.get();
        if (mine != null && mine.marketId() != null && h.reply.marketId != null
                && !mine.marketId().toString().equals(h.reply.marketId)) {
            askToJoinInNewSlot(h.peer.address, h.peer.port,
                    h.reply.marketName != null ? "'" + h.reply.marketName + "'" : h.reply.hostName);
            return;
        }

        try {
            MarketStateHolder.setMyHostPort(Integer.parseInt(hostPortField.getValue().trim()));
        } catch (NumberFormatException ignored) { }

        UUID me = MinecraftIds.userIdOf(mc.player);
        String myName = mc.getUser().getName();
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
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { status = "No player"; return null; }

        long qty, price;
        try {
            qty = Long.parseLong(amountField.getValue().trim());
            price = Long.parseLong(priceField.getValue().trim());
        } catch (NumberFormatException e) {
            status = "Amount and price must be whole numbers";
            return null;
        }
        if (qty <= 0 || price <= 0) { status = "Amount and price must be positive"; return null; }
        if (qty > MAX_QTY) { status = "Amount too large (max " + MAX_QTY + ")"; return null; }

        Item item = MinecraftIds.itemFromName(itemField.getValue().trim());
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

        Minecraft mc = Minecraft.getInstance();

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
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { status = "No player"; return; }

        long qty;
        try {
            qty = Long.parseLong(amountField.getValue().trim());
        } catch (NumberFormatException e) {
            status = "Amount must be a whole number";
            return;
        }
        if (qty <= 0) { status = "Amount must be positive"; return; }
        if (qty > MAX_QTY) { status = "Amount too large"; return; }

        Item item = MinecraftIds.itemFromName(itemField.getValue().trim());
        if (item == Items.AIR) { status = "Unknown item"; return; }

        Event.Withdraw w = new Event.Withdraw();
        w.userId = MinecraftIds.userIdOf(mc.player);
        w.itemId = MinecraftIds.itemToId(item);
        w.quantity = qty;
        w.timestamp = System.currentTimeMillis();

        MarketStateHolder.Submission s = MarketStateHolder.submit(w);
        clearAmountIfSent(s);
        if (s.pending) {
            status = "Withdraw sent...";
        } else if (s.accepted) {
            status = "Withdrew " + qty;
        } else {
            status = "Rejected: " + s.reason;
        }
    }

    /**
     * Cancels one of your orders, picked from its row rather than typed in by number.
     *
     * The old form wanted an order id and the item it belonged to, so cancelling meant
     * reading a number off the book and copying it across. The row already knows both.
     */
    private void cancelOrder(Order o) {
        if (!requireConnected()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Event.CancelOrder c = new Event.CancelOrder();
        c.userId = MinecraftIds.userIdOf(mc.player);
        c.itemId = o.itemID();
        c.orderId = o.orderId();
        c.isBid = o.isBid();
        c.timestamp = System.currentTimeMillis();

        report(MarketStateHolder.submit(c),
                "Cancelled " + (o.isBid() ? "buy" : "sell") + " of " + o.volume() + " "
                        + shortName(o.itemID()) + " at " + o.value(),
                "Cancel sent...");
    }

    /**
     * After an order or a withdrawal is sent, the amount goes. Left in the box, the form went
     * on judging it against the balance it had just changed: withdraw everything and it said
     * "That is more than you have there" in red, sell everything you carry and it said "You
     * are carrying 0". Neither was wrong about the numbers; both read as a failure.
     */
    private void clearAmountIfSent(MarketStateHolder.Submission s) {
        if (s.pending || s.accepted) amountField.setValue("");
    }

    private void report(MarketStateHolder.Submission s, String successMsg, String pendingMsg) {
        clearAmountIfSent(s);
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

    /**
     * No blur and no darkening behind the market.
     *
     * The panel is translucent black so the world shows through it, the way it does behind
     * chat. Vanilla's background blurs and dims the whole world first, which turned that
     * into a grey smear and made the panel read as a box on a box.
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // A light dim, so the world stays visible but quiets down behind the panel.
        context.fill(0, 0, this.width, this.height, 0x48000000);
    }

    /** A dialog's frame: the panel's black with a hairline edge, opaque enough to read on top of it. */
    private void darkBox(GuiGraphicsExtractor m, int x, int y, int w, int h) {
        m.fill(x, y, x + w, y + h, 0xF0080808);
        m.fill(x, y, x + w, y + 1, PANEL_LINE);
        m.fill(x, y + h - 1, x + w, y + h, PANEL_LINE);
        m.fill(x, y, x + 1, y + h, PANEL_LINE);
        m.fill(x + w - 1, y, x + w, y + h, PANEL_LINE);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor matrices, int mouseX, int mouseY, float delta) {
        // No renderBackground here. Since 1.21 the game's own entry point (renderWithTooltip)
        // draws the background before it calls render, and drawing it a second time blurs
        // the frame twice, which the game refuses with an exception. And it draws nothing:
        // see the override below.

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

        String[] offer = offerNewSlot;
        if (offer != null) {
            offerNewSlot = null;
            askToJoinInNewSlot(offer[0], Integer.parseInt(offer[1]), offer[2]);
        }

        renderFrame(matrices, mouseX, mouseY);

        int listX = this.listX;

        // The setup screens keep their two columns, divided by a line rather than boxed.
        if (isSetupScreen(activeScreen)) {
            divider(matrices, rowX - PAD - COL_GAP / 2);
        }

        // Only the current destination's left column, and everything in it placed from
        // panelTop rather than from the control rows beside it.
        if (activeScreen == SCREEN_BROWSE) {
            renderBrowse(matrices, mouseX, mouseY);
        } else if (activeScreen == SCREEN_SELL) {
            renderSell(matrices, mouseX, mouseY);
        } else if (activeScreen == SCREEN_ITEM) {
            renderItemView(matrices, mouseX, mouseY);
        } else if (activeScreen == SCREEN_ORDERS) {
            renderYourOrders(matrices, mouseX, mouseY);
        } else if (activeScreen == SCREEN_HISTORY) {
            renderHistory(matrices, mouseX, mouseY);
        } else if (activeScreen == SCREEN_LEADERBOARD) {
            renderLeaderboard(matrices, mouseX, mouseY);
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
        } else {
            renderSettingsPlaceholder(matrices);
        }

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
        //
        // Just under the panel rather than at the foot of the window. With the world no
        // longer blurred behind the market, the bottom of the window is the hotbar.
        int footerW = this.width - listX - 8;
        int below = panelOuterBottom() + 4;

        // Each on a dark strip: just under the panel is over the hearts and the hotbar now
        // that the world shows through, and bare text there was hard to read.
        if (!status.isEmpty()) {
            String shown = trim(status, footerW - 8);
            matrices.fill(listX, below - 2, listX + this.font.width(shown) + 8, below + 10, 0xC0000000);
            label(matrices, shown, listX + 4, below, 0xFFDD66);
            below += 13;
        }

        String note = MarketStateHolder.recoveryNote();
        if (!note.isEmpty()) {
            String shown = trim(note, footerW - 8);
            matrices.fill(listX, below - 2, listX + this.font.width(shown) + 8, below + 10, 0xC0000000);
            label(matrices, shown, listX + 4, below, 0x88CCFF);
        }

        // The text fields are registered with addButton now, so super.render draws
        // them — and skips the ones belonging to a tab that isn't showing, which
        // hand-rolled render calls could not do.
        super.extractRenderState(matrices, mouseX, mouseY, delta);

        // Dead last, after super.render and every panel. A tooltip drawn where it was
        // requested would be painted over by whatever came next, which is the whole
        // reason it is queued rather than drawn in place.
        if (hoverTip != null) {
            // Wrapped to a width that reads comfortably. One unbroken line ran off the side of
            // the screen on the longer explanations.
            List<FormattedCharSequence> tipLines = new ArrayList<>();
            for (String paragraph : hoverTip.split("\n")) {
                tipLines.addAll(this.font.split(Component.literal(paragraph), 220));
            }
            matrices.setTooltipForNextFrame(this.font, tipLines, mouseX, mouseY);
            hoverTip = null;
        }

        renderMenu(matrices, mouseX, mouseY);
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
    private int alertTop() { return rowY - 6 - HEADER_H; }

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
        for (List<AbstractWidget> widgets : screenWidgets) {
            for (AbstractWidget w : widgets) w.setY(w.getY() + delta);
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
            // Short enough for one line: the whole sentence ran off the panel, losing its end
            // and the "open Market" that says where the answer is. Market has it in full.
            String who = split.hostName == null ? "a host" : split.hostName;
            out.add(new Alert(split.splitAt >= 0
                    ? "FORKED: split from " + who + " after event " + split.splitAt
                    : "FORKED: " + who + " is on another branch",
                    0xFF8844, SCREEN_MARKET));
        }

        // The orders from a market you left used to sit in a box over the screen, so they
        // could not be missed. They live in Your orders now, and this is what keeps them
        // from being missed there.
        int waiting = MarketStateHolder.pendingReplace().size();
        if (waiting > 0) {
            out.add(new Alert(waiting + (waiting == 1 ? " order" : " orders")
                    + " from the market you left to place again", 0xFFDD66, SCREEN_ORDERS));
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
        return this.font.width(full) + 10 <= contentW - 8 ? full : a.text;
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
                Math.min(this.font.width(text) + 10, max), ALERT_ROW_H};
    }

    private void renderAlerts(GuiGraphicsExtractor m, int mouseX, int mouseY) {
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
            m.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], hot ? 0xC0000000 : 0x90000000);
            m.fill(r[0], r[1], r[0] + 2, r[1] + r[3], 0xFF000000 | a.colour);
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

    private void renderConnectionStatus(GuiGraphicsExtractor matrices, int x, int y) {
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

    private static final int INV_ROW_H = 20;

    // ─────────── worked-out figures, kept until the market changes ───────────
    //
    // The screen redraws every frame, and everything it shows is worked out from the
    // market: rows, the book by price, quotes, profit, the leaderboard. On a busy server
    // that is hundreds of sums sixty times a second for a market that changes a few times
    // a second at most. So each figure is kept until the market's version moves (an event
    // was applied), a different market is loaded, or a minute passes — the last for the
    // figures that read the clock, like "5 min ago" and a day's change.

    private final java.util.Map<String, Object> viewCache = new java.util.HashMap<>();
    private MarketState cachedFor;
    private long cachedVersion = -1;
    private long cachedMinute = -1;

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, java.util.function.Supplier<T> compute) {
        MarketState market = MarketStateHolder.get();
        long version = market == null ? -1 : market.version();
        long minute = System.currentTimeMillis() / 60_000;
        if (market != cachedFor || version != cachedVersion || minute != cachedMinute) {
            viewCache.clear();
            cachedFor = market;
            cachedVersion = version;
            cachedMinute = minute;
        }
        Object value = viewCache.get(key);
        if (value == null) {
            value = compute.get();
            if (value != null) viewCache.put(key, value);
        }
        return (T) value;
    }

    /** An item's row as anyone sees it (no "yours" figures). */
    private MarketView.ItemRow rowCached(String itemId) {
        MarketState market = MarketStateHolder.get();
        if (market == null || itemId == null) return null;
        return cached("row|" + itemId, () -> MarketView.row(market, null, itemId, System.currentTimeMillis()));
    }

    private MarketView.Depth depthCached(String itemId, int levels) {
        MarketState market = MarketStateHolder.get();
        return cached("depth|" + itemId + "|" + levels, () -> MarketView.depth(market, myId(), itemId, levels));
    }

    private List<Order> yourOrdersCached() {
        MarketState market = MarketStateHolder.get();
        if (market == null) return Collections.emptyList();
        return cached("orders", () -> MarketView.yourOrders(market, myId()));
    }

    // ─────────── Browse ───────────
    //
    // Every item at once, with what it costs to buy right now, what selling earns right
    // now, and what it last went for — the question you have when you sit down. One row
    // per item, the icon and its name with a grey line under it, and the prices on the
    // right. Clicking one opens its view. The search box in the footer narrows the list,
    // and offers to look through every item in the game when nothing here matches.

    private static final int ROW_H = 20;
    private static final int PRICE_COL_W = 52;
    private static final int DAY_COL_W = 34;

    private int browseX() { return listX + 6; }
    private int browseRight() { return listX + contentW - 6; }
    /** Below the column titles. */
    private int browseListTop() { return panelTop() + 12; }

    private String searchText() {
        return searchField == null ? "" : searchField.getValue().trim().toLowerCase(java.util.Locale.ROOT);
    }

    private List<MarketView.ItemRow> browseRows() {
        MarketState market = MarketStateHolder.get();
        if (market == null) return Collections.emptyList();
        List<MarketView.ItemRow> rows = cached("browse", () -> {
            List<MarketView.ItemRow> all = MarketView.browse(market, myId(), null, System.currentTimeMillis());
            if (!MarketStateHolder.sharedMarket()) return all;
            // A market shared across versions: what this game has, and anything you hold
            // anyway (deposited from another version), which you can still sell from here.
            UUID me = myId();
            List<MarketView.ItemRow> here = new ArrayList<>();
            for (MarketView.ItemRow r : all) {
                if (ItemVersions.availableIn(r.itemId, GameVersion.current())
                        || (me != null && market.itemBalances().getBalance(me, r.itemId) > 0)) {
                    here.add(r);
                }
            }
            return here;
        });
        String q = searchText();
        if (q.isEmpty()) return rows;
        List<MarketView.ItemRow> out = new ArrayList<>();
        for (MarketView.ItemRow r : rows) {
            if (shortName(r.itemId).toLowerCase(java.util.Locale.ROOT).contains(q)) out.add(r);
        }
        return out;
    }

    /** The extra row at the end of a search, offering every item in the game. */
    private boolean browseOffersSearchAll() {
        return !searchText().isEmpty();
    }

    private static UUID myId() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : MinecraftIds.userIdOf(mc.player);
    }

    /** One list row's ground: a faint wash under the cursor, and a hairline between rows. */
    private void rowGround(GuiGraphicsExtractor m, int x, int y, int right, boolean hot) {
        if (hot) m.fill(x - 4, y, right + 4, y + ROW_H, 0x28FFFFFF);
        m.fill(x - 4, y + ROW_H - 1, right + 4, y + ROW_H, 0x14FFFFFF);
    }

    /** An item's icon on its own, the way the list rows show it. */
    private void rowIcon(GuiGraphicsExtractor m, String itemId, int x, int y) {
        Item item = MinecraftIds.idToItem(itemId);
        if (item != Items.AIR) drawIcon(m, new ItemStack(item), x, y + 2, null);
    }

    private void renderBrowse(GuiGraphicsExtractor m, int mouseX, int mouseY) {
        int x = browseX();
        int right = browseRight();
        List<MarketView.ItemRow> rows = browseRows();
        int top = browseListTop();
        boolean searchAll = browseOffersSearchAll();

        if (rows.isEmpty() && !searchAll) {
            label(m, "Nothing is trading yet.", x, top, 0xC0C0C0);
            label(m, trim("Search for an item below to make the first offer.", right - x), x, top + 12, 0x808080);
            return;
        }

        int dayR = right, lastR = dayR - DAY_COL_W, sellR = lastR - PRICE_COL_W, buyR = sellR - PRICE_COL_W;
        label(m, "Item", x, top - 11, 0x808080);
        labelRight(m, "Buy now", buyR, top - 11, 0x808080);
        labelRight(m, "Sell now", sellR, top - 11, 0x808080);
        labelRight(m, "Last", lastR, top - 11, 0x808080);
        labelRight(m, "Day", dayR, top - 11, 0x808080);

        int viewH = panelBottom() - top;
        int count = rows.size() + (searchAll ? 1 : 0);
        noteScrollable("browse", x, top, right - x, viewH, count * ROW_H, mouseX, mouseY);
        scrollbar(m, "browse", right + 3, top, viewH, count * ROW_H);
        int y = top - scrollOf("browse");
        int nameW = buyR - PRICE_COL_W - x - 22;

        beginClip(m, x - 4, top, right - x + 8, viewH);
        for (MarketView.ItemRow r : rows) {
            boolean hot = mouseX >= x && mouseX < right && mouseY >= y && mouseY < y + ROW_H
                    && mouseY >= top && mouseY < top + viewH;
            rowGround(m, x, y, right, hot);
            rowIcon(m, r.itemId, x, y);

            label(m, trim(shortName(r.itemId), nameW), x + 20, y + 2, 0xFFFFFF);
            String sub = r.youHold > 0 ? r.youHold + " in item-wallet"
                    : r.recentVolume > 0 ? r.recentVolume + " traded" : "no trades yet";
            if (r.yourOrders > 0) sub += " · " + r.yourOrders + (r.yourOrders == 1 ? " order" : " orders") + " yours";
            label(m, trim(sub, nameW), x + 20, y + 11, 0x808080);

            labelRight(m, MarketView.price(r.buyNow), buyR, y + 6, 0xFF8888);
            labelRight(m, MarketView.price(r.sellNow), sellR, y + 6, 0x88FF88);
            labelRight(m, MarketView.price(r.last), lastR, y + 6, 0xFFFFFF);
            String day = MarketView.change(r.dayChangePercent);
            int dayColour = r.dayChangePercent == null || Math.round(r.dayChangePercent) == 0 ? 0x909090
                    : r.dayChangePercent > 0 ? 0x55FF55 : 0xFF5555;
            labelRight(m, day, dayR, y + 6, dayColour);
            y += ROW_H;
        }
        if (searchAll) {
            boolean hot = mouseX >= x && mouseX < right && mouseY >= y && mouseY < y + ROW_H
                    && mouseY >= top && mouseY < top + viewH;
            rowGround(m, x, y, right, hot);
            label(m, trim("Look through every item for \"" + searchField.getValue().trim() + "\"", right - x - 20),
                    x + 20, y + 6, hot ? 0xFFFFFF : ACCENT);
        }
        endClip(m);
    }

    /** A click on Browse's list: a row opens that item, the last row searches every item. */
    private boolean browseClicked(double mouseX, double mouseY) {
        if (activeScreen != SCREEN_BROWSE) return false;
        int top = browseListTop();
        if (mouseX < browseX() || mouseX >= browseRight() || mouseY < top || mouseY >= panelBottom()) return false;
        List<MarketView.ItemRow> rows = browseRows();
        int index = (int) ((mouseY - top + scrollOf("browse")) / ROW_H);
        if (index >= 0 && index < rows.size()) {
            openItem(MinecraftIds.idToItem(rows.get(index).itemId), MODE_BUY);
            return true;
        }
        if (index == rows.size() && browseOffersSearchAll()) {
            openPicker();
            pickerQuery = searchField.getValue().trim();
            return true;
        }
        return false;
    }

    /**
     * A thin bar at a list's right edge when it holds more than shows. Rows past the bottom
     * of a panel are simply not drawn, so without it a long list looked like a short one —
     * the old order book said "scroll for more", and these lists had lost even that.
     */
    private void scrollbar(GuiGraphicsExtractor m, String key, int x, int top, int viewH, int contentH) {
        if (contentH <= viewH || viewH <= 0) return;
        m.fill(x, top, x + 2, top + viewH, 0x30FFFFFF);
        int thumbH = Math.max(8, viewH * viewH / contentH);
        int travel = viewH - thumbH;
        int maxScroll = contentH - viewH;
        int thumbY = top + (maxScroll <= 0 ? 0 : (int) ((long) travel * scrollOf(key) / maxScroll));
        m.fill(x, thumbY, x + 2, thumbY + thumbH, 0xA0000000 | ACCENT);
    }

    private void labelRight(GuiGraphicsExtractor m, String s, int right, int y, int colour) {
        label(m, s, right - this.font.width(s), y, colour);
    }

    // ─────────── Sell ───────────
    //
    // What you are carrying, each with what it would sell for right now. Selling starts
    // from what is in your pockets, so this is where it starts here too: click one and its
    // view opens ready to list it, priced at the best buyer's offer.

    private int sellListTop() { return panelTop() + 12; }

    private void renderSell(GuiGraphicsExtractor m, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int x = browseX(), right = browseRight(), top = sellListTop();

        InventoryBridge.Holdings held = InventoryBridge.held(mc.player);
        if (held.items.isEmpty()) {
            label(m, "You are not carrying anything to sell.", x, top, 0x808080);
            return;
        }
        MarketState market = MarketStateHolder.get();
        int lastR = right, sellR = lastR - PRICE_COL_W;
        label(m, "You are carrying", x, top - 11, 0x808080);
        labelRight(m, "Sells now", sellR, top - 11, 0x808080);
        labelRight(m, "Last", lastR, top - 11, 0x808080);

        int footer = held.skipped > 0 ? 11 : 0;
        int viewH = panelBottom() - top - footer;
        noteScrollable("sell", x, top, right - x, viewH, held.items.size() * ROW_H, mouseX, mouseY);
        scrollbar(m, "sell", right + 3, top, viewH, held.items.size() * ROW_H);
        int y = top - scrollOf("sell");
        int nameW = sellR - PRICE_COL_W - x - 22;
        long now = System.currentTimeMillis();

        beginClip(m, x - 4, top, right - x + 8, viewH);
        for (InventoryBridge.Holding h : held.items) {
            boolean hot = mouseX >= x && mouseX < right && mouseY >= y && mouseY < y + ROW_H
                    && mouseY >= top && mouseY < top + viewH;
            rowGround(m, x, y, right, hot);
            drawIcon(m, new ItemStack(h.item), x, y + 2, null);
            label(m, trim(MinecraftIds.nameOf(h.item).getString(), nameW), x + 20, y + 2, 0xFFFFFF);
            label(m, trim(h.count + " carried", nameW), x + 20, y + 11, 0x808080);
            if (market != null) {
                MarketView.ItemRow r = rowCached(MinecraftIds.itemToId(h.item));
                labelRight(m, MarketView.price(r.sellNow), sellR, y + 6, 0x88FF88);
                labelRight(m, MarketView.price(r.last), lastR, y + 6, 0xFFFFFF);
            }
            y += ROW_H;
        }
        endClip(m);

        if (held.skipped > 0) {
            label(m, held.skipped + " with NBT hidden", x, top + viewH + 1, 0x606060);
        }
    }

    /** Which carried item a click on the Sell list landed on, or null. */
    private InventoryBridge.Holding sellRowAt(double mouseX, double mouseY) {
        if (activeScreen != SCREEN_SELL) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        int top = sellListTop();
        if (mouseX < browseX() || mouseX >= browseRight() || mouseY < top || mouseY >= panelBottom()) return null;
        List<InventoryBridge.Holding> items = InventoryBridge.held(mc.player).items;
        int index = (int) ((mouseY - top + scrollOf("sell")) / ROW_H);
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    // ─────────── one item ───────────
    //
    // Simple first: the market price, what buying costs right now and how many are for
    // sale at that, and what selling earns right now and how many are wanted. Two columns
    // of "price · amount" made a player work out which side was which before they could
    // read it. The book in full, a chart and the figures behind the market price are one
    // click away under Advanced, for whoever wants them.

    private int itemX() { return listX + 6; }
    private int itemRight() { return listX + itemLeftW - 6; }
    private int itemTop() { return panelTop() + 1; }
    /** The back arrow at the top left. */
    private int[] backRect() { return new int[]{itemX(), itemTop(), 12, 18}; }
    private static final int LEVEL_H = 10;

    /** Static like the screen: whoever wants the detail keeps getting it. */
    private static boolean itemAdvanced = false;

    private int[] advancedToggleRect() {
        String text = itemAdvanced ? "Simple" : "Advanced";
        int w = this.font.width(text);
        return new int[]{itemRight() - w, itemTop() + 1, w, 10};
    }

    /** Simple view: the "buy now" row (0) and the "sell now" row (1). */
    private int[] quickRowRect(int which) {
        return new int[]{itemX() - 2, itemTop() + 46 + which * 24, itemRight() - itemX() + 4, 22};
    }

    // Advanced view: chart, then a line of figures, then the book.
    private int chartTop() { return itemTop() + 24; }
    private int chartHeight() { return Math.max(18, (panelBottom() - chartTop()) * 2 / 5); }
    private int figuresY() { return chartTop() + chartHeight() + 3; }
    /** Top of the grouped book's rows. */
    private int depthTop() { return figuresY() + 12 + 11; }

    private String selectedItemId() {
        Item item = MinecraftIds.itemFromName(itemField.getValue().trim());
        return item == Items.AIR ? null : MinecraftIds.itemToId(item);
    }

    /** How many price levels fit on each side. */
    private int levelsThatFit() {
        return Math.max(1, (panelBottom() - depthTop()) / LEVEL_H);
    }

    private void renderItemView(GuiGraphicsExtractor m, int mouseX, int mouseY) {
        divider(m, formX - PAD - COL_GAP / 2);

        MarketState market = MarketStateHolder.get();
        String itemId = selectedItemId();
        int x = itemX(), right = itemRight(), y = itemTop();

        int[] back = backRect();
        boolean backHot = within(mouseX, mouseY, back);
        label(m, "<", back[0] + 3, back[1] + 5, backHot ? 0xFFFFFF : ACCENT);
        if (backHot) hoverTip = "Back to " + SCREEN_NAMES[itemOpenedFrom];

        if (market == null || itemId == null) {
            label(m, "Pick an item from Browse.", x + 16, y + 5, 0x808080);
            renderOrderForm(m, null, null, mouseX, mouseY);
            return;
        }

        UUID me = myId();
        Item item = MinecraftIds.idToItem(itemId);
        int[] toggle = advancedToggleRect();
        boolean toggleHot = within(mouseX, mouseY, toggle);
        label(m, itemAdvanced ? "Simple" : "Advanced", toggle[0], toggle[1], toggleHot ? 0xFFFFFF : ACCENT);

        int nameW = toggle[0] - x - 40;
        if (item != Items.AIR) drawIcon(m, new ItemStack(item), x + 15, y + 1, null);
        label(m, trim(shortName(itemId), nameW), x + 36, y + 1, 0xFFFFFF);
        long inMarket = me == null ? 0 : market.itemBalances().getBalance(me, itemId);
        // "Item-wallet", not "in the market": these are yours, held by the market for you,
        // and "in the market" read as though they were up for sale.
        label(m, trim(inMarket + " in item-wallet · " + carried(item) + " carried", right - x - 36),
                x + 36, y + 11, 0x909090);

        long now = System.currentTimeMillis();
        MarketView.ItemRow row = cached("myrow|" + itemId, () -> MarketView.row(market, me, itemId, now));
        if (itemAdvanced) {
            renderItemAdvanced(m, market, me, itemId, row, mouseX, mouseY);
        } else {
            renderItemSimple(m, market, me, itemId, row, mouseX, mouseY);
        }
        renderOrderForm(m, market, itemId, mouseX, mouseY);
    }

    private void renderItemSimple(GuiGraphicsExtractor m, MarketState market, UUID me, String itemId,
                                  MarketView.ItemRow row, int mouseX, int mouseY) {
        int x = itemX(), right = itemRight(), y = itemTop() + 24;

        // The market price, with the day's change beside it when there is one, and how much
        // has been changing hands — a price on three trades means less than one on three hundred.
        label(m, "Market price", x, y, 0x909090);
        MarketView.Stats st = cached("stats|" + itemId,
                () -> MarketView.stats(market, itemId, System.currentTimeMillis()));
        String volume = st.trades.isEmpty() ? "" : "Volume: " + st.volume + (st.lastDay ? " today" : " lately");
        if (!volume.isEmpty()) labelRight(m, trim(volume, (right - x) / 2), right, y, 0x909090);
        String price = row.marketPrice == MarketView.NONE ? "not traded yet" : row.marketPrice + " credits";
        label(m, price, x, y + 10, 0xFFFFFF);
        String day = MarketView.change(row.dayChangePercent);
        if (!day.isEmpty()) {
            label(m, day + " today", x + this.font.width(price) + 8, y + 10,
                    row.dayChangePercent > 0 ? 0x55FF55 : row.dayChangePercent < 0 ? 0xFF5555 : 0x909090);
        }
        if (mouseX >= x && mouseX < right && mouseY >= y && mouseY < y + 20) {
            hoverTip = "The average of the last " + MarketView.MARKET_PRICE_TRADES
                    + " trades, weighted by how much each moved. Volume is how many changed hands "
                    + (st.lastDay ? "in the last day" : "in the trades this market remembers") + ".";
        }

        // The two things a player can do right now, said in words.
        MarketView.Depth depth = depthCached(itemId, 1);
        quickRow(m, 0, "Buy now", row.buyNow,
                depth.asks.isEmpty() ? "nobody is selling yet" : depth.asks.get(0).volume + " for sale at that price",
                0xFF8888, mouseX, mouseY);
        quickRow(m, 1, "Sell now", row.sellNow,
                depth.bids.isEmpty() ? "nobody is buying yet" : depth.bids.get(0).volume + " wanted at that price",
                0x88FF88, mouseX, mouseY);

        // In place of the old live feed: just this item's last few prices, newest first.
        List<Trade> recent = market.trades().recentFor(itemId, 8);
        StringBuilder line = new StringBuilder(recent.isEmpty() ? "No trades yet" : "Recent trades:");
        for (int i = recent.size() - 1; i >= 0; i--) line.append(' ').append(recent.get(i).price);
        label(m, trim(line.toString(), right - x), x, panelBottom() - 9, 0x808080);
    }

    private void quickRow(GuiGraphicsExtractor m, int which, String what, long price, String sub, int colour,
                          int mouseX, int mouseY) {
        int[] r = quickRowRect(which);
        boolean live = price != MarketView.NONE;
        boolean hot = live && within(mouseX, mouseY, r);
        if (hot) m.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0x28FFFFFF);
        int x = itemX(), right = itemRight();
        label(m, what, x, r[1] + 2, live ? colour : 0x808080);
        if (live) labelRight(m, price + " each", right, r[1] + 2, colour);
        label(m, trim(sub, right - x), x, r[1] + 12, 0x808080);
        if (hot) hoverTip = which == 0 ? "Buy at this price" : "Sell at this price";
    }

    private void renderItemAdvanced(GuiGraphicsExtractor m, MarketState market, UUID me, String itemId,
                                    MarketView.ItemRow row, int mouseX, int mouseY) {
        int x = itemX(), right = itemRight();
        MarketView.Stats st = cached("stats|" + itemId,
                () -> MarketView.stats(market, itemId, System.currentTimeMillis()));

        // The chart: every trade the figures cover, oldest on the left.
        int cy = chartTop(), ch = chartHeight(), cw = right - x;
        m.fill(x, cy, right, cy + ch, 0x30000000);
        List<Trade> trades = st.trades.size() > 80 ? st.trades.subList(st.trades.size() - 80, st.trades.size()) : st.trades;
        if (trades.isEmpty()) {
            label(m, "No trades yet", x + 4, cy + ch / 2 - 4, 0x808080);
        } else {
            drawPriceLine(m, trades, x + 1, cy + 2, cw - 2, ch - 4, st.low, st.high);
            label(m, String.valueOf(st.high), x + 2, cy + 1, 0x707070);
            label(m, String.valueOf(st.low), x + 2, cy + ch - 9, 0x707070);
        }

        // The figures behind the market price.
        String figures = trades.isEmpty() ? "" : "low " + st.low + " · high " + st.high
                + " · " + st.volume + " traded " + (st.lastDay ? "today" : "lately")
                + (st.spread == MarketView.NONE ? "" : " · gap " + st.spread);
        label(m, trim(figures, cw), x, figuresY(), 0x909090);
        if (!figures.isEmpty() && mouseX >= x && mouseX < right && mouseY >= figuresY() && mouseY < figuresY() + 10) {
            hoverTip = "Over " + (st.lastDay ? "the last day" : "the trades this market remembers")
                    + ". The gap is between the cheapest seller and the best buyer.";
        }

        // The book in full, grouped by price.
        MarketView.Depth depth = depthCached(itemId, levelsThatFit());
        int half = cw / 2;
        int top = depthTop();
        label(m, "For sale", x, top - 11, 0xFF8888);
        label(m, "Wanted", x + half, top - 11, 0x88FF88);
        if (depth.asks.isEmpty() && depth.bids.isEmpty()) {
            label(m, trim("No orders yet. Be the first.", cw), x, top, 0x808080);
        } else {
            drawLevels(m, depth.asks, x, top, half - 4, 0xFF8888, mouseX, mouseY);
            drawLevels(m, depth.bids, x + half, top, half - 4, 0x88FF88, mouseX, mouseY);
        }
    }

    /** A line through the trade prices, drawn as steps since the font-sized grid has no diagonals. */
    private void drawPriceLine(GuiGraphicsExtractor m, List<Trade> trades, int x, int y, int w, int h, long low, long high) {
        double span = Math.max(1.0, (double) high - low);
        int n = trades.size();
        int prevX = -1, prevY = -1;
        for (int i = 0; i < n; i++) {
            int px = n == 1 ? x + w / 2 : x + (int) ((long) i * (w - 1) / (n - 1));
            int py = y + h - 1 - (int) Math.round((trades.get(i).price - low) / span * (h - 1));
            if (prevX < 0) {
                m.fill(px, py, px + 1, py + 1, 0xFF55CCFF);
            } else {
                m.fill(prevX, prevY, px + 1, prevY + 1, 0xFF55CCFF);
                m.fill(px, Math.min(prevY, py), px + 1, Math.max(prevY, py) + 1, 0xFF55CCFF);
            }
            prevX = px;
            prevY = py;
        }
    }

    private void drawLevels(GuiGraphicsExtractor m, List<MarketView.Level> levels, int x, int top, int w,
                            int colour, int mouseX, int mouseY) {
        int y = top;
        for (MarketView.Level l : levels) {
            boolean hot = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + LEVEL_H;
            if (hot) m.fill(x - 2, y - 1, x + w, y + LEVEL_H - 1, 0x30FFFFFF);
            label(m, trim(l.volume + " at " + l.price + (l.yours ? " *" : ""), w), x, y, colour);
            if (hot) {
                hoverTip = l.volume + " at " + l.price + " from " + l.orders
                        + (l.orders == 1 ? " order" : " orders") + (l.yours ? ", one of them yours" : "")
                        + ". Click to use this price.";
            }
            y += LEVEL_H;
        }
    }

    /** The price level a click landed on: {price, 1 if it is a seller's level, 0 for a buyer's}, or null. */
    private long[] levelAt(double mouseX, double mouseY) {
        if (activeScreen != SCREEN_ITEM || !itemAdvanced) return null;
        MarketState market = MarketStateHolder.get();
        String itemId = selectedItemId();
        if (market == null || itemId == null) return null;
        int x = itemX(), half = (itemRight() - x) / 2, top = depthTop();
        if (mouseY < top) return null;
        int index = (int) ((mouseY - top) / LEVEL_H);
        MarketView.Depth depth = depthCached(itemId, levelsThatFit());
        if (mouseX >= x && mouseX < x + half - 4 && index < depth.asks.size()) {
            return new long[]{depth.asks.get(index).price, 1};
        }
        if (mouseX >= x + half && mouseX < x + half * 2 - 4 && index < depth.bids.size()) {
            return new long[]{depth.bids.get(index).price, 0};
        }
        return null;
    }

    /** How many of an item the player is carrying, counted the way the carrying list counts it. */
    private static long carried(Item item) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || item == null) return 0;
        long n = 0;
        for (InventoryBridge.Holding h : InventoryBridge.held(mc.player).items) {
            if (h.item == item) n += h.count;
        }
        return n;
    }

    // ─── the order form ───

    private int[] modeRect(int mode) {
        int w = formW / MODE_NAMES.length;
        return new int[]{formX + mode * w, amountField.getY() - 18, w - 2, 14};
    }

    private void renderOrderForm(GuiGraphicsExtractor m, MarketState market, String itemId, int mouseX, int mouseY) {
        for (int i = 0; i < MODE_NAMES.length; i++) {
            int[] r = modeRect(i);
            boolean here = i == itemMode;
            boolean hot = !here && within(mouseX, mouseY, r);
            // The chosen one a lighter black than the others; nothing else needed.
            m.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], here ? 0xFF2C2C2C : hot ? 0xFF1A1A1A : 0xFF0E0E0E);
            Draw.centered(m, this.font, Component.literal(MODE_NAMES[i]), r[0] + r[2] / 2, r[1] + 3,
                    here ? 0xFFFFFF : hot ? 0xD0D0D0 : 0x808080);
        }

        label(m, "Amount", formX, amountField.getY() + 6, 0xC0C0C0);
        if (itemMode != MODE_WITHDRAW) label(m, "Price each", formX, priceField.getY() + 6, 0xC0C0C0);

        int y = priceField.getY() + 24;
        for (Object[] line : quoteLines(market, itemId)) {
            if (y + 9 > placeButton.getY() - 2) break;
            label(m, trim((String) line[0], formW), formX, y, (Integer) line[1]);
            y += 10;
        }
    }

    /**
     * What the order on the form would do, as up to three lines of {text, colour}.
     *
     * Worked out before it is sent, from this copy of the market: what it costs, what is
     * charged for it, and whether it trades now or waits. The host still decides; this is
     * so pressing the button is never a guess.
     */
    private List<Object[]> quoteLines(MarketState market, String itemId) {
        List<Object[]> out = new ArrayList<>();
        if (market == null || itemId == null) return out;
        UUID me = myId();
        long amount = parsePositive(amountField.getValue());

        if (itemMode == MODE_WITHDRAW) {
            long inMarket = me == null ? 0 : market.itemBalances().getBalance(me, itemId);
            out.add(new Object[]{"You have " + inMarket + " in your item-wallet", 0xC0C0C0});
            out.add(amount > inMarket
                    ? new Object[]{"That is more than you have there", 0xFF6666}
                    : new Object[]{"Moves them to your inventory", 0x808080});
            return out;
        }

        long price = parsePositive(priceField.getValue());
        if (amount <= 0 || price <= 0) {
            out.add(new Object[]{"Type an amount and a price", 0x808080});
            return out;
        }
        boolean buying = itemMode == MODE_BUY;
        MarketView.Quote q = cached("quote|" + itemId + "|" + buying + "|" + price + "|" + amount,
                () -> MarketView.quote(market, me, itemId, buying, price, amount));
        if (!q.sensible) {
            out.add(new Object[]{"Those numbers are too large", 0xFF6666});
            return out;
        }

        if (buying) {
            out.add(new Object[]{"Costs " + q.total + " credits"
                    + (q.listingFee > 0 ? " + " + q.listingFee + " to list" : ""),
                    q.affordable ? 0xC0C0C0 : 0xFF6666});
            if (!q.affordable) {
                out.add(new Object[]{"You have " + market.wallets().getBalance(me) + " credits", 0xFF6666});
            }
        } else {
            long fees = q.tradingFee + q.listingFee;
            out.add(new Object[]{"Earns " + (q.total - q.tradingFee) + " credits"
                    + (fees > 0 ? " after " + fees + " in fees" : ""), 0xC0C0C0});
            long carrying = carried(MinecraftIds.idToItem(itemId));
            if (carrying < amount) {
                out.add(new Object[]{"You are carrying " + carrying, 0xFF6666});
            } else if (!q.affordable) {
                out.add(new Object[]{"Listing costs " + q.listingFee + ", you have "
                        + market.wallets().getBalance(me), 0xFF6666});
            }
        }

        String fills = q.fillsNow <= 0 ? "Waits on the book until it matches"
                : q.fillsNow >= amount ? "Trades straight away"
                : q.fillsNow + " trade now, " + (amount - q.fillsNow) + " wait";
        out.add(new Object[]{fills, q.fillsNow > 0 ? 0x88FF88 : 0x808080});
        return out.size() > 3 ? out.subList(0, 3) : out;
    }

    private static long parsePositive(String text) {
        try {
            long v = Long.parseLong(text.trim());
            return v > 0 ? v : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean itemViewClicked(double mouseX, double mouseY) {
        if (activeScreen != SCREEN_ITEM) return false;
        if (within(mouseX, mouseY, backRect())) {
            selectScreen(itemOpenedFrom);
            return true;
        }
        if (selectedItemId() != null && within(mouseX, mouseY, advancedToggleRect())) {
            itemAdvanced = !itemAdvanced;
            return true;
        }
        if (!itemAdvanced && MarketStateHolder.get() != null && selectedItemId() != null) {
            MarketView.ItemRow row = rowCached(selectedItemId());
            for (int which = 0; which < 2; which++) {
                long price = which == 0 ? row.buyNow : row.sellNow;
                if (price != MarketView.NONE && within(mouseX, mouseY, quickRowRect(which))) {
                    itemMode = which == 0 ? MODE_BUY : MODE_SELL;
                    priceField.setValue(String.valueOf(price));
                    return true;
                }
            }
        }
        for (int i = 0; i < MODE_NAMES.length; i++) {
            if (within(mouseX, mouseY, modeRect(i))) {
                setMode(i);
                return true;
            }
        }
        long[] level = levelAt(mouseX, mouseY);
        if (level != null) {
            // A seller's price is one you can buy at, and a buyer's one you can sell at.
            itemMode = level[1] == 1 ? MODE_BUY : MODE_SELL;
            priceField.setValue(String.valueOf(level[0]));
            return true;
        }
        return false;
    }

    // ─────────── Your orders ───────────
    //
    // Everything you have open, each with its own cancel, and — straight after moving
    // markets — the orders from the market you left, ready to place again. Those used to
    // sit in a box laid over whichever column was third on the screen; they are a list of
    // your orders, so this is where they live now, with an alert pointing here.

    private static final int OE_REPLACE_HEADER = 0;
    private static final int OE_REPLACE = 1;
    private static final int OE_HEADER = 2;
    private static final int OE_ORDER = 3;
    private static final int OE_NOTE = 4;

    /** One line of the orders list, where it sits, and what it is about. */
    private static final class OrdersEntry {
        final int kind;
        final int y;
        final int h;
        final Object about;

        OrdersEntry(int kind, int y, int h, Object about) {
            this.kind = kind;
            this.y = y;
            this.h = h;
            this.about = about;
        }
    }

    private int ordersX() { return listX + 6; }
    private int ordersRight() { return listX + contentW - 6; }
    private int ordersTop() { return panelTop() + 1; }

    /**
     * The orders list laid out, scroll included.
     *
     * Both the drawing and the click test walk this, which is the only reason they cannot
     * disagree about where a row is — every drifted hit test this file has had came from
     * the two keeping their own copies of the walk.
     */
    private List<OrdersEntry> ordersLayout() {
        List<OrdersEntry> out = new ArrayList<>();
        int y = ordersTop() - scrollOf("orders");
        List<MarketStateHolder.OldOrder> old = MarketStateHolder.pendingReplace();
        if (!old.isEmpty()) {
            out.add(new OrdersEntry(OE_REPLACE_HEADER, y, 12, null));
            y += 12;
            for (MarketStateHolder.OldOrder o : old) {
                out.add(new OrdersEntry(OE_REPLACE, y, ROW_H, o));
                y += ROW_H;
            }
            y += 6;
        }
        out.add(new OrdersEntry(OE_HEADER, y, 12, null));
        y += 12;
        MarketState market = MarketStateHolder.get();
        List<Order> mine = yourOrdersCached();
        if (mine.isEmpty()) {
            out.add(new OrdersEntry(OE_NOTE, y, 12, "You have no open orders. Place one from an item in Browse."));
        }
        for (Order o : mine) {
            out.add(new OrdersEntry(OE_ORDER, y, ROW_H, o));
            y += ROW_H;
        }
        return out;
    }

    private int ordersContentHeight(List<OrdersEntry> entries) {
        if (entries.isEmpty()) return 0;
        OrdersEntry last = entries.get(entries.size() - 1);
        return last.y + last.h + scrollOf("orders") - ordersTop();
    }

    private boolean ordersVisible(OrdersEntry e) {
        return e.y >= ordersTop() && e.y + e.h <= panelBottom();
    }

    /** The cancel box at the right end of an order's row. */
    private int[] cancelRect(OrdersEntry e) {
        return new int[]{ordersRight() - 12, e.y + 4, 12, 12};
    }

    private void renderYourOrders(GuiGraphicsExtractor m, int mouseX, int mouseY) {
        MarketState market = MarketStateHolder.get();
        List<OrdersEntry> entries = ordersLayout();
        int x = ordersX(), right = ordersRight(), w = right - x;
        noteScrollable("orders", listX, frameTop(), contentW, frameH(), panelBottom() - ordersTop(),
                ordersContentHeight(entries), mouseX, mouseY);
        scrollbar(m, "orders", right + 3, ordersTop(), panelBottom() - ordersTop(), ordersContentHeight(entries));

        for (OrdersEntry e : entries) {
            if (!ordersVisible(e)) continue;
            boolean hot = mouseX >= x && mouseX < right && mouseY >= e.y && mouseY < e.y + e.h;
            switch (e.kind) {
                case OE_REPLACE_HEADER: {
                    label(m, trim("From the market you left: click one to place it again", w - 50), x, e.y, 0xFFDD66);
                    labelRight(m, "dismiss", right, e.y, hot ? 0xFFFFFF : 0xFFDD66);
                    break;
                }
                case OE_REPLACE: {
                    MarketStateHolder.OldOrder o = (MarketStateHolder.OldOrder) e.about;
                    rowGround(m, x, e.y, right, hot);
                    rowIcon(m, o.itemId, x, e.y);
                    label(m, trim((o.isBid ? "Buy " : "Sell ") + o.volume + " " + shortName(o.itemId)
                            + " at " + o.price, w - 26), x + 20, e.y + 2, 0x88CCFF);
                    // What the same order would meet here, so placing it again at the old
                    // price is a decision rather than a guess.
                    String here = "";
                    OrderBook book = market == null ? null : market.peekBook(o.itemId);
                    if (book != null) {
                        List<Order> other = o.isBid ? book.restingAsks() : book.restingBids();
                        if (!other.isEmpty()) here = (o.isBid ? "best ask here " : "best bid here ") + other.get(0).value();
                    }
                    if (!here.isEmpty()) label(m, trim(here, w - 26), x + 20, e.y + 11, 0x808080);
                    break;
                }
                case OE_HEADER: {
                    int count = yourOrdersCached().size();
                    label(m, "Your open orders (" + count + ")", x, e.y, ACCENT);
                    if (market != null && market.listingFee() > 0) {
                        labelRight(m, trim("cancelling keeps the listing fee", w / 2), right, e.y, 0x707070);
                    }
                    break;
                }
                case OE_NOTE: {
                    label(m, trim((String) e.about, w), x, e.y, 0x808080);
                    break;
                }
                default: {
                    Order o = (Order) e.about;
                    int[] c = cancelRect(e);
                    boolean cancelHot = within(mouseX, mouseY, c);
                    rowGround(m, x, e.y, right, hot && !cancelHot);
                    rowIcon(m, o.itemID(), x, e.y);
                    label(m, trim((o.isBid() ? "Buy " : "Sell ") + o.volume() + " " + shortName(o.itemID())
                            + " at " + o.value(), w - 44), x + 20, e.y + 2, o.isBid() ? 0x88FF88 : 0xFF8888);
                    MarketView.ItemRow row = market == null ? null : rowCached(o.itemID());
                    String sub = row == null ? "" : o.isBid()
                            ? "lowest seller " + MarketView.price(row.buyNow)
                            : "highest buyer " + MarketView.price(row.sellNow);
                    label(m, trim(sub, w - 44), x + 20, e.y + 11, 0x808080);
                    m.fill(c[0], c[1], c[0] + c[2], c[1] + c[3], cancelHot ? 0xC0AA2222 : 0x60000000);
                    Draw.centered(m, this.font, Component.literal("x"), c[0] + c[2] / 2, c[1] + 2,
                            cancelHot ? 0xFFFFFF : 0xFF8888);
                    if (cancelHot) hoverTip = "Cancel this order";
                    break;
                }
            }
        }
    }

    private boolean ordersClicked(double mouseX, double mouseY) {
        if (activeScreen != SCREEN_ORDERS) return false;
        int x = ordersX(), right = ordersRight();
        if (mouseX < x || mouseX >= right) return false;
        for (OrdersEntry e : ordersLayout()) {
            if (!ordersVisible(e) || mouseY < e.y || mouseY >= e.y + e.h) continue;
            switch (e.kind) {
                case OE_REPLACE_HEADER:
                    if (mouseX >= right - this.font.width("dismiss") - 4) {
                        MarketStateHolder.clearPendingReplace();
                        status = "Dismissed — your balance is unaffected";
                    }
                    return true;
                case OE_REPLACE:
                    replaceOrder((MarketStateHolder.OldOrder) e.about);
                    return true;
                case OE_ORDER: {
                    Order o = (Order) e.about;
                    if (within(mouseX, mouseY, cancelRect(e))) {
                        cancelOrder(o);
                    } else {
                        openItem(MinecraftIds.idToItem(o.itemID()), o.isBid() ? MODE_BUY : MODE_SELL);
                    }
                    return true;
                }
                default:
                    return true;
            }
        }
        return false;
    }

    // ─────────── History ───────────
    //
    // Your own trades only, newest first. The rest of the market's activity is in each
    // item's prices; a feed of everybody's orders was what made the old screen a blur.

    private static final int HISTORY_LIMIT = 200;

    /** Below the profit line. */
    private int historyTop() { return panelTop() + 1 + 14; }

    private List<MarketView.YourTrade> historyRows() {
        MarketState market = MarketStateHolder.get();
        return market == null ? Collections.<MarketView.YourTrade>emptyList()
                : cached("trades", () -> MarketView.yourTrades(market, myId(), HISTORY_LIMIT));
    }

    private void renderHistory(GuiGraphicsExtractor m, int mouseX, int mouseY) {
        MarketState market = MarketStateHolder.get();
        int x = listX + 6, right = listX + contentW - 6, w = right - x;

        // Your profit: what selling has locked in, and what you hold is worth over its cost.
        if (market != null) {
            MarketView.Pnl pnl = cached("pnl", () -> MarketView.pnl(market, myId(), System.currentTimeMillis()));
            int py = panelTop() + 1;
            String locked = "Profit " + MarketView.signed(pnl.realized);
            label(m, locked, x, py, pnlColour(pnl.realized));
            String open = "· " + MarketView.signed(pnl.open) + " on what you hold";
            label(m, trim(open, w - this.font.width(locked) - 6),
                    x + this.font.width(locked) + 6, py, 0x909090);
            if (mouseX >= x && mouseX < right && mouseY >= py && mouseY < py + 10) {
                hoverTip = "Profit: what you have made by selling, after fees and after what those"
                        + " goods cost you. Things you deposited yourself count as costing nothing."
                        + "\nOn what you hold: what your unsold goods are worth now, minus what you"
                        + " paid for them.";
            }
        }

        List<MarketView.YourTrade> rows = historyRows();
        int top = historyTop();
        if (rows.isEmpty()) {
            label(m, "You have not traded here yet.", x, top, 0x808080);
            return;
        }

        int viewH = panelBottom() - top;
        noteScrollable("history", x, top, w, viewH, rows.size() * ROW_H, mouseX, mouseY);
        scrollbar(m, "history", right + 3, top, viewH, rows.size() * ROW_H);
        int y = top - scrollOf("history");
        long now = System.currentTimeMillis();
        beginClip(m, x - 2, top, w + 4, viewH);
        for (MarketView.YourTrade t : rows) {
            boolean hot = mouseX >= x && mouseX < right && mouseY >= y && mouseY < y + ROW_H
                    && mouseY >= top && mouseY < top + viewH;
            rowGround(m, x, y, right, hot);
            rowIcon(m, t.trade.itemId, x, y);
            label(m, trim((t.bought ? "Bought " : "Sold ") + t.trade.quantity + " "
                    + shortName(t.trade.itemId) + " at " + t.trade.price, w - 26),
                    x + 20, y + 2, t.bought ? 0x88FF88 : 0xFF8888);

            String value;
            try {
                value = Math.multiplyExact(t.trade.price, t.trade.quantity) + " credits";
            } catch (ArithmeticException tooBig) {
                value = "a vast sum";
            }
            UUID other = t.bought ? t.trade.sellerId : t.trade.buyerId;
            String who = market != null && market.isBot(other)
                    ? (t.bought ? " · from " : " · to ") + market.botName(other) : "";
            label(m, trim(value + " · " + MarketView.ago(t.trade.timestamp, now) + who, w - 26),
                    x + 20, y + 11, 0x808080);
            y += ROW_H;
        }
        endClip(m);
    }

    // ─────────── Leaderboard ───────────
    //
    // Everyone in this market, best profit first. Profit here is what selling has locked in,
    // less listing fees; the grey figure beside it is what they still hold, over its cost.
    // Every copy of the market works these out from the same log, so this list is the same
    // for everybody who looks.

    private int leaderTop() { return panelTop() + 12; }

    /**
     * A name for an identity: you, a bot by the name the market gives it, a player in this
     * world, a host this game has met, or the start of their id. The log records keys, not
     * names, so somebody nobody here has seen can only be shown by their id.
     */
    private String nameOf(UUID id) {
        if (id == null) return "?";
        if (id.equals(myId())) return "You";
        MarketState market = MarketStateHolder.get();
        if (market != null && market.isBot(id)) return market.botName(id);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            for (net.minecraft.world.entity.player.Player p : mc.level.players()) {
                if (id.equals(MinecraftIds.userIdOf(p))) return p.getName().getString();
            }
        }
        // What the host told us, which covers a dedicated server: it shares names but not
        // who connected from where.
        String told = MarketStateHolder.knownName(id);
        if (told != null) return told;
        PeerCache peers = MarketStateHolder.peers();
        if (peers != null) {
            for (PeerCache.Peer peer : peers.all()) {
                if (id.toString().equals(peer.userId) && peer.displayName != null && !peer.displayName.isEmpty()) {
                    return peer.displayName;
                }
            }
        }
        return "Player " + id.toString().substring(0, 6);
    }

    private void renderLeaderboard(GuiGraphicsExtractor m, int mouseX, int mouseY) {
        MarketState market = MarketStateHolder.get();
        int x = listX + 6, right = listX + contentW - 6, top = leaderTop();
        if (market == null) return;
        List<MarketView.Pnl> board = cached("leaders",
                () -> MarketView.leaderboard(market, System.currentTimeMillis()));
        if (board.isEmpty()) {
            label(m, "Nobody has traded here yet.", x, top, 0x808080);
            return;
        }

        int openR = right, profitR = openR - 64;
        label(m, "Trader", x + 22, top - 11, 0x808080);
        labelRight(m, "Profit", profitR, top - 11, 0x808080);
        labelRight(m, "Holding", openR, top - 11, 0x808080);
        if (mouseY >= top - 11 && mouseY < top - 1 && mouseX >= profitR - 60 && mouseX < right) {
            hoverTip = "Profit: what they have made by selling, after fees and after what those"
                    + " goods cost them."
                    + "\nHolding: what their unsold goods are worth now, minus what they paid for them.";
        }

        int viewH = panelBottom() - top;
        noteScrollable("leaders", x, top, right - x, viewH, board.size() * 12, mouseX, mouseY);
        scrollbar(m, "leaders", right + 3, top, viewH, board.size() * 12);
        int y = top - scrollOf("leaders");
        UUID me = myId();
        beginClip(m, x - 4, top, right - x + 8, viewH);
        for (int i = 0; i < board.size(); i++) {
            MarketView.Pnl p = board.get(i);
            boolean mine = p.who.equals(me);
            if (mine) m.fill(x - 4, y - 1, right + 4, y + 11, 0x284CD07A);
            labelRight(m, "#" + (i + 1), x + 18, y + 1, 0x707070);
            label(m, trim(nameOf(p.who), profitR - 60 - x - 22), x + 22, y + 1, mine ? 0xFFFFFF : 0xC0C0C0);
            labelRight(m, MarketView.signed(p.realized), profitR, y + 1, pnlColour(p.realized));
            labelRight(m, MarketView.signed(p.open), openR, y + 1, 0x909090);
            y += 12;
        }
        endClip(m);
    }

    private boolean historyClicked(double mouseX, double mouseY) {
        if (activeScreen != SCREEN_HISTORY) return false;
        int top = historyTop();
        if (mouseX < listX + 6 || mouseX >= listX + contentW - 6 || mouseY < top || mouseY >= panelBottom()) {
            return false;
        }
        List<MarketView.YourTrade> rows = historyRows();
        int index = (int) ((mouseY - top + scrollOf("history")) / ROW_H);
        if (index < 0 || index >= rows.size()) return false;
        MarketView.YourTrade t = rows.get(index);
        openItem(MinecraftIds.idToItem(t.trade.itemId), t.bought ? MODE_SELL : MODE_BUY);
        return true;
    }

    private void label(GuiGraphicsExtractor m, String s, int x, int y, int colour) {
        Draw.text(m, this.font, Component.literal(s), x, y, colour);
    }

    /**
     * Grey placeholder text that disappears once something is typed.
     *
     * setSuggestion on its own draws after whatever is in the field and never stops,
     * so typing 5 into an amount box left it reading "5qty". The suggestion has to be
     * cleared and restored as the field fills and empties.
     */
    private void hint(EditBox field, String hint) {
        field.setSuggestion(field.getValue().isEmpty() ? hint : null);
        field.setResponder(text -> field.setSuggestion(text.isEmpty() ? hint : null));
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
        itemField.setValue(MinecraftIds.itemToId(item));
        scrollOffsets.put("book", 0);   // a different item, a different book
    }

    // ─────────── the item view ───────────

    private static final int MODE_BUY = 0;
    private static final int MODE_SELL = 1;
    private static final int MODE_WITHDRAW = 2;
    private static final String[] MODE_NAMES = {"Buy", "Sell", "Withdraw"};

    /** Static like the screen: coming back to an item finds the form as you left it. */
    private static int itemMode = MODE_BUY;

    /**
     * Opens an item's view, ready to buy or to sell it.
     *
     * The price is filled in with what would trade straight away — the lowest ask to buy,
     * the highest bid to sell — or the last price when that side is empty, so the common
     * case is typing an amount and pressing the button. An existing amount is left alone.
     */
    private void openItem(Item item, int mode) {
        if (item == null || item == Items.AIR) return;
        selectItem(item);
        setMode(mode);
        selectScreen(SCREEN_ITEM);
    }

    private void setMode(int mode) {
        itemMode = mode;
        if (mode == MODE_WITHDRAW) return;
        MarketState market = MarketStateHolder.get();
        Item item = MinecraftIds.itemFromName(itemField.getValue().trim());
        if (market == null || item == Items.AIR) return;
        MarketView.ItemRow row = rowCached(MinecraftIds.itemToId(item));
        long suggested = mode == MODE_BUY ? row.buyNow : row.sellNow;
        if (suggested == MarketView.NONE) suggested = row.last;
        if (suggested != MarketView.NONE && suggested > 0) {
            priceField.setValue(String.valueOf(suggested));
        }
    }

    /** The one button on the order form, doing whatever the switch above it says. */
    private void onPlace() {
        if (itemMode == MODE_SELL) onSell();
        else if (itemMode == MODE_WITHDRAW) onWithdraw();
        else onBuy();
    }

    // ─────────── settings widgets ───────────
    //
    // Neither vanilla control reports a change on its own: CheckboxWidget takes no
    // callback, and SliderWidget is abstract. Both are subclassed here so a setting
    // saves the moment it is changed, rather than needing an Apply button that people
    // forget to press.

    /**
     * A checkbox that reports each change. 1.16.5's took no callback, so this was a
     * subclass; the 1.21 one is built with one, and its size is its own.
     */
    private Checkbox toggle(int x, int y, int w, int h, String message, boolean checked,
                                  java.util.function.Consumer<Boolean> onChange) {
        return Checkbox.builder(Component.literal(message), this.font)
                .pos(x, y).maxWidth(w).selected(checked)
                .onValueChange((box, value) -> onChange.accept(value)).build();
    }

    private static final class IntSlider extends AbstractSliderButton {
        private final String label;
        private final int min;
        private final int max;
        private final java.util.function.IntConsumer onChange;

        IntSlider(int x, int y, int w, int h, String label, int min, int max,
                  int initial, java.util.function.IntConsumer onChange) {
            super(x, y, w, h, Component.literal(""),
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
            setMessage(Component.literal(label + ": " + describe(current())));
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
     * 1.16.5 needed raw framebuffer pixels with a flipped Y, worked out here by hand; the
     * DrawContext takes GUI coordinates directly and keeps its own stack of them. The clip
     * applies to everything drawn through it until endClip, item icons included.
     */
    private void beginClip(GuiGraphicsExtractor m, int x, int y, int w, int h) {
        m.enableScissor(x, y, x + w, y + h);
    }

    private void endClip(GuiGraphicsExtractor m) {
        m.disableScissor();
    }

    /**
     * An item icon in an 18x18 cell, with an optional count in the corner.
     *
     * renderInGui ignores the DrawContext — it draws through the legacy RenderSystem
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

    private void drawItemCell(GuiGraphicsExtractor m, ItemStack stack, int x, int y,
                              String countLabel, boolean hovered) {
        m.fill(x, y, x + 18, y + 18, SLOT_BODY);
        m.fill(x, y, x + 17, y + 1, SLOT_EDGE_DARK);
        m.fill(x, y + 1, x + 1, y + 17, SLOT_EDGE_DARK);
        m.fill(x + 17, y, x + 18, y + 18, SLOT_EDGE_LIGHT);
        m.fill(x + 1, y + 17, x + 18, y + 18, SLOT_EDGE_LIGHT);

        // Before the icon, as vanilla's drawSlot does — the item is drawn at a raised
        // z-offset, so a highlight painted afterwards would sit behind it.
        if (hovered) m.fill(x + 1, y + 1, x + 17, y + 17, 0x80FFFFFF);

        drawIcon(m, stack, x + 1, y + 1, countLabel);
    }

    /** The icon on its own, for places that want the item without a slot around it. */
    private void drawIcon(GuiGraphicsExtractor m, ItemStack stack, int x, int y, String countLabel) {
        if (stack == null || stack.isEmpty()) return;

        // Stacking is draw order now. The 1.16.5 version raised a z-offset by hand so the
        // icon landed above the slot behind it; the DrawContext keeps submission order, so
        // an icon drawn after its slot is above it without anything else being said.
        m.item(stack, x, y);
        m.itemDecorations(this.font, stack, x, y, countLabel);
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
    public boolean charTyped(CharacterEvent input) {
        if (pickerOpen) {
            int chr = input.codepoint();
            if (chr >= ' ' && chr != 127) {
                pickerQuery += input.codepointAsString();
                scrollOffsets.put("picker", 0);   // a new query, back to the top
            }
            return true;
        }
        return super.charTyped(input);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount,
                                 double amount) {
        if (!overlays.isEmpty()) return true;
        if (hoveredScrollKey != null && hoveredScrollMax > 0) {
            int next = (int) (scrollOf(hoveredScrollKey) - amount * 12);
            scrollOffsets.put(hoveredScrollKey,
                    Math.max(0, Math.min(hoveredScrollMax, next)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, amount);
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
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                for (InventoryBridge.Holding h : InventoryBridge.held(mc.player).items) {
                    seed.add(h.item);
                }
            }
            out.addAll(seed);
        } else {
            for (Item it : BuiltInRegistries.ITEM) {
                if (it == Items.AIR) continue;
                if (MinecraftIds.nameOf(it).getString().toLowerCase(java.util.Locale.ROOT).contains(q)) {
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

    private void renderPicker(GuiGraphicsExtractor m, int mouseX, int mouseY) {
        if (!pickerOpen) return;

        m.nextStratum();

        m.fill(0, 0, this.width, this.height, 0xE0101010);

        int[] box = pickerBox();
        darkBox(m, box[0], box[1], box[2], box[3]);

        label(m, "Pick an item", box[0] + 8, box[1] + 8, 0xFFAA00);

        // Search box, drawn rather than widgeted. It always has focus — the picker is
        // modal and there is nothing else here to type into — so it shows a caret from
        // the moment it opens rather than waiting for a click that does nothing.
        int searchY = box[1] + 20;
        m.fill(box[0] + 7, searchY - 1, box[0] + box[2] - 7, searchY + 13, 0xFF88CCFF);
        m.fill(box[0] + 8, searchY, box[0] + box[2] - 8, searchY + 12, 0xFF101010);
        if (pickerQuery.isEmpty()) {
            label(m, "type to search by name...", box[0] + 11, searchY + 2, 0x606060);
        } else {
            label(m, pickerQuery, box[0] + 11, searchY + 2, 0xFFFFFF);
        }
        if ((System.currentTimeMillis() / 500) % 2 == 0) {
            int caretX = box[0] + 11 + this.font.width(pickerQuery);
            m.fill(caretX, searchY + 2, caretX + 1, searchY + 11, 0xFFFFFFFF);
        }


        List<Item> items = pickerItems();
        int[] grid = pickerGridRect();
        int rows = (items.size() + PICK_COLS - 1) / PICK_COLS;
        noteScrollable("picker", grid[0], grid[1], grid[2], grid[3],
                rows * PICK_CELL, mouseX, mouseY);
        int offset = scrollOf("picker");

        if (items.isEmpty()) {
            label(m, "nothing matches", grid[0], grid[1] + 4, 0x808080);
        }

        beginClip(m, grid[0], grid[1], grid[2], grid[3]);
        for (int i = 0; i < items.size(); i++) {
            int cx = grid[0] + (i % PICK_COLS) * PICK_CELL;
            int cy = grid[1] + (i / PICK_COLS) * PICK_CELL - offset;
            boolean hot = mouseX >= cx && mouseX < cx + 18 && mouseY >= cy && mouseY < cy + 18
                    && mouseY >= grid[1] && mouseY < grid[1] + grid[3];
            drawItemCell(m, new ItemStack(items.get(i)), cx, cy, null, hot);
        }
        endClip(m);

        // Name of whatever is under the cursor, so the grid is readable without
        // clicking things to find out what they are. Depth off again — the icons above
        // left it on, and this sits over the panel.
        Item hovered = pickerItemAt(mouseX, mouseY);
        String footer = hovered != null ? MinecraftIds.nameOf(hovered).getString()
                : items.size() + " item(s) — Esc to cancel";
        label(m, footer, box[0] + 8, box[1] + box[3] - 14,
                hovered != null ? 0xFFFFFF : 0x808080);
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
            pickerOpen = false;
            openItem(chosen, MODE_BUY);
        } else if (!within(mouseX, mouseY, pickerBox())) {
            pickerOpen = false;   // clicking away cancels
        }
        return true;
    }

    // ─────────── chrome ───────────

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
    private final Button overlayConfirmButton = Button.builder(Component.literal(""), b -> {})
                .bounds(0, 0, OVERLAY_BTN_W, OVERLAY_BTN_H).build();
    private final Button overlayDismissButton = Button.builder(Component.literal(""), b -> {})
                .bounds(0, 0, OVERLAY_BTN_W, OVERLAY_BTN_H).build();

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
    private List<FormattedCharSequence> overlayLines(Overlay o) {
        List<FormattedCharSequence> out = new ArrayList<>();
        String[] paragraphs = o.body.split("\n\n");
        for (int i = 0; i < paragraphs.length; i++) {
            if (i > 0) out.add(FormattedCharSequence.EMPTY);
            out.addAll(this.font.split(
                    Component.literal(paragraphs[i].replace("\n", " ")),
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

    private void renderOverlay(GuiGraphicsExtractor m, Overlay o, int mouseX, int mouseY) {
        // Depth off for the same reason the picker turns it off: item icons leave depth
        // testing enabled, and a panel filled afterwards is otherwise rejected against
        // text already drawn, letting the buttons underneath show through.
        m.nextStratum();

        // Dim everything behind it, so it reads as blocking rather than as another
        // widget competing for attention with the rest of the screen.
        m.fill(0, 0, this.width, this.height, 0xE0101010);

        int[] box = overlayBox(o);
        // Vanilla's own severity colours: red for destructive, yellow for a choice.
        int accent = o.kind == Overlay.DANGER ? 0xFFFF5555 : 0xFFFFAA00;

        darkBox(m, box[0], box[1], box[2], box[3]);

        int y = box[1] + OVERLAY_PAD;
        label(m, o.title, box[0] + OVERLAY_PAD, y, accent);
        y += 14;

        for (FormattedCharSequence line : overlayLines(o)) {
            Draw.text(m, this.font, line, box[0] + OVERLAY_PAD, y, 0xFFDDDDDD);
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

        int[] confirm = overlayConfirmRect(o);
        if (confirm != null) {
            overlayConfirmButton.setMessage(Component.literal(o.confirmLabel));
            overlayConfirmButton.setX(confirm[0]);
            overlayConfirmButton.setY(confirm[1]);
            overlayConfirmButton.setWidth(confirm[2]);
            overlayConfirmButton.active = o.armed();
            overlayConfirmButton.visible = true;
            overlayConfirmButton.extractRenderState(m, mouseX, mouseY, 0.0F);
        }

        int[] dismiss = overlayDismissRect(o);
        overlayDismissButton.setMessage(
                Component.literal(o.onConfirm == null ? "OK" : "Cancel"));
        overlayDismissButton.setX(dismiss[0]);
        overlayDismissButton.setY(dismiss[1]);
        overlayDismissButton.setWidth(dismiss[2]);
        overlayDismissButton.active = true;
        overlayDismissButton.visible = true;
        overlayDismissButton.extractRenderState(m, mouseX, mouseY, 0.0F);
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
            s.setLastItem(itemField.getValue().trim());
            s.setLastHostAddress(hostField.getValue().trim());
            try {
                s.setHostPort(Integer.parseInt(hostPortField.getValue().trim()));
            } catch (NumberFormatException ignored) {
                // Not a number, so not worth remembering. setHostPort rejects
                // out-of-range values on the same principle.
            }
        }
        super.removed();
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int keyCode = input.key();
        // Escape answers the overlay rather than closing the screen out from under it.
        if (!overlays.isEmpty()) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                overlays.pollFirst();
            }
            return true;
        }

        // The picker eats typing, since its search box isn't a widget and so gets no
        // key events of its own.
        if (pickerOpen) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                pickerOpen = false;
            } else if (keyCode == InputConstants.KEY_BACKSPACE
                    && !pickerQuery.isEmpty()) {
                pickerQuery = pickerQuery.substring(0, pickerQuery.length() - 1);
                scrollOffsets.put("picker", 0);
            }
            return true;
        }

        if (menuOpen && keyCode == InputConstants.KEY_ESCAPE) {
            menuOpen = false;
            return true;
        }

        // Out of an item's view, Escape steps back to the list it came from rather than
        // closing the market — the same way out of a nested view the rest of the game uses.
        if (activeScreen == SCREEN_ITEM && keyCode == InputConstants.KEY_ESCAPE) {
            selectScreen(itemOpenedFrom);
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
                && MarketKeybinds.openMarketKey.matches(input)
                && !(this.getFocused() instanceof EditBox)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(input);
    }

}