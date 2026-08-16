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
    private ButtonWidget createButton;
    private ButtonWidget importButton;
    private ButtonWidget migrateButton;
    private ButtonWidget itemButton;
    private ButtonWidget exportButton;
    private ButtonWidget resetButton;

    /** Set from the game thread by button handlers, and from the network thread
     *  by callbacks — hence volatile. */
    private static volatile String status = "";

    /**
     * Result of settling interrupted inventory operations at world load.
     *
     * Held rather than shown immediately, because it is worked out before the player
     * has any reason to open this screen — and an item silently reappearing in your
     * inventory with no explanation is worse than the original problem.
     */
    private static volatile String recoveryNote = "";

    public static void reportRecovery(int returned, int unconfirmed) {
        StringBuilder sb = new StringBuilder();
        if (returned > 0) {
            sb.append("Returned items from ").append(returned)
              .append(returned == 1 ? " deposit that" : " deposits that")
              .append(" never completed");
        }
        if (unconfirmed > 0) {
            if (sb.length() > 0) sb.append(". ");
            sb.append(unconfirmed).append(unconfirmed == 1 ? " withdrawal" : " withdrawals")
              .append(" may not have reached you — see the log");
        }
        recoveryNote = sb.toString();
    }

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

    private int frameTop() { return rowY - 6; }
    private int frameH() { return contentH - 18; }
    /** First usable row inside a panel. */
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

    /**
     * Whether the nav panel is showing.
     *
     * Instance, not static: a menu left open is not a place you were, it is a gesture
     * half-finished, and it should not be waiting for you next time you open the screen.
     */
    private boolean navOpen = false;

    private <T extends ClickableWidget> T onScreen(int screen, T widget) {
        screenWidgets.get(screen).add(widget);
        return this.addButton(widget);
    }

    private void selectScreen(int screen) {
        activeScreen = screen;
        navOpen = false;
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

    // ─── nav panel geometry ───
    //
    // Drawn and hit-tested by hand rather than built from ButtonWidgets, because it has
    // to sit above whatever screen is beneath it. Widgets are drawn by super.render at
    // one fixed point in the pipeline, so a widget-based menu would end up underneath
    // the content it is supposed to cover.

    private static final int NAV_W = 96;
    private static final int NAV_ROW_H = 16;
    private static final int BURGER_SIZE = 14;

    private int[] burgerRect() {
        return new int[]{this.width - BURGER_SIZE - 8, 6, BURGER_SIZE, BURGER_SIZE};
    }

    private int[] navRowRect(int index) {
        int[] burger = burgerRect();
        int top = burger[1] + burger[3] + 4;
        return new int[]{this.width - NAV_W - 8, top + index * NAV_ROW_H, NAV_W, NAV_ROW_H};
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
        int boxY = Math.max(34, Math.min((this.height - contentH) / 2,
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

        onScreen(SCREEN_NETWORK, new ButtonWidget(rowX, rowY + ROW_STEP * 2, halfW, FIELD_HEIGHT,
                new LiteralText("Disconnect"), b -> onDisconnect()));
        onScreen(SCREEN_NETWORK, new ButtonWidget(rowX + halfW + PAD, rowY + ROW_STEP * 2, halfW,
                FIELD_HEIGHT, new LiteralText("Stop hosting"), b -> onStopHosting()));

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

    private void onReset() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        UUID me = MinecraftIds.userIdOf(mc.player);

        showDanger("Discard this world's market?",
                "You would lose " + MarketStateHolder.describeLoss(me) + "."
                        + " This cannot be undone. If you are rejoining a market you"
                        + " diverged from, everything you did before the split is in"
                        + " their copy too and comes back when you reconnect.",
                "Discard", () -> {
                    MarketStateHolder.resetLog();
                    status = "Local history discarded";
                });
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

        final Path worldDir = mc.getServer().getSavePath(WorldSavePath.ROOT);
        final UUID me = MinecraftIds.userIdOf(mc.player);

        showConfirm("Create '" + name + "'?",
                "This starts a SEPARATE economy. Anyone who joins it will not see trades"
                        + " from any market your friends already use, and the two can"
                        + " never be merged afterwards. To join an existing one instead,"
                        + " use Connect.",
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
        if (hostButton != null) hostButton.active = hostButton.visible && has;

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
                        + " as its own. Every event in it is verified before anything is"
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

        Path worldDir = mc.getServer().getSavePath(WorldSavePath.ROOT);
        UUID me = MinecraftIds.userIdOf(mc.player);
        String playerName = mc.getSession().getUsername();

        status = "Starting host...";
        new Thread(() -> {
            MarketStateHolder.startHosting(worldDir, hostPort, me, playerName);
            status = MarketStateHolder.mode() == MarketStateHolder.Mode.HOSTING
                    ? "Hosting on port " + hostPort
                    : "Failed to start host";
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

        Path worldDir = mc.getServer() != null
                ? mc.getServer().getSavePath(WorldSavePath.ROOT) : null;
        if (worldDir == null) return;
        String myName = mc.getSession().getUsername();

        showDanger("Migrate to " + host + ":" + port + "?",
                "Your position in '" + mine.marketName() + "' — "
                        + MarketStateHolder.describeLoss(me) + " — is verified by that"
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
                        MarketStateHolder.observeHostHead(
                                UUID.fromString(h.reply.marketId), h.reply.lastSeq,
                                h.reply.lastHash, h.reply.userId, h.reply.hostName);
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
    private int discoveryStartY() {
        return panelTop() + 14;
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
        // shift everything by a few pixels.
        int top = rowY - 6;
        int height = contentH - 18;
        int total = contentW;

        int sideW = (total - gap * 2) / 3;
        int midW = total - gap * 2 - sideW * 2;
        int midX = listX + sideW + gap;
        int rightX = midX + midW + gap;

        panel(m, listX, top, sideW, height, "Widget 1");

        int hostsH = height / 2;
        panel(m, midX, top, midW, hostsH, "Hosts");
        renderHostsPanel(m, midX + 8, top + 18, midW - 16, hostsH - 24);
        panel(m, midX, top + hostsH + gap, midW, height - hostsH - gap, "Widget 2");

        panel(m, rightX, top, sideW, height, "Widget 3");
    }

    /**
     * The vanilla tooltip frame — near-black fill, violet gradient edge.
     *
     * Minecraft has one panel look and this is it, so anything the mod draws itself
     * uses it rather than inventing a flat modern box that sits oddly beside the
     * vanilla buttons right next to it.
     */
    private void vanillaPanel(MatrixStack m, int x, int y, int w, int h) {
        final int bg = 0xF0100010;
        final int edgeTop = 0x505000FF;
        final int edgeBottom = 0x5028007F;

        fill(m, x - 3, y - 4, x + w + 3, y - 3, bg);
        fill(m, x - 3, y + h + 3, x + w + 3, y + h + 4, bg);
        fill(m, x - 3, y - 3, x + w + 3, y + h + 3, bg);
        fill(m, x - 4, y - 3, x - 3, y + h + 3, bg);
        fill(m, x + w + 3, y - 3, x + w + 4, y + h + 3, bg);

        fillGradient(m, x - 3, y - 2, x - 2, y + h + 2, edgeTop, edgeBottom);
        fillGradient(m, x + w + 2, y - 2, x + w + 3, y + h + 2, edgeTop, edgeBottom);
        fillGradient(m, x - 3, y - 3, x + w + 3, y - 2, edgeTop, edgeTop);
        fillGradient(m, x - 3, y + h + 2, x + w + 3, y + h + 3, edgeBottom, edgeBottom);
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
            String name = host.reply.hostName == null ? "?" : host.reply.hostName;
            label(m, name, x, row, 0x88CCFF);
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
        for (PeerPoll.HostInfo h : discovered) {
            if (h.reply.marketId != null && !myMarket.equals(h.reply.marketId)) return h;
        }
        return null;
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
        boolean migrate = foreign && situation != MS_NO_MARKET && situation != MS_FORKED
                && situation != MS_DAMAGED;
        boolean reset = situation != MS_NO_MARKET;

        marketNameField.visible = create;
        marketNameField.active = create;

        int y = rowY + 4;
        y = place(createButton, create, y);
        y = place(importButton, importFile, y);
        y = place(exportButton, export, y);
        y = place(migrateButton, migrate, y);
        place(resetButton, reset, y);

        if (create) {
            // The name field sits above the button that consumes it.
            marketNameField.y = rowY + 4;
            createButton.y = rowY + 4 + ROW_STEP;
            importButton.y = rowY + 4 + ROW_STEP * 2;
        }
    }

    private int place(ButtonWidget button, boolean shown, int y) {
        if (button == null) return y;
        button.visible = shown;
        button.active = shown;
        if (!shown) return y;
        button.y = y;
        return y + ROW_STEP;
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
                        + " Nothing can be done with it until it is discarded. If"
                        + " someone else still has this market, you get everything back"
                        + " when you reconnect to them.";
                break;
            case MS_NO_MARKET:
                heading = "This world has no market";
                body = "Create one to start an economy of your own, or import a file"
                        + " someone exported. To join a market your friends already use,"
                        + " go to Network and connect to whoever is hosting it — do NOT"
                        + " create one, since two markets can never be merged.";
                break;
            case MS_FORKED:
                heading = "You have diverged from this market";
                body = MarketStateHolder.divergence().describe()
                        + ". Discarding and reconnecting is the way back, and it costs"
                        + " only what you did after the split — everything before it is"
                        + " in their copy too. Migrating is the wrong tool here and the"
                        + " host will refuse it.";
                break;
            case MS_BEHIND:
                heading = "Your copy is behind";
                body = MarketStateHolder.eventsBehind() + " events have happened that you"
                        + " do not have. Connect to someone serving this market from the"
                        + " Network screen and you will catch up automatically. Do not"
                        + " host until you have.";
                break;
            case MS_CONNECTED:
                heading = "Connected to '" + (market == null ? "?" : market.marketName()) + "'";
                body = "Everything is in order. You can export a copy of this market to"
                        + " a file for someone who cannot be online at the same time as"
                        + " anyone holding it.";
                break;
            default:
                heading = "You hold '" + (market == null ? "?" : market.marketName()) + "'";
                body = "Nobody is serving it. Host it from the Network screen so others"
                        + " can trade, or connect to someone who already is.";
                break;
        }

        label(m, heading, x, panelTop(), 0xFFAA00);

        int y = panelTop() + 14;
        for (OrderedText line : this.textRenderer.wrapLines(new LiteralText(body), listW - 12)) {
            this.textRenderer.drawWithShadow(m, line, x, y, 0xC0C0C0);
            y += 10;
        }

        if (foreign != null && situation != MS_NO_MARKET && situation != MS_FORKED) {
            y += 6;
            for (OrderedText line : this.textRenderer.wrapLines(new LiteralText(
                    foreign.reply.hostName + " is running a separate market ('"
                            + foreign.reply.marketName + "'). Migrating carries your"
                            + " whole position there and abandons this one."), listW)) {
                this.textRenderer.drawWithShadow(m, line, x, y, 0x88CCFF);
                y += 10;
            }
        }
    }

    /**
     * Orders carried over from a migrated market, waiting to be re-placed.
     *
     * Shown with the destination's current price beside each one. The prices you set in
     * a dead economy mean nothing here, and the difference is usually invisible until
     * after you've clicked — so it goes on the row.
     */
    private void renderReplaceList(MatrixStack matrices) {
        List<MarketStateHolder.OldOrder> old = MarketStateHolder.pendingReplace();
        if (old.isEmpty()) return;

        int x = rowX;
        int y = discoveryStartY();

        label(matrices, "Orders from your old market — click to re-place, "
                + "[X] to dismiss all:", x, y, 0xFFDD66);
        y += DISCOVERY_ROW_HEIGHT + 2;

        MarketState s = MarketStateHolder.get();
        for (MarketOldRow row : replaceRows()) {
            MarketStateHolder.OldOrder o = row.order;
            String here = "";
            if (s != null) {
                List<Order> book = o.isBid
                        ? s.bookFor(o.itemId).restingAsks()
                        : s.bookFor(o.itemId).restingBids();
                if (!book.isEmpty()) {
                    here = o.isBid
                            ? "   (best ask here: " + book.get(0).value() + ")"
                            : "   (best bid here: " + book.get(0).value() + ")";
                }
            }
            label(matrices, "  " + (o.isBid ? "Buy  " : "Sell ") + o.volume + " "
                    + shortItem(o.itemId) + " @ " + o.price + here, x, y, 0x88CCFF);
            y += DISCOVERY_ROW_HEIGHT;
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

    private void renderDiscovery(MatrixStack matrices, int px, int py,
                                 double mouseX, double mouseY) {
        // The re-place list takes this space while it exists — it's transient and
        // actionable, discovery is neither. render() draws it directly now, on
        // whichever tab you are on, so this only has to stand aside.
        if (!MarketStateHolder.pendingReplace().isEmpty()) return;

        int x = px;
        int y = py;

        // No running counter: it re-polls every 10s, so the age is almost always
        // uninteresting and a ticking number just pulls the eye. Say something only
        // when the list has actually gone stale, which now means polling is failing.
        boolean stale = !polling && lastPollAt != 0 && !pollIsFresh();
        label(matrices, stale ? "Hosts: (out of date)" : "Hosts:",
                x, y, stale ? 0xAA8844 : 0xFFFFFF);
        y += DISCOVERY_ROW_HEIGHT + 2;

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

        for (PeerPoll.HostInfo h : hosts) {
            boolean isSelf = h.reply.userId != null && h.reply.userId.equals(myUuid);
            // Whether this host serves the market we hold decides whether clicking it
            // will work at all, so it belongs on the row rather than in a failure later.
            boolean joinable = myMarket == null || myMarket.equals(h.reply.marketId);
            String marketLabel = h.reply.marketName != null ? h.reply.marketName : "unnamed";

            String line = "  " + h.reply.hostName
                    + (isSelf ? " (you)" : "")
                    + "  [" + marketLabel + "]"
                    + (joinable ? "" : " (different market)")
                    + "  (" + h.reply.lastSeq + " events, "
                    + h.reply.clientCount + " online)";

            int colour = isSelf ? 0xAAAAAA : (joinable ? 0x88CCFF : 0x996666);
            label(matrices, line, x, y, colour);
            y += DISCOVERY_ROW_HEIGHT;
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
        if (button == 0 && navClicked(mouseX, mouseY)) return true;

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
        recoveryNote = "";

        // The re-place list occupies the discovery area while it exists, so it claims
        // clicks there first.
        if (button == 0 && !MarketStateHolder.pendingReplace().isEmpty()) {
            int x = rowX;
            int headerY = discoveryStartY();

            // Header doubles as dismiss — the list is a convenience, not an obligation.
            if (mouseX >= x && mouseX <= x + 300
                    && mouseY >= headerY && mouseY < headerY + DISCOVERY_ROW_HEIGHT) {
                MarketStateHolder.clearPendingReplace();
                status = "Dismissed — your balance is unaffected";
                return true;
            }

            int y = headerY + DISCOVERY_ROW_HEIGHT + 2;
            for (MarketOldRow row : replaceRows()) {
                if (mouseX >= x && mouseX <= x + 300
                        && mouseY >= y && mouseY < y + DISCOVERY_ROW_HEIGHT) {
                    replaceOrder(row.order);
                    return true;
                }
                y += DISCOVERY_ROW_HEIGHT;
            }

            // Swallow only clicks that actually landed on the list. This used to
            // return unconditionally, which meant that while any orders were waiting
            // to be re-placed — i.e. immediately after every migration — no button or
            // text field anywhere on the screen could be clicked at all.
            if (mouseX >= x && mouseX <= x + 300 && mouseY >= headerY && mouseY < y) {
                return true;
            }
        }

        // Only where the host list is actually drawn. Hit-testing it from another tab
        // would join a host from a row nobody can see.
        if (button == 0 && !polling && activeScreen == SCREEN_NETWORK) {
            MinecraftClient mc = MinecraftClient.getInstance();
            String myUuid = mc.player != null
                    ? MinecraftIds.userIdOf(mc.player).toString() : null;

            int x = listX + 4;
            int y = discoveryStartY() + DISCOVERY_ROW_HEIGHT + 2;

            for (PeerPoll.HostInfo h : discovered) {
                boolean isSelf = h.reply.userId != null && h.reply.userId.equals(myUuid);
                if (!isSelf && mouseX >= x && mouseX <= x + listW - 8
                        && mouseY >= y && mouseY < y + DISCOVERY_ROW_HEIGHT) {
                    joinHost(h);
                    return true;
                }
                y += DISCOVERY_ROW_HEIGHT;
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

        MarketStateHolder.Submission s = MarketStateHolder.submit(e);
        // A submission that fails outright never becomes an event, so nothing will ever
        // clear this entry — settle it here rather than leaving a false refund waiting.
        if (journal != null && !s.pending && !s.accepted) {
            journal.clearDeposit(clientEventId);
            InventoryBridge.give(mc.player, req.item, (int) req.qty);
        }
        report(s, "Listed " + req.qty + " at " + req.price, "Sell sent...");
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

        report(MarketStateHolder.submit(order),
                "Bid placed for " + req.qty + " at " + req.price, "Buy sent...");
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
        String itemId = MinecraftIds.itemToId(item);
        OrderBook book = MarketStateHolder.get().bookFor(itemId);
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
                    : "Traded — " + s.result.fills.size() + " fill(s)";
        } else {
            status = "Rejected: " + s.reason;
        }
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

        // Persistent, not a status-line message — this one doesn't get to scroll away.
        int warnY = 42;
        if (MarketStateHolder.chainBrokenAt() != -1) {
            String why = MarketStateHolder.damageReason();
            label(matrices, "LOG UNUSABLE — " + (why == null ? "damaged" : why),
                    listX, warnY, 0xFF6666);
        } else {
            long behind = MarketStateHolder.eventsBehind();
            MarketStateHolder.Divergence split = MarketStateHolder.divergence();
            if (behind > 0) {
                label(matrices, behind + " events behind — connect to catch up",
                        listX, warnY, 0xFFAA55);
            }
            // Below the behind-warning rather than instead of it: they're different
            // problems and both can be true at once. Found passively by discovery, so
            // it can be showing before anyone has tried to connect.
            if (split != null) {
                label(matrices, "FORKED — " + split.describe(),
                        listX, behind > 0 ? warnY + 10 : warnY, 0xFF8844);
            }
        }

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
            renderDiscovery(matrices, listX + 4, panelTop(), mouseX, mouseY);
        } else if (activeScreen == SCREEN_MARKET) {
            renderMarketGuidance(matrices, listX + 4);
        } else if (activeScreen == SCREEN_HOME) {
            renderHome(matrices);
        } else {
            renderSettingsPlaceholder(matrices);
        }

        // The re-place checklist belongs to whichever tab you are on: it appears right
        // after a migration and is the only thing you should be doing next.
        if (!MarketStateHolder.pendingReplace().isEmpty()) {
            renderReplaceList(matrices);
        }

        // Shown the first time the screen is opened after a recovery, then dismissed by
        // any click — it explains something that already happened, so it only has to be
        // seen once.
        if (!recoveryNote.isEmpty()) {
            label(matrices, recoveryNote, listX, this.height - 36, 0x88CCFF);
        }

        if (!status.isEmpty()) {
            label(matrices, status, listX, this.height - 24, 0xFFDD66);
        }

        // Your identity is a file, and it doesn't follow you to a new computer. Moving
        // machines without it makes you a stranger to every market you were part of —
        // same username, same UUID, unrecognised key. Cheaper to say so than to debug.
        Path identity = MarketStateHolder.identityPath();
        if (identity != null) {
            label(matrices, "identity: config/" + identity.getFileName()
                            + "  — copy this to move computers, never share it",
                    listX, this.height - 12, 0x707070);
        }

        // The text fields are registered with addButton now, so super.render draws
        // them — and skips the ones belonging to a tab that isn't showing, which
        // hand-rolled render calls could not do.
        super.render(matrices, mouseX, mouseY, delta);

        // Above the widgets super.render just drew, and below an overlay, which is the
        // only thing that outranks the menu.
        renderNav(matrices, mouseX, mouseY);
        renderPicker(matrices, mouseX, mouseY);

        Overlay overlay = overlays.peekFirst();
        if (overlay != null) renderOverlay(matrices, overlay, mouseX, mouseY);
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
    private void drawItemCell(MatrixStack m, ItemStack stack, int x, int y,
                              String countLabel, boolean hovered) {
        fill(m, x, y, x + 18, y + 18, hovered ? 0xFF4A4A4A : 0xFF2A2A2A);
        fill(m, x, y, x + 18, y + 1, 0xFF1A1A1A);
        fill(m, x, y, x + 1, y + 18, 0xFF1A1A1A);

        if (stack == null || stack.isEmpty()) return;

        ItemRenderer items = MinecraftClient.getInstance().getItemRenderer();
        this.setZOffset(100);
        items.zOffset = 100.0F;
        RenderSystem.enableDepthTest();
        items.renderInGui(stack, x + 1, y + 1);
        items.renderGuiItemOverlay(this.textRenderer, stack, x + 1, y + 1, countLabel);
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
        int max = Math.max(0, contentHeight - h);
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
        if (!overlays.isEmpty() || navOpen) return true;
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
     * you're worth, the time, and the way out to everything else.
     *
     * Real-world clock rather than the world's. In-game time tells you whether it is
     * night where you are standing; this market spans separate worlds, and the useful
     * question is whether the people you trade with are plausibly awake.
     */
    private void renderHeader(MatrixStack m) {
        drawCenteredText(m, this.textRenderer, this.title, this.width / 2, 8, 0xFFFFFF);

        renderConnectionStatus(m, 8, 8);

        MinecraftClient mc = MinecraftClient.getInstance();
        MarketState market = MarketStateHolder.get();
        if (mc.player != null && market != null) {
            UUID me = MinecraftIds.userIdOf(mc.player);
            label(m, "Credits: " + market.wallets().getBalance(me), 8, 20, 0xFFFF88);

            // Only where an item is selected and the number means something.
            if (activeScreen == SCREEN_TRADING) {
                Item item = MinecraftIds.itemFromName(itemField.getText().trim());
                if (item != Items.AIR) {
                    long held = market.itemBalances()
                            .getBalance(me, MinecraftIds.itemToId(item));
                    label(m, item.getName().getString() + " market credit: " + held,
                            8, 30, 0xFFFF88);
                }
            }
        }

        String clock = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        int clockW = this.textRenderer.getWidth(clock);
        label(m, clock, burgerRect()[0] - clockW - 8, 9, 0xA0A0A0);

        renderBurger(m);
    }

    private void renderBurger(MatrixStack m) {
        int[] r = burgerRect();
        int colour = navOpen ? 0xFFFFDD66 : 0xFFCCCCCC;
        for (int i = 0; i < 3; i++) {
            int y = r[1] + 3 + i * 4;
            fill(m, r[0], y, r[0] + r[2], y + 2, colour);
        }
    }

    private void renderNav(MatrixStack m, int mouseX, int mouseY) {
        if (!navOpen) return;

        int[] first = navRowRect(0);
        int[] last = navRowRect(SCREEN_NAMES.length - 1);
        int top = first[1];
        int bottom = last[1] + last[3];

        vanillaPanel(m, first[0], top, NAV_W, bottom - top);

        for (int i = 0; i < SCREEN_NAMES.length; i++) {
            int[] r = navRowRect(i);
            boolean here = i == activeScreen;
            boolean hot = within(mouseX, mouseY, r);
            // Vanilla marks the current entry with a chevron rather than a highlight
            // block — the selection reads at a glance without another filled rectangle.
            label(m, (here ? "> " : "  ") + SCREEN_NAMES[i], r[0] + 4, r[1] + 4,
                    here ? 0xFFAA00 : (hot ? 0xFFFFFF : 0xA0A0A0));
        }
    }

    /** Returns true if the click belonged to the nav, including opening or closing it. */
    private boolean navClicked(double mouseX, double mouseY) {
        if (within(mouseX, mouseY, burgerRect())) {
            navOpen = !navOpen;
            return true;
        }
        if (!navOpen) return false;

        for (int i = 0; i < SCREEN_NAMES.length; i++) {
            if (within(mouseX, mouseY, navRowRect(i))) {
                selectScreen(i);
                return true;
            }
        }
        // Clicking anywhere else dismisses rather than falling through to whatever is
        // underneath — a menu that closes AND presses the button behind it is a trap.
        navOpen = false;
        return true;
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
        static final int NOTICE = 0;
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

    private void showNotice(String title, String body) {
        overlays.addLast(new Overlay(Overlay.NOTICE, title, body, "OK", null));
    }

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

    private List<OrderedText> overlayLines(Overlay o) {
        return this.textRenderer.wrapLines(new LiteralText(o.body), OVERLAY_W - OVERLAY_PAD * 2);
    }

    /**
     * Geometry for the current overlay: {left, top, width, height}.
     *
     * A pure function of the window and the message, so render and hit-testing derive
     * the same rectangles from the same inputs rather than one trusting coordinates
     * the other happened to leave in a field.
     */
    private int[] overlayBox(Overlay o) {
        int lines = overlayLines(o).size();
        int h = OVERLAY_PAD + 12                       // title
                + lines * 10 + OVERLAY_PAD             // body
                + OVERLAY_BTN_H + OVERLAY_PAD;         // buttons
        int left = (this.width - OVERLAY_W) / 2;
        int top = Math.max(20, (this.height - h) / 2);
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
        int accent = o.kind == Overlay.DANGER ? 0xFFFF5555
                : o.kind == Overlay.CONFIRM ? 0xFFFFAA00 : 0xFF55FFFF;

        vanillaPanel(m, box[0], box[1], box[2], box[3]);

        int y = box[1] + OVERLAY_PAD;
        label(m, o.title, box[0] + OVERLAY_PAD, y, accent);
        y += 14;

        for (OrderedText line : overlayLines(o)) {
            this.textRenderer.drawWithShadow(m, line, box[0] + OVERLAY_PAD, y, 0xFFDDDDDD);
            y += 10;
        }

        int[] confirm = overlayConfirmRect(o);
        if (confirm != null) {
            boolean armed = o.armed();
            boolean hot = armed && within(mouseX, mouseY, confirm);
            fill(m, confirm[0], confirm[1], confirm[0] + confirm[2], confirm[1] + confirm[3],
                    armed ? (hot ? 0xFF505050 : 0xFF383838) : 0xFF262626);
            drawCenteredText(m, this.textRenderer, new LiteralText(o.confirmLabel),
                    confirm[0] + confirm[2] / 2, confirm[1] + 6,
                    armed ? accent : 0xFF707070);
        }

        int[] dismiss = overlayDismissRect(o);
        boolean dismissHot = within(mouseX, mouseY, dismiss);
        fill(m, dismiss[0], dismiss[1], dismiss[0] + dismiss[2], dismiss[1] + dismiss[3],
                dismissHot ? 0xFF505050 : 0xFF383838);
        drawCenteredText(m, this.textRenderer,
                new LiteralText(o.onConfirm == null ? "OK" : "Cancel"),
                dismiss[0] + dismiss[2] / 2, dismiss[1] + 6, 0xFFDDDDDD);

        RenderSystem.enableDepthTest();
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

        // Then the menu, for the same reason: Escape should back out one step at a
        // time rather than dropping straight to the game.
        if (navOpen && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            navOpen = false;
            return true;
        }

        // Derived from focus rather than a list of fields: the old enumeration left out
        // marketNameField, so typing "m" into it closed the screen.
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_M
                && !(this.getFocused() instanceof TextFieldWidget)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

}