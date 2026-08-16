package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.*;
import io.github.badbull643.economiesmod.core.net.PeerPoll;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.LiteralText;
import net.minecraft.text.OrderedText;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;



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

    /** Left column, for whichever list the current tab shows. */
    private static final int LIST_W = 190;
    /** Right column, for that tab's controls. */
    private static final int CONTROLS_W = 230;
    private static final int CONTENT_W = LIST_W + 16 + CONTROLS_W;
    private static final int CONTENT_H = 150;
    private static final int ROW_STEP = 24;

    // Row positions, set in init() so render and hit-tests can't drift.
    private int rowX;
    private int rowY;
    private int buttonsY;
    private int cancelY;

    /** Left column, where the lists live. */
    private int listX;

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

        // A fixed box, centred and clamped, instead of origins derived from the window
        // size. The percentages this used to use put the bottom row off-screen at GUI
        // scale 3 and higher, because the rows below them were a fixed pixel height
        // that the percentage never accounted for.
        int boxX = Math.max(4, (this.width - CONTENT_W) / 2);
        int boxY = Math.max(30, Math.min((this.height - CONTENT_H) / 2, this.height - CONTENT_H - 30));

        this.listX = boxX;
        this.rowX = boxX + LIST_W + 16;
        this.rowY = boxY + 22;
        this.buttonsY = rowY + ROW_STEP;
        this.cancelY = rowY + ROW_STEP * 2;

        // ─── TRADING ───
        this.amountField = new TextFieldWidget(this.textRenderer,
                rowX, rowY, 45, FIELD_HEIGHT, new LiteralText("Amount"));
        this.amountField.setSuggestion("qty");
        onScreen(SCREEN_TRADING, this.amountField);

        this.itemField = new TextFieldWidget(this.textRenderer,
                rowX + 50, rowY, 130, FIELD_HEIGHT, new LiteralText("Item"));
        this.itemField.setMaxLength(64);
        this.itemField.setText(savedItem());
        onScreen(SCREEN_TRADING, this.itemField);

        this.priceField = new TextFieldWidget(this.textRenderer,
                rowX + 185, rowY, 45, FIELD_HEIGHT, new LiteralText("Price"));
        this.priceField.setSuggestion("price");
        onScreen(SCREEN_TRADING, this.priceField);

        onScreen(SCREEN_TRADING, new ButtonWidget(rowX, buttonsY, 110, FIELD_HEIGHT,
                new LiteralText("Buy"), b -> onBuy()));
        onScreen(SCREEN_TRADING, new ButtonWidget(rowX + 120, buttonsY, 110, FIELD_HEIGHT,
                new LiteralText("Sell"), b -> onSell()));

        onScreen(SCREEN_TRADING, new ButtonWidget(rowX, cancelY, 110, FIELD_HEIGHT,
                new LiteralText("Withdraw"), b -> onWithdraw()));

        this.cancelField = new TextFieldWidget(this.textRenderer,
                rowX + 120, cancelY, 45, FIELD_HEIGHT, new LiteralText("Order ID"));
        this.cancelField.setSuggestion("id");
        onScreen(SCREEN_TRADING, this.cancelField);
        onScreen(SCREEN_TRADING, new ButtonWidget(rowX + 170, cancelY, 60, FIELD_HEIGHT,
                new LiteralText("Cancel"), b -> onCancel()));

        // ─── NETWORK ───
        this.hostField = new TextFieldWidget(this.textRenderer,
                rowX, rowY, 175, FIELD_HEIGHT, new LiteralText("Host"));
        this.hostField.setMaxLength(64);
        this.hostField.setText(savedHost());
        onScreen(SCREEN_NETWORK, this.hostField);

        this.hostPortField = new TextFieldWidget(this.textRenderer,
                rowX + 185, rowY, 45, FIELD_HEIGHT, new LiteralText("Port"));
        this.hostPortField.setText(savedPort());
        onScreen(SCREEN_NETWORK, this.hostPortField);

        onScreen(SCREEN_NETWORK, new ButtonWidget(rowX, rowY + ROW_STEP, 110, FIELD_HEIGHT,
                new LiteralText("Connect"), b -> onConnect()));

        // Host serves the market this world already holds. With no market there is
        // nothing to serve, so the button is disabled rather than silently creating
        // one — that silent creation is what fragments a friend group into two
        // permanently incompatible economies.
        this.hostButton = new ButtonWidget(rowX + 120, rowY + ROW_STEP, 110, FIELD_HEIGHT,
                new LiteralText("Host"), b -> onHost());
        onScreen(SCREEN_NETWORK, this.hostButton);

        onScreen(SCREEN_NETWORK, new ButtonWidget(rowX, rowY + ROW_STEP * 2, 110, FIELD_HEIGHT,
                new LiteralText("Disconnect"), b -> onDisconnect()));
        onScreen(SCREEN_NETWORK, new ButtonWidget(rowX + 120, rowY + ROW_STEP * 2, 110,
                FIELD_HEIGHT, new LiteralText("Stop hosting"), b -> onStopHosting()));

        onScreen(SCREEN_NETWORK, new ButtonWidget(rowX, rowY + ROW_STEP * 3, 110, FIELD_HEIGHT,
                new LiteralText("Refresh hosts"), b -> startPoll()));

        // ─── MARKET ───
        this.marketNameField = new TextFieldWidget(this.textRenderer,
                rowX, rowY, 230, FIELD_HEIGHT, new LiteralText("Market name"));
        this.marketNameField.setMaxLength(32);
        this.marketNameField.setText(savedMarketName());
        this.marketNameField.setSuggestion("new market name");
        onScreen(SCREEN_MARKET, this.marketNameField);

        this.createButton = new ButtonWidget(rowX, rowY + ROW_STEP, 110, FIELD_HEIGHT,
                new LiteralText("Create market"), b -> onCreateMarket());
        onScreen(SCREEN_MARKET, this.createButton);

        // Sharing a market by file is how someone joins who was never online at the
        // same time as anyone holding it.
        this.importButton = new ButtonWidget(rowX + 120, rowY + ROW_STEP, 110, FIELD_HEIGHT,
                new LiteralText("Import from file"), b -> onImport());
        onScreen(SCREEN_MARKET, this.importButton);

        onScreen(SCREEN_MARKET, new ButtonWidget(rowX, rowY + ROW_STEP * 2, 110, FIELD_HEIGHT,
                new LiteralText("Export to file"), b -> onExport()));

        this.migrateButton = new ButtonWidget(rowX + 120, rowY + ROW_STEP * 2, 110,
                FIELD_HEIGHT, new LiteralText("Migrate to host"), b -> onMigrate());
        onScreen(SCREEN_MARKET, this.migrateButton);

        onScreen(SCREEN_MARKET, new ButtonWidget(rowX, rowY + ROW_STEP * 3, 110, FIELD_HEIGHT,
                new LiteralText("Reset log"), b -> onReset()));

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
        boolean has = MarketStateHolder.hasMarket();
        if (hostButton != null) hostButton.active = hostButton.visible && has;
        if (createButton != null) createButton.active = createButton.visible && !has;
        // Import adopts a market, so it needs the same empty slate Create does.
        if (importButton != null) importButton.active = importButton.visible && !has;
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
        return rowY + ROW_STEP * 4 + 6;
    }

    // ─────────── home ───────────

    /**
     * The dashboard.
     *
     * Hosts is live because discovery already polls for it; the other three are drawn
     * as empty panels rather than left out, so the layout is real and adding a panel
     * later is filling one in rather than finding room for it.
     */
    private void renderHome(MatrixStack m) {
        int top = rowY - 4;
        int bottom = top + CONTENT_H - 10;
        int gap = 6;
        int leftW = LIST_W;
        int midX = listX + leftW + gap;
        int midW = 130;
        int rightX = midX + midW + gap;
        int rightW = Math.max(60, (listX + CONTENT_W) - rightX);
        int half = (bottom - top - gap) / 2;

        panel(m, listX, top, leftW, bottom - top, "Notice board");
        panel(m, midX, top, midW, half, "Hosts");
        renderHostsPanel(m, midX + 4, top + 14, midW - 8, half - 18);
        panel(m, midX, top + half + gap, midW, half, "Widget");
        panel(m, rightX, top, rightW, half, "Most traded");
        panel(m, rightX, top + half + gap, rightW, half, "Widget");
    }

    /** A titled empty box. The border makes the layout legible before the content exists. */
    private void panel(MatrixStack m, int x, int y, int w, int h, String title) {
        fill(m, x, y, x + w, y + h, 0x40000000);
        fill(m, x, y, x + w, y + 1, 0xFF404040);
        fill(m, x, y + h - 1, x + w, y + h, 0xFF404040);
        fill(m, x, y, x + 1, y + h, 0xFF404040);
        fill(m, x + w - 1, y, x + w, y + h, 0xFF404040);
        label(m, title, x + 4, y + 4, 0xFFDD66);
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
        panel(m, listX, rowY - 4, CONTENT_W, CONTENT_H - 10, "Settings");
        label(m, "Port and notification options will live here.",
                listX + 8, rowY + 14, 0x808080);
        label(m, "They are already saved between sessions —",
                listX + 8, rowY + 26, 0x808080);
        label(m, "this screen just hasn't been built yet.",
                listX + 8, rowY + 38, 0x808080);
    }

    /** What this world holds, on the Market screen's left column. */
    private void renderMarketSummary(MatrixStack matrices, int x) {
        MarketState market = MarketStateHolder.get();
        label(matrices, "This world", x, rowY - 11, 0xFFFFFF);

        if (market == null || market.marketId() == null) {
            label(matrices, "No market yet.", x, rowY + 2, 0x808080);
            label(matrices, "Create one, import a", x, rowY + 14, 0x808080);
            label(matrices, "file, or connect to", x, rowY + 26, 0x808080);
            label(matrices, "someone hosting one.", x, rowY + 38, 0x808080);
            return;
        }

        label(matrices, "'" + market.marketName() + "'", x, rowY + 2, 0xFFDD66);
        label(matrices, market.registeredCount() + " participant(s)", x, rowY + 16, 0xA0A0A0);

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            label(matrices, "You hold:", x, rowY + 34, 0xA0A0A0);
            // describeLoss is the same net-position calculation migration credits from,
            // so what this says is exactly what a migration would carry.
            String held = MarketStateHolder.describeLoss(MinecraftIds.userIdOf(mc.player));
            for (OrderedText line : this.textRenderer.wrapLines(
                    new LiteralText(held), LIST_W)) {
                this.textRenderer.drawWithShadow(matrices, line, x, rowY + 46, 0xFFFF88);
                break;   // one line is enough here; the Trade tab has the detail
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

    private void renderDiscovery(MatrixStack matrices) {
        // The re-place list takes this space while it exists — it's transient and
        // actionable, discovery is neither. render() draws it directly now, on
        // whichever tab you are on, so this only has to stand aside.
        if (!MarketStateHolder.pendingReplace().isEmpty()) return;

        int x = rowX;
        int y = discoveryStartY();

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

        // The nav sits above everything else, so it gets first refusal on a click.
        if (button == 0 && navClicked(mouseX, mouseY)) return true;

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

            int x = rowX;
            int y = discoveryStartY() + DISCOVERY_ROW_HEIGHT + 2;

            for (PeerPoll.HostInfo h : discovered) {
                boolean isSelf = h.reply.userId != null && h.reply.userId.equals(myUuid);
                if (!isSelf && mouseX >= x && mouseX <= x + 220
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

        // Only the current destination's left column.
        if (activeScreen == SCREEN_TRADING) {
            renderSelectedItem(matrices, listX, rowY - 14, mouseX, mouseY);
            label(matrices, "Order book", listX, rowY + 8, 0xFFFFFF);
            renderBook(matrices, listX, rowY + 20, mouseX, mouseY);
        } else if (activeScreen == SCREEN_NETWORK) {
            renderDiscovery(matrices);
        } else if (activeScreen == SCREEN_MARKET) {
            renderMarketSummary(matrices, listX);
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
        int viewH = CONTENT_H - (startY - (rowY - 4)) - 30;
        int contentH = (asks.size() + bids.size()) * rowHeight + 4;

        noteScrollable("book", x, startY, LIST_W, viewH, contentH, mouseX, mouseY);
        int y = startY - scrollOf("book");

        // Clipped rather than truncated. The book used to stop at six a side with no
        // indication there was more, which on a busy item hid most of the market.
        beginClip(x, startY, LIST_W, viewH);
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
        int top = first[1] - 2;
        int bottom = last[1] + last[3] + 2;

        fill(m, first[0] - 2, top - 1, first[0] + NAV_W + 2, bottom + 1, 0xFFFFDD66);
        fill(m, first[0] - 1, top, first[0] + NAV_W + 1, bottom, 0xF0181818);

        for (int i = 0; i < SCREEN_NAMES.length; i++) {
            int[] r = navRowRect(i);
            boolean here = i == activeScreen;
            boolean hot = within(mouseX, mouseY, r);
            if (here) {
                fill(m, r[0], r[1], r[0] + r[2], r[1] + r[3], 0xFF3A3A2A);
            } else if (hot) {
                fill(m, r[0], r[1], r[0] + r[2], r[1] + r[3], 0xFF303030);
            }
            label(m, SCREEN_NAMES[i], r[0] + 8, r[1] + 4,
                    here ? 0xFFDD66 : (hot ? 0xFFFFFF : 0xC0C0C0));
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
        // Dim everything behind it, so it reads as blocking rather than as another
        // widget competing for attention with the rest of the screen.
        fill(m, 0, 0, this.width, this.height, 0xC0101010);

        int[] box = overlayBox(o);
        int accent = o.kind == Overlay.DANGER ? 0xFFFF6655
                : o.kind == Overlay.CONFIRM ? 0xFFFFCC66 : 0xFF88CCFF;

        fill(m, box[0] - 1, box[1] - 1, box[0] + box[2] + 1, box[1] + box[3] + 1, accent);
        fill(m, box[0], box[1], box[0] + box[2], box[1] + box[3], 0xFF202020);

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