package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.Event;
import io.github.badbull643.economiesmod.core.MarketState;
import io.github.badbull643.economiesmod.core.Order;
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
import java.util.List;
import java.util.UUID;

public class MarketScreen extends Screen {

    private TextFieldWidget amountField;
    private TextFieldWidget itemField;
    private TextFieldWidget priceField;
    private TextFieldWidget hostField;

    /** Set from the game thread by button handlers, and from the network thread
     *  by callbacks — hence volatile. */
    private static volatile String status = "";

    private static final int FIELD_WIDTH = 80;
    private static final int FIELD_HEIGHT = 20;
    private static final int FIELD_GAP = 16;
    private static final long MAX_QTY = 100_000L;
    private boolean resetArmed = false;

    public MarketScreen() {
        super(new LiteralText("Market"));
    }

    @Override
    protected void init() {
        super.init();

        // Route rejections from the network thread into the status line.
        MarketStateHolder.setOnRejected(reason -> status = "Rejected: " + reason);

        int rowX = (int) (this.width * 0.42);
        int rowY = (int) (this.height * 0.28);

        this.amountField = new TextFieldWidget(this.textRenderer,
                rowX, rowY, FIELD_WIDTH, FIELD_HEIGHT, new LiteralText("Amount"));
        this.itemField = new TextFieldWidget(this.textRenderer,
                rowX + (FIELD_WIDTH + FIELD_GAP), rowY, FIELD_WIDTH, FIELD_HEIGHT,
                new LiteralText("Item"));
        this.priceField = new TextFieldWidget(this.textRenderer,
                rowX + (FIELD_WIDTH + FIELD_GAP) * 2, rowY, FIELD_WIDTH, FIELD_HEIGHT,
                new LiteralText("Price"));

        this.itemField.setMaxLength(64);
        this.itemField.setText("minecraft:iron_ingot");

        this.addChild(this.amountField);
        this.addChild(this.itemField);
        this.addChild(this.priceField);

        int buttonsY = rowY + FIELD_HEIGHT + 40;
        int buttonWidth = 74;

        // Row 1: Buy / Sell
        this.addButton(new ButtonWidget(rowX, buttonsY, buttonWidth, FIELD_HEIGHT,
                new LiteralText("Buy"), b -> onBuy()));
        this.addButton(new ButtonWidget(
                rowX + (FIELD_WIDTH + FIELD_GAP) * 2 + FIELD_WIDTH - buttonWidth, buttonsY,
                buttonWidth, FIELD_HEIGHT, new LiteralText("Sell"), b -> onSell()));

        // Row 2: Withdraw / credits
        this.addButton(new ButtonWidget(rowX, buttonsY + 24, 100, FIELD_HEIGHT,
                new LiteralText("Withdraw"), b -> onWithdraw()));
        this.addButton(new ButtonWidget(rowX + 110, buttonsY + 24, 100, FIELD_HEIGHT,
                new LiteralText("+1000 credits"), b -> onAddCredits()));

        // Row 3: connection controls
        int connY = buttonsY + 56;
        this.hostField = new TextFieldWidget(this.textRenderer,
                rowX, connY, 140, FIELD_HEIGHT, new LiteralText("Host"));
        this.hostField.setMaxLength(64);
        this.hostField.setText("localhost:25555");
        this.addChild(this.hostField);

        this.addButton(new ButtonWidget(rowX + 150, connY, 70, FIELD_HEIGHT,
                new LiteralText("Connect"), b -> onConnect()));
        this.addButton(new ButtonWidget(rowX + 150, connY + 24, 70, FIELD_HEIGHT,
                new LiteralText("Disconnect"), b -> onDisconnect()));

        this.addButton(new ButtonWidget(rowX + 230, connY, 70, FIELD_HEIGHT,
                new LiteralText("Host"), b -> onHost()));
        this.addButton(new ButtonWidget(rowX + 230, connY + 24, 70, FIELD_HEIGHT,
                new LiteralText("Stop"), b -> onStopHosting()));
        this.addButton(new ButtonWidget(rowX, connY + 48, 90, FIELD_HEIGHT,
                new LiteralText("Reset log"), b -> onReset()));
    }

