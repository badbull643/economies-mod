package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.*;
import io.github.badbull643.economiesmod.core.net.PeerPoll;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.text.LiteralText;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class MarketScreen extends Screen {

    private TextFieldWidget amountField;
    private TextFieldWidget itemField;
    private TextFieldWidget priceField;
    private TextFieldWidget hostField;
    private TextFieldWidget cancelField;
    private TextFieldWidget hostPortField;

    /** Set from the game thread by button handlers, and from the network thread
     *  by callbacks — hence volatile. */
    private static volatile String status = "";

    private static final int FIELD_HEIGHT = 20;
    private static final int AMOUNT_W = 50;
    private static final int ITEM_W = 160;
    private static final int PRICE_W = 50;
    private static final int ITEM_X_OFF = AMOUNT_W + 10;              // 60
    private static final int PRICE_X_OFF = ITEM_X_OFF + ITEM_W + 10;  // 230
    private static final int ROW_WIDTH = PRICE_X_OFF + PRICE_W;       // 280
    private static final long MAX_QTY = 100_000L;

    private boolean resetArmed = false;

    // Row positions, set in init() so render and hit-tests can't drift.
    private int rowX;
    private int rowY;
    private int buttonsY;
    private int cancelY;
    private int connY;

    private static String savedHostText = "localhost:25555";
    private static String savedPortText = "25555";
    private static String savedItemText = "minecraft:iron_ingot";

    public MarketScreen() {
        super(new LiteralText("Market"));
    }

    @Override
    protected void init() {
        super.init();

        MarketStateHolder.setOnRejected(reason -> status = "Rejected: " + reason);

        this.rowX = (int) (this.width * 0.42);
        this.rowY = (int) (this.height * 0.28);
        this.buttonsY = rowY + FIELD_HEIGHT + 40;
        this.cancelY = buttonsY + 64;
        this.connY = cancelY + 32;

        // ─── Trade entry fields ───
        this.amountField = new TextFieldWidget(this.textRenderer,
                rowX, rowY, AMOUNT_W, FIELD_HEIGHT, new LiteralText("Amount"));
        this.itemField = new TextFieldWidget(this.textRenderer,
                rowX + ITEM_X_OFF, rowY, ITEM_W, FIELD_HEIGHT, new LiteralText("Item"));
        this.priceField = new TextFieldWidget(this.textRenderer,
                rowX + PRICE_X_OFF, rowY, PRICE_W, FIELD_HEIGHT, new LiteralText("Price"));

        this.itemField.setMaxLength(64);
        this.itemField.setText(savedItemText);

        this.addChild(this.amountField);
        this.addChild(this.itemField);
        this.addChild(this.priceField);

        int buttonWidth = 74;

        // ─── Row 1: Buy / Sell ───
        this.addButton(new ButtonWidget(rowX, buttonsY, buttonWidth, FIELD_HEIGHT,
                new LiteralText("Buy"), b -> onBuy()));
        this.addButton(new ButtonWidget(rowX + ROW_WIDTH - buttonWidth, buttonsY,
                buttonWidth, FIELD_HEIGHT, new LiteralText("Sell"), b -> onSell()));

        // ─── Row 2: Withdraw / credits ───
        this.addButton(new ButtonWidget(rowX, buttonsY + 24, 100, FIELD_HEIGHT,
                new LiteralText("Withdraw"), b -> onWithdraw()));
        this.addButton(new ButtonWidget(rowX + 110, buttonsY + 24, 100, FIELD_HEIGHT,
                new LiteralText("+1000 credits"), b -> onAddCredits()));

        // ─── Row 3: cancel by order id ───
        this.cancelField = new TextFieldWidget(this.textRenderer,
                rowX, cancelY, 60, FIELD_HEIGHT, new LiteralText("Order ID"));
        this.addChild(this.cancelField);
        this.addButton(new ButtonWidget(rowX + 70, cancelY, 70, FIELD_HEIGHT,
                new LiteralText("Cancel"), b -> onCancel()));

        // ─── Row 4: connection ───
        this.hostField = new TextFieldWidget(this.textRenderer,
                rowX, connY, 140, FIELD_HEIGHT, new LiteralText("Host"));
        this.hostField.setMaxLength(64);
        this.hostField.setText(savedHostText);
        this.addChild(this.hostField);

        this.addButton(new ButtonWidget(rowX + 150, connY, 70, FIELD_HEIGHT,
                new LiteralText("Connect"), b -> onConnect()));
        this.addButton(new ButtonWidget(rowX + 230, connY, 70, FIELD_HEIGHT,
                new LiteralText("Host"), b -> onHost()));

        this.hostPortField = new TextFieldWidget(this.textRenderer,
                rowX + 310, connY, 45, FIELD_HEIGHT, new LiteralText("Port"));
        this.hostPortField.setText(savedPortText);
        this.addChild(this.hostPortField);

        // ─── Row 5: disconnect / stop ───
        this.addButton(new ButtonWidget(rowX + 150, connY + 24, 70, FIELD_HEIGHT,
                new LiteralText("Disconnect"), b -> onDisconnect()));
        this.addButton(new ButtonWidget(rowX + 230, connY + 24, 70, FIELD_HEIGHT,
                new LiteralText("Stop"), b -> onStopHosting()));

        // ─── Row 6: log reset and discovery refresh ───
        this.addButton(new ButtonWidget(rowX, connY + 48, 90, FIELD_HEIGHT,
                new LiteralText("Reset log"), b -> onReset()));
        this.addButton(new ButtonWidget(rowX + 100, connY + 48, 90, FIELD_HEIGHT,
                new LiteralText("Refresh"), b -> startPoll()));

        startPoll();
    }

    private void onReset() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        UUID me = MinecraftIds.userIdOf(mc.player);

        if (!resetArmed) {
            resetArmed = true;
            status = "DISCARD everything? You would lose: "
                    + MarketStateHolder.describeLoss(me) + " — click again";
            return;
        }
        resetArmed = false;
        MarketStateHolder.resetLog();
        status = "Local history discarded";
    }

    // ─────────── connection ───────────

    private static long lastConnectAttempt = 0;

    private void onConnect() {
        long now = System.currentTimeMillis();
        if (now - lastConnectAttempt < 3000) {
            status = "Wait a moment before retrying";
            return;
        }
        lastConnectAttempt = now;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        String text = hostField.getText().trim();
        String host;
        int port = 25555;

        try {
            if (text.startsWith("[")) {
                // IPv6 in brackets: [2001:db8::1] or [2001:db8::1]:25555
                int close = text.indexOf(']');
                if (close < 0) {
                    status = "Bad address — missing closing bracket";
                    return;
                }
                host = text.substring(1, close);
                String rest = text.substring(close + 1);
                if (rest.startsWith(":")) {
                    port = Integer.parseInt(rest.substring(1));
                }
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
            status = "Bad port — use host:port or [ipv6]:port";
            return;
        }

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
            status = MarketStateHolder.isConnected()
                    ? "Connected to " + finalHost + ":" + finalPort
                    : "Connect failed";
        }, "market-connect").start();
    }

    private void onDisconnect() {
        MarketStateHolder.disconnect();
        status = "Disconnected — using local market";
    }

    private static long lastHostAttempt = 0;

    private void onHost() {
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

    private void onStopHosting() {
        MarketStateHolder.stopHosting();
        status = "Stopped hosting";
    }

    // ─────────── discovery ───────────

    private static volatile List<PeerPoll.HostInfo> discovered = Collections.emptyList();
    private static volatile boolean polling = false;
    private static final int DISCOVERY_ROW_HEIGHT = 12;

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
                discovered = PeerPoll.findHosts(others, 2000);
            } finally {
                polling = false;
            }
        }, "market-discovery").start();
    }

    private int discoveryStartY() {
        return connY + 76;
    }

    private void renderDiscovery(MatrixStack matrices) {
        int x = rowX;
        int y = discoveryStartY();

        label(matrices, "Hosts:", x, y, 0xFFFFFF);
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

        for (PeerPoll.HostInfo h : hosts) {
            boolean isSelf = h.reply.userId != null && h.reply.userId.equals(myUuid);
            String line = "  " + h.reply.hostName
                    + (isSelf ? " (you)" : "")
                    + "  (" + h.reply.lastSeq + " events, "
                    + h.reply.clientCount + " online)";
            label(matrices, line, x, y, isSelf ? 0xAAAAAA : 0x88CCFF);
            y += DISCOVERY_ROW_HEIGHT;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !polling) {
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
            status = MarketStateHolder.isConnected()
                    ? "Connected to " + hostLabel
                    : "Connect failed";
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
        OrderRequest req = parseForm();
        if (req == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();

        // Remove physical items FIRST — never credit before removing.
        if (!InventoryBridge.remove(mc.player, req.item, (int) req.qty)) {
            status = "You don't have " + req.qty + " of that";
            return;
        }

        Event.Deposit dep = new Event.Deposit();
        dep.userId = req.userId;
        dep.itemId = req.itemId;
        dep.quantity = req.qty;
        dep.timestamp = System.currentTimeMillis();
        MarketStateHolder.submit(dep);

        Event.PlaceOrder order = new Event.PlaceOrder();
        order.userId = req.userId;
        order.itemId = req.itemId;
        order.price = req.price;
        order.volume = req.qty;
        order.isBid = false;
        order.timestamp = System.currentTimeMillis();

        report(MarketStateHolder.submit(order),
                "Listed " + req.qty + " at " + req.price, "Sell sent...");
    }

    private void onBuy() {
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

    private void onAddCredits() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        UUID uid = MinecraftIds.userIdOf(mc.player);
        Event.InjectCredits ic = new Event.InjectCredits();
        ic.userId = uid;
        ic.targetUserId = uid;
        ic.amount = 1000;
        ic.timestamp = System.currentTimeMillis();

        report(MarketStateHolder.submit(ic), "Added 1000 credits", "Credits sent...");
    }

    private void onCancel() {
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

        drawCenteredText(matrices, this.textRenderer, this.title, this.width / 2, 16, 0xFFFFFF);

        int listX = (int) (this.width * 0.08);

        label(matrices, "Amount", rowX, rowY - 11, 0xA0A0A0);
        label(matrices, "Item", rowX + ITEM_X_OFF, rowY - 11, 0xA0A0A0);
        label(matrices, "Price", rowX + PRICE_X_OFF, rowY - 11, 0xA0A0A0);
        label(matrices, "Cancel order", rowX, cancelY - 11, 0xA0A0A0);
        label(matrices, "Order book:", listX, rowY - 11, 0xFFFFFF);
        label(matrices, "Port", rowX + 310, connY - 11, 0xA0A0A0);

        renderConnectionStatus(matrices, listX, 30);
        renderBook(matrices, listX, rowY + 6);
        renderBalances(matrices, listX);
        renderDiscovery(matrices);

        if (!status.isEmpty()) {
            label(matrices, status, listX, this.height - 24, 0xFFDD66);
        }

        this.amountField.render(matrices, mouseX, mouseY, delta);
        this.itemField.render(matrices, mouseX, mouseY, delta);
        this.priceField.render(matrices, mouseX, mouseY, delta);
        this.hostField.render(matrices, mouseX, mouseY, delta);
        this.cancelField.render(matrices, mouseX, mouseY, delta);
        this.hostPortField.render(matrices, mouseX, mouseY, delta);

        super.render(matrices, mouseX, mouseY, delta);
    }

    private void renderConnectionStatus(MatrixStack matrices, int x, int y) {
        if (MarketStateHolder.mode() == MarketStateHolder.Mode.HOSTING) {
            label(matrices, "● hosting", x, y, 0xFFDD66);
        } else if (MarketStateHolder.isConnected()) {
            label(matrices, "● connected to host", x, y, 0x88FF88);
        } else if (MarketStateHolder.mode() == MarketStateHolder.Mode.CONNECTED) {
            label(matrices, "● connection lost", x, y, 0xFF8888);
        } else {
            label(matrices, "● local market", x, y, 0xAAAAAA);
        }
    }

    private void renderBook(MatrixStack matrices, int x, int startY) {
        MinecraftClient mc = MinecraftClient.getInstance();
        UUID myUuid = mc.player != null ? MinecraftIds.userIdOf(mc.player) : null;

        Item item = MinecraftIds.itemFromName(itemField.getText().trim());
        if (item == Items.AIR) {
            label(matrices, "(enter an item to see its book)", x, startY, 0x808080);
            return;
        }

        String itemId = MinecraftIds.itemToId(item);
        MarketState market = MarketStateHolder.get();
        List<Order> asks = market.bookFor(itemId).restingAsks();
        List<Order> bids = market.bookFor(itemId).restingBids();

        if (asks.isEmpty() && bids.isEmpty()) {
            label(matrices, "(no orders)", x, startY, 0x808080);
            return;
        }

        int y = startY;
        int rowHeight = 13;

        int shown = 0;
        for (Order o : asks) {
            if (shown++ >= 6) break;
            boolean mine = myUuid != null && o.userID().equals(myUuid);
            label(matrices, (mine ? "* " : "  ") + "#" + o.orderId()
                            + " SELL " + o.volume() + " @ " + o.value(),
                    x, y, mine ? 0xFFCC66 : 0xFF8888);
            y += rowHeight;
        }

        y += 4;
        shown = 0;
        for (Order o : bids) {
            if (shown++ >= 6) break;
            boolean mine = myUuid != null && o.userID().equals(myUuid);
            label(matrices, (mine ? "* " : "  ") + "#" + o.orderId()
                            + " BUY  " + o.volume() + " @ " + o.value(),
                    x, y, mine ? 0xFFCC66 : 0x88FF88);
            y += rowHeight;
        }
    }

    private void renderBalances(MatrixStack matrices, int x) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        UUID userId = MinecraftIds.userIdOf(mc.player);
        MarketState market = MarketStateHolder.get();

        int y = this.height - 50;
        label(matrices, "Credits: " + market.wallets().getBalance(userId), x, y, 0xFFFF88);

        Item item = MinecraftIds.itemFromName(itemField.getText().trim());
        if (item != Items.AIR) {
            long held = market.itemBalances().getBalance(userId, MinecraftIds.itemToId(item));
            label(matrices, "Market credit: " + held + " " + item.getName().getString(),
                    x, y + 13, 0xFFFF88);
        }
    }

    private void label(MatrixStack m, String s, int x, int y, int colour) {
        drawTextWithShadow(m, this.textRenderer, new LiteralText(s), x, y, colour);
    }

    private static class OrderRequest {
        Item item;
        String itemId;
        UUID userId;
        long qty;
        long price;
    }

    /** Reads the port field, falling back to 25555 if it's not a valid number. */
    private int hostPortFromField() {
        try {
            int p = Integer.parseInt(hostPortField.getText().trim());
            return (p >= 1024 && p <= 65535) ? p : 25555;
        } catch (NumberFormatException e) {
            return 25555;
        }
    }

    @Override
    public void removed() {
        resetArmed = false;
        savedItemText = itemField.getText();
        savedItemText = itemField.getText();
        savedHostText = hostField.getText();
        savedPortText = hostPortField.getText();
        super.removed();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_M
                && !amountField.isFocused()
                && !itemField.isFocused()
                && !priceField.isFocused()
                && !hostField.isFocused()
                && !hostPortField.isFocused()
                && !cancelField.isFocused()) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

}