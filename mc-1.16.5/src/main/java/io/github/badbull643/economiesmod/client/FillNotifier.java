package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.Fill;
import io.github.badbull643.economiesmod.core.MarketState;
import io.github.badbull643.economiesmod.core.Settings;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.util.Formatting;

import java.util.List;
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

        long delta;
        MutableText chat;
        MutableText bar;

        if (bought && sold) {
            // Filling your own order washes out on price — you pay yourself and the
            // goods come back — but the fee does not. It is taken from the seller's
            // proceeds and burned, and that side is you as well, so the trade costs
            // exactly the fee. Silent used to mean free; it only ever meant silent.
            long fee = selfTradeFee(fill);
            if (fee <= 0) return;
            delta = -fee;
            chat = selfLine(fill, fee);
            bar = selfShortLine(fill, fee);
        } else {
            delta = bought ? -fill.amount() : fill.amount();
            chat = line(fill, bought, delta, resting);
            bar = shortLine(fill, bought, delta);
        }

        rollWindow();
        int limit = settings.notifyMaxPerMinute();
        if (limit > 0 && sentThisWindow < limit) {
            sentThisWindow++;
            send(settings, chat, bar);
        } else {
            batchedFills++;
            batchedNet += delta;
        }
    }

    /**
     * One line for an order of your own that crossed several resting ones at once.
     *
     * The case this exists for: nine resting buy orders taken by a single listing, which
     * on screen is nine rows vanishing in one frame. A sweep and a wipe look identical
     * there, and the mod said nothing that told them apart — the per-fill lines below
     * would have arrived as nine greys or as a batched "9 more fills", neither of which
     * says *your order did that*. Reported here rather than at the submit, because when
     * a host is sequencing, the submit returns before anybody knows what it crossed.
     *
     * Says what is left as well as what went. An order that fills in part leaves the
     * remainder resting, and "sold 9 of 10" is the difference between a trade that
     * finished and one that is still open — which is exactly the thing somebody goes
     * looking for in the book afterwards.
     *
     * @param ordered how much the order asked for, or 0 when that is not known — the
     *                remainder line is skipped rather than guessed at.
     */
    public void onOwnSweep(List<Fill> fills, UUID me, long ordered) {
        Settings settings = MarketStateHolder.settings();
        if (settings == null) return;
        if (!settings.notifyChat() && !settings.notifyActionBar()) return;
        if (fills == null || fills.isEmpty()) return;

        long quantity = 0;
        long delta = 0;
        boolean bought = false;
        String itemId = null;
        for (Fill f : fills) {
            boolean iBought = me.equals(f.buyerId());
            boolean iSold = me.equals(f.sellerId());
            if (!iBought && !iSold) continue;
            if (iBought && iSold) {
                // Crossing your own book. The existing per-fill path explains that case
                // properly, including the fee, and it is rare enough not to be worth a
                // second telling of it here.
                return;
            }
            quantity += f.quantity();
            delta += iBought ? -f.amount() : f.amount();
            bought = iBought;
            itemId = f.itemId();
        }
        if (quantity == 0 || itemId == null) return;

        long resting = ordered > quantity ? ordered - quantity : 0;
        MutableText chat = prefix()
                .append(new LiteralText(bought ? "Bought " : "Sold ")
                        .formatted(Formatting.GRAY))
                .append(new LiteralText(quantity + (ordered > 0 ? " of " + ordered : "")
                                + " " + name(itemId))
                        .formatted(Formatting.WHITE))
                .append(new LiteralText(" across " + fills.size() + " orders · ")
                        .formatted(Formatting.DARK_GRAY))
                .append(credits(delta));
        if (resting > 0) {
            chat.append(new LiteralText(" · " + resting + " still resting")
                    .formatted(Formatting.AQUA));
        }

        MutableText bar = new LiteralText((bought ? "Bought " : "Sold ") + quantity + " ")
                .formatted(Formatting.GRAY)
                .append(new LiteralText(name(itemId)).formatted(Formatting.WHITE))
                .append(new LiteralText(" ").formatted(Formatting.DARK_GRAY))
                .append(credits(delta));

        // Outside the window counter. This is one line for one thing the player just
        // did, so it cannot flood the way a market-maker's resting orders can, and
        // batching the feedback for an action into next minute's summary would be the
        // silence this method exists to end.
        send(settings, chat, bar);
    }

    private static String name(String itemId) {
        Item item = MinecraftIds.idToItem(itemId);
        return item == Items.AIR ? itemId : item.getName().getString();
    }

    /**
     * What filling your own order actually cost.
     *
     * Zero at a zero rate, where the trade genuinely nets to nothing and there is
     * nothing worth saying. Above it the credits leave the market altogether — the fee
     * is burned rather than paid to anyone — so neither side of you gets them back.
     *
     * Read from the market rather than the fill, because the rate is policy and the
     * fill only records what changed hands.
     */
    private static long selfTradeFee(Fill fill) {
        MarketState market = MarketStateHolder.get();
        if (market == null) return 0;
        return MarketState.taxOn(fill.amount(), market.taxBps());
    }

    /** Named as your own doing, since nobody traded with you and nothing is owed. */
    private MutableText selfLine(Fill fill, long fee) {
        return prefix()
                .append(new LiteralText("You filled your own order: ")
                        .formatted(Formatting.GRAY))
                .append(new LiteralText(fill.quantity() + " " + itemName(fill))
                        .formatted(Formatting.WHITE))
                .append(new LiteralText(" @ ").formatted(Formatting.DARK_GRAY))
                .append(new LiteralText(String.valueOf(fill.price()))
                        .formatted(Formatting.YELLOW))
                .append(new LiteralText(" · fee ").formatted(Formatting.DARK_GRAY))
                .append(credits(-fee));
    }

    private MutableText selfShortLine(Fill fill, long fee) {
        return new LiteralText("Own order " + fill.quantity() + " ")
                .formatted(Formatting.GRAY)
                .append(new LiteralText(itemName(fill)).formatted(Formatting.WHITE))
                .append(new LiteralText(" fee ").formatted(Formatting.DARK_GRAY))
                .append(credits(-fee));
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
