package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.text.LiteralText;
import java.util.UUID;

import java.util.List;

public class MarketScreen extends Screen {

    private TextFieldWidget amountField;
    private TextFieldWidget itemField;
    private TextFieldWidget priceField;

    private String status = "";

    private static final int FIELD_WIDTH = 80;
    private static final int FIELD_HEIGHT = 20;
    private static final int FIELD_GAP = 16;
    private static final long MAX_QTY = 100_000L;

    public MarketScreen() {
        super(new LiteralText("Market"));
    }

    @Override
    protected void init() {
        super.init();

        int rowX = (int) (this.width * 0.42);
        int rowY = (int) (this.height * 0.28);

        this.amountField = new TextFieldWidget(this.textRenderer,
                rowX, rowY, FIELD_WIDTH, FIELD_HEIGHT, new LiteralText("Amount"));
        this.itemField = new TextFieldWidget(this.textRenderer,
                rowX + (FIELD_WIDTH + FIELD_GAP), rowY, FIELD_WIDTH, FIELD_HEIGHT, new LiteralText("Item"));
        this.priceField = new TextFieldWidget(this.textRenderer,
                rowX + (FIELD_WIDTH + FIELD_GAP) * 2, rowY, FIELD_WIDTH, FIELD_HEIGHT, new LiteralText("Price"));

        this.itemField.setMaxLength(64);
        this.itemField.setText("minecraft:iron_ingot");

        this.addChild(this.amountField);
        this.addChild(this.itemField);
        this.addChild(this.priceField);

        int buttonsY = rowY + FIELD_HEIGHT + 40;
        int buttonWidth = 74;

        // Row 1: Buy (left) and Sell (right)
        this.addButton(new ButtonWidget(
                rowX, buttonsY, buttonWidth, FIELD_HEIGHT,
                new LiteralText("Buy"),
                button -> onBuy()));

        this.addButton(new ButtonWidget(
                rowX + (FIELD_WIDTH + FIELD_GAP) * 2 + FIELD_WIDTH - buttonWidth, buttonsY,
                buttonWidth, FIELD_HEIGHT,
                new LiteralText("Sell"),
                button -> onSell()));

        // Row 2: Withdraw and the temporary credits button, side by side
        this.addButton(new ButtonWidget(
                rowX, buttonsY + 24, 100, FIELD_HEIGHT,
                new LiteralText("Withdraw"),
                button -> onWithdraw()));

        this.addButton(new ButtonWidget(
                rowX + 110, buttonsY + 24, 100, FIELD_HEIGHT,
                new LiteralText("+1000 credits"),
                button -> onAddCredits()));
    }

    // ─────────── actions ───────────

