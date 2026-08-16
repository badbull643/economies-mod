package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.Fill;
import io.github.badbull643.economiesmod.core.Settings;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.util.Formatting;

import java.util.UUID;

/**
 * Tells you when one of your orders trades.
 *
 * The point of a market you can log off from is that things happen while you are not
 * looking. Without this the only way to find out was to open the screen and compare
 * numbers against what you remembered — which is not something anyone does.
 *
 * Rate limited, because a market-maker on a busy host can have dozens of orders fill
 * in a second and a message per fill would bury the chat. Over the limit, fills are
 * batched into one line: you lose the detail, never the news.
 */
public final class FillNotifier {

    private static final long WINDOW_MS = 60_000L;

    private long windowStarted = System.currentTimeMillis();
    private int sentThisWindow;

    private int batchedFills;
    private long batchedNet;

    /**
     * Records a fill the local player was part of.
     *
     * @param resting true when the player's order was the one sitting on the book —
     *                the case worth being told about, since the other kind happened
     *                because they just pressed a button.
     */
    public void onFill(Fill fill, UUID me, boolean resting) {
        Settings settings = MarketStateHolder.settings();
        if (settings == null) return;
        if (!settings.notifyChat() && !settings.notifyActionBar()) return;

        boolean bought = me.equals(fill.buyerId());
        boolean sold = me.equals(fill.sellerId());
        if (!bought && !sold) return;
        // Trading with yourself nets to nothing and is not news.
        if (bought && sold) return;

        long delta = bought ? -fill.amount() : fill.amount();

        rollWindow();
        int limit = settings.notifyMaxPerMinute();
        if (limit > 0 && sentThisWindow < limit) {
            sentThisWindow++;
            send(settings, line(fill, bought, delta, resting), shortLine(fill, bought, delta));
        } else {
            batchedFills++;
            batchedNet += delta;
        }
    }

    /** Flushes any batched fills once their window closes. Call from the client tick. */
    public void tick() {
        if (batchedFills == 0) return;
        if (System.currentTimeMillis() - windowStarted < WINDOW_MS) return;

        Settings settings = MarketStateHolder.settings();
        if (settings != null) {
            MutableText text = prefix()
                    .append(new LiteralText(batchedFills + " more fills")
                            .formatted(Formatting.WHITE))
                    .append(new LiteralText(" · ").formatted(Formatting.DARK_GRAY))
                    .append(credits(batchedNet));
            send(settings, text, new LiteralText(batchedFills + " fills " )
                    .formatted(Formatting.GRAY).append(credits(batchedNet)));
        }

        batchedFills = 0;
        batchedNet = 0;
        rollWindow();
    }

    private void rollWindow() {
        long now = System.currentTimeMillis();
        if (now - windowStarted >= WINDOW_MS) {
            windowStarted = now;
            sentThisWindow = 0;
        }
    }

    /** The server-notice look: a coloured tag, then the message. */
    private static MutableText prefix() {
        return new LiteralText("[").formatted(Formatting.DARK_GRAY)
                .append(new LiteralText("Exchange").formatted(Formatting.GOLD))
                .append(new LiteralText("] ").formatted(Formatting.DARK_GRAY));
    }

    private static MutableText credits(long delta) {
        String sign = delta >= 0 ? "+" : "";
        return new LiteralText(sign + delta + "cr")
                .formatted(delta >= 0 ? Formatting.GREEN : Formatting.RED);
    }

    private static String itemName(Fill fill) {
        Item item = MinecraftIds.idToItem(fill.itemId());
        return item == Items.AIR ? fill.itemId() : item.getName().getString();
    }

    private MutableText line(Fill fill, boolean bought, long delta, boolean resting) {
        MutableText text = prefix();
        // Only the resting case is really a notification; the other is feedback for
        // something the player did a moment ago, and saying so keeps them apart.
        text.append(new LiteralText(resting
                        ? (bought ? "Your buy order filled: " : "Your sell order filled: ")
                        : (bought ? "Bought " : "Sold "))
                .formatted(resting ? Formatting.AQUA : Formatting.GRAY));

        text.append(new LiteralText(fill.quantity() + " " + itemName(fill))
                .formatted(Formatting.WHITE));
        text.append(new LiteralText(" @ ").formatted(Formatting.DARK_GRAY));
        text.append(new LiteralText(String.valueOf(fill.price()))
                .formatted(Formatting.YELLOW));
        text.append(new LiteralText(" · ").formatted(Formatting.DARK_GRAY));
        text.append(credits(delta));
        return text;
    }

    /** The action bar overwrites itself, so it gets the short version. */
    private MutableText shortLine(Fill fill, boolean bought, long delta) {
        return new LiteralText((bought ? "Bought " : "Sold ") + fill.quantity() + " ")
                .formatted(Formatting.GRAY)
                .append(new LiteralText(itemName(fill)).formatted(Formatting.WHITE))
                .append(new LiteralText(" ").formatted(Formatting.DARK_GRAY))
                .append(credits(delta));
    }

    private void send(Settings settings, MutableText chat, MutableText actionBar) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        // Never Screen.sendMessage — that broadcasts to the server as player chat.
        // These are notices to one person about their own orders.
        if (settings.notifyChat()) {
            mc.player.sendMessage(chat, false);
        }
        if (settings.notifyActionBar()) {
            mc.player.sendMessage(actionBar, true);
        }
    }
}
