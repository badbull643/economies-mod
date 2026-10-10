package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.Fill;
import io.github.badbull643.economiesmod.core.MarketState;
import io.github.badbull643.economiesmod.core.Settings;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

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
        MutableComponent chat;
        MutableComponent bar;

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
        MutableComponent chat = prefix()
                .append(Component.literal(bought ? "Bought " : "Sold ")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal(quantity + (ordered > 0 ? " of " + ordered : "")
                                + " " + name(itemId))
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" across " + fills.size() + " orders · ")
                        .withStyle(ChatFormatting.DARK_GRAY))
                .append(credits(delta));
        if (resting > 0) {
            chat.append(Component.literal(" · " + resting + " still resting")
                    .withStyle(ChatFormatting.AQUA));
        }

        MutableComponent bar = Component.literal((bought ? "Bought " : "Sold ") + quantity + " ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(name(itemId)).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY))
                .append(credits(delta));

        // Outside the window counter. This is one line for one thing the player just
        // did, so it cannot flood the way a market-maker's resting orders can, and
        // batching the feedback for an action into next minute's summary would be the
        // silence this method exists to end.
        send(settings, chat, bar);
    }

    private static String name(String itemId) {
        Item item = MinecraftIds.idToItem(itemId);
        return item == Items.AIR ? itemId : MinecraftIds.nameOf(item).getString();
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
    private MutableComponent selfLine(Fill fill, long fee) {
        return prefix()
                .append(Component.literal("You filled your own order: ")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal(fill.quantity() + " " + itemName(fill))
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" @ ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(String.valueOf(fill.price()))
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" · fee ").withStyle(ChatFormatting.DARK_GRAY))
                .append(credits(-fee));
    }

    private MutableComponent selfShortLine(Fill fill, long fee) {
        return Component.literal("Own order " + fill.quantity() + " ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(itemName(fill)).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" fee ").withStyle(ChatFormatting.DARK_GRAY))
                .append(credits(-fee));
    }

    /** Flushes any batched fills once their window closes. Call from the client tick. */
    public void tick() {
        if (batchedFills == 0) return;
        if (System.currentTimeMillis() - windowStarted < WINDOW_MS) return;

        Settings settings = MarketStateHolder.settings();
        if (settings != null) {
            MutableComponent text = prefix()
                    .append(Component.literal(batchedFills + " more fills")
                            .withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(credits(batchedNet));
            send(settings, text, Component.literal(batchedFills + " fills " )
                    .withStyle(ChatFormatting.GRAY).append(credits(batchedNet)));
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
    private static MutableComponent prefix() {
        return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal("Exchange").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static MutableComponent credits(long delta) {
        String sign = delta >= 0 ? "+" : "";
        return Component.literal(sign + delta + "cr")
                .withStyle(delta >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private static String itemName(Fill fill) {
        Item item = MinecraftIds.idToItem(fill.itemId());
        return item == Items.AIR ? fill.itemId() : MinecraftIds.nameOf(item).getString();
    }

    private MutableComponent line(Fill fill, boolean bought, long delta, boolean resting) {
        MutableComponent text = prefix();
        // Only the resting case is really a notification; the other is feedback for
        // something the player did a moment ago, and saying so keeps them apart.
        text.append(Component.literal(resting
                        ? (bought ? "Your buy order filled: " : "Your sell order filled: ")
                        : (bought ? "Bought " : "Sold "))
                .withStyle(resting ? ChatFormatting.AQUA : ChatFormatting.GRAY));

        text.append(Component.literal(fill.quantity() + " " + itemName(fill))
                .withStyle(ChatFormatting.WHITE));
        text.append(Component.literal(" @ ").withStyle(ChatFormatting.DARK_GRAY));
        text.append(Component.literal(String.valueOf(fill.price()))
                .withStyle(ChatFormatting.YELLOW));
        text.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
        text.append(credits(delta));
        return text;
    }

    /** The action bar overwrites itself, so it gets the short version. */
    private MutableComponent shortLine(Fill fill, boolean bought, long delta) {
        return Component.literal((bought ? "Bought " : "Sold ") + fill.quantity() + " ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(itemName(fill)).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY))
                .append(credits(delta));
    }

    private void send(Settings settings, MutableComponent chat, MutableComponent actionBar) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Never Screen.sendMessage — that broadcasts to the server as player chat.
        // These are notices to one person about their own orders.
        if (settings.notifyChat()) {
            mc.player.sendSystemMessage(chat);
        }
        if (settings.notifyActionBar()) {
            mc.player.sendOverlayMessage(actionBar);
        }
    }
}