    /** Parsed and validated form input, or null if invalid (status already set). */
    private OrderRequest parseForm() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            status = "No player";
            return null;
        }

        long qty, price;
        try {
            qty = Long.parseLong(amountField.getText().trim());
            price = Long.parseLong(priceField.getText().trim());
        } catch (NumberFormatException e) {
            status = "Amount and price must be whole numbers";
            return null;
        }
        if (qty <= 0 || price <= 0) {
            status = "Amount and price must be positive";
            return null;
        }
        if (qty > MAX_QTY) {
            status = "Amount too large (max " + MAX_QTY + ")";
            return null;
        }

        Item item = MinecraftIds.itemFromName(itemField.getText().trim());
        if (item == Items.AIR) {
            status = "Unknown item (try minecraft:iron_ingot)";
            return null;
        }

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

        // Remove physical items FIRST
        if (!InventoryBridge.remove(mc.player, req.item, (int) req.qty)) {
            status = "You don't have " + req.qty + " of that";
            return;
        }

        // Deposit event
        Event.Deposit dep = new Event.Deposit();
        dep.userId = req.userId;
        dep.itemId = req.itemId;
        dep.quantity = req.qty;
        dep.timestamp = System.currentTimeMillis();
        MarketStateHolder.submit(dep);

        // Place order event
        Event.PlaceOrder order = new Event.PlaceOrder();
        order.userId = req.userId;
        order.itemId = req.itemId;
        order.price = req.price;
        order.volume = req.qty;
        order.isBid = false;
        order.timestamp = System.currentTimeMillis();
        EventApplier.Result r = MarketStateHolder.submit(order);

        status = r.accepted
                ? (r.fills.isEmpty() ? "Listed " + req.qty + " at " + req.price
                   : "Sold — " + r.fills.size() + " fill(s)")
                : "Rejected: " + r.reason;
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

        EventApplier.Result r = MarketStateHolder.submit(order);

        if (!r.accepted) {
            status = "Rejected: " + r.reason;
        } else if (r.fills.isEmpty()) {
            status = "Bid placed for " + req.qty + " at " + req.price;
        } else {
            status = "Bought — " + r.fills.size() + " fill(s)";
        }
    }

    /** Withdraw ignores the price field — only amount and item matter. */
    private void onWithdraw() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            status = "No player";
            return;
        }

        long qty;
        try {
            qty = Long.parseLong(amountField.getText().trim());
        } catch (NumberFormatException e) {
            status = "Amount must be a whole number";
            return;
        }
        if (qty <= 0) { status = "Amount must be positive"; return; }
        if (qty > MAX_QTY) { status = "Amount too large (max " + MAX_QTY + ")"; return; }

        Item item = MinecraftIds.itemFromName(itemField.getText().trim());
        if (item == Items.AIR) { status = "Unknown item"; return; }

        Event.Withdraw w = new Event.Withdraw();
        w.userId = MinecraftIds.userIdOf(mc.player);
        w.itemId = MinecraftIds.itemToId(item);
        w.quantity = qty;
        w.timestamp = System.currentTimeMillis();

        // Ledger debit happens via the event. Only grant items if it succeeded.
        EventApplier.Result r = MarketStateHolder.submit(w);
        if (!r.accepted) {
            status = "Rejected: " + r.reason;
            return;
        }

        InventoryBridge.give(mc.player, item, (int) qty);
        status = "Withdrew " + qty;
    }

    /** TEMPORARY debug helper — remove before release. */
    private void onAddCredits() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        UUID uid = MinecraftIds.userIdOf(mc.player);

        Event.InjectCredits ic = new Event.InjectCredits();
        ic.userId = uid;          // initiator
        ic.targetUserId = uid;    // recipient
        ic.amount = 1000;
        ic.timestamp = System.currentTimeMillis();

        EventApplier.Result r = MarketStateHolder.submit(ic);
        status = r.accepted ? "Added 1000 credits" : "Rejected: " + r.reason;
    }

    // ─────────── rendering ───────────

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);

        drawCenteredText(matrices, this.textRenderer, this.title,
                this.width / 2, 16, 0xFFFFFF);

        int rowX = (int) (this.width * 0.42);
        int rowY = (int) (this.height * 0.28);
        int listX = (int) (this.width * 0.08);

        label(matrices, "Amount", rowX, rowY - 11, 0xA0A0A0);
        label(matrices, "Item", rowX + (FIELD_WIDTH + FIELD_GAP), rowY - 11, 0xA0A0A0);
        label(matrices, "Price", rowX + (FIELD_WIDTH + FIELD_GAP) * 2, rowY - 11, 0xA0A0A0);

        label(matrices, "Order book:", listX, rowY - 11, 0xFFFFFF);

        renderBook(matrices, listX, rowY + 6);
        renderBalances(matrices, listX);

        if (!status.isEmpty()) {
            label(matrices, status, rowX, rowY + FIELD_HEIGHT + 94, 0xFFDD66);
        }

        this.amountField.render(matrices, mouseX, mouseY, delta);
        this.itemField.render(matrices, mouseX, mouseY, delta);
        this.priceField.render(matrices, mouseX, mouseY, delta);

        super.render(matrices, mouseX, mouseY, delta);
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

    /** Small holder for parsed form input. */
    private static class OrderRequest {
        Item item;
        String itemId;
        UUID userId;
        long qty;
        long price;
    }
}