    private void onReset() {
        if (!resetArmed) {
            resetArmed = true;
            status = "Click again to DISCARD all local market history";
            return;
        }
        resetArmed = false;
        MarketStateHolder.resetLog();
        status = "Local history discarded — reconnect to sync";
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
        int port;
        try {
            int colon = text.lastIndexOf(':');
            if (colon < 0) {
                host = text;
                port = 25555;
            } else {
                host = text.substring(0, colon);
                port = Integer.parseInt(text.substring(colon + 1));
            }
        } catch (NumberFormatException e) {
            status = "Bad host — use host:port";
            return;
        }

        status = "Connecting to " + host + ":" + port + "...";
        UUID me = MinecraftIds.userIdOf(mc.player);

        // Don't block the game thread on a socket connect.
        new Thread(() -> {
            MarketStateHolder.connect(host, port, me);
            status = MarketStateHolder.isConnected()
                    ? "Connected to " + host + ":" + port
                    : "Connect failed";
        }, "market-connect").start();
    }

    private void onDisconnect() {
        MarketStateHolder.disconnect();
        status = "Disconnected — using local market";
    }

    ///////////////////
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

        Path worldDir = mc.getServer().getSavePath(WorldSavePath.ROOT);
        UUID me = MinecraftIds.userIdOf(mc.player);

        status = "Starting host...";
        new Thread(() -> {
            MarketStateHolder.startHosting(worldDir, 25555, me);
            status = MarketStateHolder.mode() == MarketStateHolder.Mode.HOSTING
                    ? "Hosting on port 25555"
                    : "Failed to start host";
        }, "market-host-start").start();
    }

    private void onStopHosting() {
        MarketStateHolder.stopHosting();
        status = "Stopped hosting";
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
                "Listed " + req.qty + " at " + req.price,
                "Sell sent...");
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
                "Bid placed for " + req.qty + " at " + req.price,
                "Buy sent...");
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

        // Items are granted by the onApplied callback once the withdrawal is
        // confirmed — locally that's immediate, over the network it's when the
        // host broadcasts it back.
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

    /** Turns a Submission into a status message. */
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

        int rowX = (int) (this.width * 0.42);
        int rowY = (int) (this.height * 0.28);
        int listX = (int) (this.width * 0.08);

        label(matrices, "Amount", rowX, rowY - 11, 0xA0A0A0);
        label(matrices, "Item", rowX + (FIELD_WIDTH + FIELD_GAP), rowY - 11, 0xA0A0A0);
        label(matrices, "Price", rowX + (FIELD_WIDTH + FIELD_GAP) * 2, rowY - 11, 0xA0A0A0);
        label(matrices, "Order book:", listX, rowY - 11, 0xFFFFFF);

        renderConnectionStatus(matrices, listX, 30);
        renderBook(matrices, listX, rowY + 6);
        renderBalances(matrices, listX);

        if (!status.isEmpty()) {
            label(matrices, status, rowX, rowY + FIELD_HEIGHT + 154, 0xFFDD66);
        }

        this.amountField.render(matrices, mouseX, mouseY, delta);
        this.itemField.render(matrices, mouseX, mouseY, delta);
        this.priceField.render(matrices, mouseX, mouseY, delta);
        this.hostField.render(matrices, mouseX, mouseY, delta);

        super.render(matrices, mouseX, mouseY, delta);
    }

    private void renderConnectionStatus(MatrixStack matrices, int x, int y) {
        if (MarketStateHolder.mode() == MarketStateHolder.Mode.HOSTING) {
            label(matrices, "● hosting", x, y, 0xFFDD66);
        }else if (MarketStateHolder.isConnected()) {
            label(matrices, "● connected to host", x, y, 0x88FF88);
        } else if (MarketStateHolder.mode() == MarketStateHolder.Mode.CONNECTED) {
            label(matrices, "● connection lost", x, y, 0xFF8888);
        } else {
            label(matrices, "● local market", x, y, 0xAAAAAA);
        }
    }

    private void renderBook(MatrixStack matrices, int x, int startY) {
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
            label(matrices, "SELL  " + o.volume() + " @ " + o.value(), x, y, 0xFF8888);
            y += rowHeight;
        }
        y += 4;
        shown = 0;
        for (Order o : bids) {
            if (shown++ >= 6) break;
            label(matrices, "BUY   " + o.volume() + " @ " + o.value(), x, y, 0x88FF88);
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
}