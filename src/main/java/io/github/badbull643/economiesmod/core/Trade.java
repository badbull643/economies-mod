package io.github.badbull643.economiesmod.core;

import java.util.UUID;

/**
 * A fill, plus when it happened.
 *
 * A {@link Fill} says what changed hands; it carries no time, because the matching
 * engine has no clock — an order is matched against a book, not against a moment.
 * The time comes from the event that caused it, so a trade is only assembled here,
 * where both halves are in scope.
 *
 * Immutable, like Fill: this is history, and history does not get edited.
 */
public final class Trade {

    /** Sequence number of the event that produced this fill. Monotonic, so it orders
     *  trades reliably even when two events share a timestamp — or when an author's
     *  clock is wrong, which is not something the market can check. */
    public final long seq;

    /** The author's clock at the time. Useful for a human-readable axis; not to be
     *  trusted for ordering. Use seq for that. */
    public final long timestamp;

    public final String itemId;
    public final long price;
    public final long quantity;
    public final UUID buyerId;
    public final UUID sellerId;

    Trade(long seq, long timestamp, Fill fill) {
        this.seq = seq;
        this.timestamp = timestamp;
        this.itemId = fill.itemId();
        this.price = fill.price();
        this.quantity = fill.quantity();
        this.buyerId = fill.buyerId();
        this.sellerId = fill.sellerId();
    }

    /** Restore only — rebuilds a recorded trade from a snapshot, with no Fill behind it. */
    Trade(long seq, long timestamp, String itemId, long price, long quantity,
          UUID buyerId, UUID sellerId) {
        this.seq = seq;
        this.timestamp = timestamp;
        this.itemId = itemId;
        this.price = price;
        this.quantity = quantity;
        this.buyerId = buyerId;
        this.sellerId = sellerId;
    }

    /** Total currency that moved, for convenience. */
    public long amount() {
        return Math.multiplyExact(quantity, price);
    }

    @Override
    public String toString() {
        return "Trade{seq=" + seq + " " + quantity + " " + itemId + " @ " + price + "}";
    }
}
