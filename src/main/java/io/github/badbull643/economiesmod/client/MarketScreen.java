package io.github.badbull643.economiesmod.client;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

import java.util.List;

public class MarketScreen extends Screen {

    private TextFieldWidget amountField;
    private TextFieldWidget itemField;
    private TextFieldWidget priceField;

    // ---- Layout anchors (computed in init, used everywhere) ----
    private int panelX;        // left edge of the right-hand panel
    private int rowAmountY;     // Y of the amount field
    private int rowItemY;       // Y of the item field
    private int rowPriceY;      // Y of the price field
    private int buttonsY;       // Y of the buy/sell buttons

    private static final int FIELD_WIDTH = 80;    // narrower, since 3 sit side by side
    private static final int FIELD_HEIGHT = 20;
    private static final int FIELD_GAP = 16;
    private static final int ROW_GAP = 34;     // vertical distance between rows
    private static final int LABEL_OFFSET = 11;


    private final java.util.List<FakeOrder> fakeOrders = java.util.Arrays.asList(
            new FakeOrder(500, 247),
            new FakeOrder(320, 246),
            new FakeOrder(150, 244)
    );

    public MarketScreen() {
        super(new LiteralText("Market"));
    }

    @Override
    protected void init() {
        super.init();

        // Starting X for the row of fields, and a shared Y
        int rowX = (int) (this.width * 0.42);
        int rowY = (int) (this.height * 0.28);

        this.amountField = new TextFieldWidget(this.textRenderer,
                rowX, rowY, FIELD_WIDTH, FIELD_HEIGHT, new LiteralText("Amount"));
        this.itemField = new TextFieldWidget(this.textRenderer,
                rowX + (FIELD_WIDTH + FIELD_GAP), rowY, FIELD_WIDTH, FIELD_HEIGHT, new LiteralText("Item"));
        this.priceField = new TextFieldWidget(this.textRenderer,
                rowX + (FIELD_WIDTH + FIELD_GAP) * 2, rowY, FIELD_WIDTH, FIELD_HEIGHT, new LiteralText("Price"));

        this.addChild(this.amountField);
        this.addChild(this.itemField);
        this.addChild(this.priceField);

        // Buy / Sell buttons below the row of fields
        int buttonsY = rowY + FIELD_HEIGHT + 40;
        int buttonWidth = 74;

        // Buy under the first field, Sell under the third — bracketing the row like your sketch
        this.addButton(new ButtonWidget(
                rowX, buttonsY, buttonWidth, FIELD_HEIGHT,
                new LiteralText("Buy"),
                button -> onBuy()));

        this.addButton(new ButtonWidget(
                rowX + (FIELD_WIDTH + FIELD_GAP) * 2 + FIELD_WIDTH - buttonWidth, buttonsY, buttonWidth, FIELD_HEIGHT,
                new LiteralText("Sell"),
                button -> onSell()));
    }

    private void onBuy() {
        System.out.println("BUY  amount=" + amountField.getText()
                + " item=" + itemField.getText()
                + " price=" + priceField.getText());
    }

    private void onSell() {
        System.out.println("SELL amount=" + amountField.getText()
                + " item=" + itemField.getText()
                + " price=" + priceField.getText());
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);

        drawCenteredText(matrices, this.textRenderer, this.title,
                this.width / 2, 16, 0xFFFFFF);

        int rowX = (int) (this.width * 0.42);
        int rowY = (int) (this.height * 0.28);

        // Labels above each field
        drawTextWithShadow(matrices, this.textRenderer, new LiteralText("Amount"),
                rowX, rowY - 11, 0xA0A0A0);
        drawTextWithShadow(matrices, this.textRenderer, new LiteralText("Item"),
                rowX + (FIELD_WIDTH + FIELD_GAP), rowY - 11, 0xA0A0A0);
        drawTextWithShadow(matrices, this.textRenderer, new LiteralText("Price"),
                rowX + (FIELD_WIDTH + FIELD_GAP) * 2, rowY - 11, 0xA0A0A0);

        // Order book heading (left)
        drawTextWithShadow(matrices, this.textRenderer, new LiteralText("Order book:"),
                (int) (this.width * 0.08), rowY - 11, 0xFFFFFF);

        // Draw the fields explicitly
        this.amountField.render(matrices, mouseX, mouseY, delta);
        this.itemField.render(matrices, mouseX, mouseY, delta);
        this.priceField.render(matrices, mouseX, mouseY, delta);

        int listX = (int) (this.width * 0.08);
        int rowwY = (int) (this.height * 0.28);   // same rowY you use elsewhere
        int listStartY = rowwY + 6;               // first order sits just below the heading
        int rowHeight = 13;

        for (int i = 0; i < fakeOrders.size(); i++) {
            FakeOrder order = fakeOrders.get(i);
            String line = "Order " + (i + 1) + "   x" + order.amount + "   @" + order.price;
            drawTextWithShadow(matrices, this.textRenderer, new LiteralText(line),
                    listX, listStartY + i * rowHeight, 0xE0E0E0);
        }

        super.render(matrices, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;   // don't pause the game while the market is open
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Close on M (GLFW key code 77), so M toggles the screen shut
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_M) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static class FakeOrder {
        final int amount;
        final int price;
        FakeOrder(int amount, int price) { this.amount = amount; this.price = price; }
    }
}