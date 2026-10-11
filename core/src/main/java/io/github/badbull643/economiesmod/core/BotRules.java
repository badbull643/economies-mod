package io.github.badbull643.economiesmod.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The fixed numbers bots live by, and the rules every player's game checks them against.
 *
 * Fixed in the mod, not configurable: an operator decides how many bots there are and which
 * are running, never what they are given or how fast they produce. These are part of the
 * market's rules — every replica reads them from here when it validates a bot event — so a
 * change to any of them is a change to what logs mean, and has to be versioned like one.
 *
 * Values from docs/BOTS-SPEC.md (proposed numbers, open for adjustment before release).
 */
public final class BotRules {

    private BotRules() {}

    /** The bot types. Lowercase words because they appear in logs and console commands. */
    public static final String PRODUCER = "producer";
    public static final String MARKET_MAKER = "maker";

    public static final List<String> TYPES =
            Collections.unmodifiableList(Arrays.asList(PRODUCER, MARKET_MAKER));

    public static boolean isType(String type) {
        return TYPES.contains(type);
    }

    /** Every bot's basket, and every producer's output. Ids that exist in every supported version. */
    public static final List<String> ITEMS = Collections.unmodifiableList(Arrays.asList(
            "minecraft:oak_log",
            "minecraft:cobblestone",
            "minecraft:coal",
            "minecraft:wheat",
            "minecraft:iron_ingot"));

    /** At most this many of each type in one market. */
    public static final int MAX_PER_TYPE = 8;

    // ─────────── versions ───────────
    //
    // A bot's grants, what it produces and its ceiling are part of the market's rules, so a
    // change to them is a change to what logs mean. Each bot records the algorithm version
    // it was spawned with, and these figures are looked up by that version — a log written
    // with version 1 bots replays exactly as it did, whatever version 2 says.
    //
    // Version 1: every producer makes all five items, 500 credits and 512 of each to start,
    // ceiling 512; a market maker gets 3,000 credits and 64 of each.
    //
    // Version 2: a producer makes one item, chosen when it is spawned, and starts with 1,000
    // credits and 256 of it, ceiling 256; it buys the other items and uses them up. A market
    // maker gets 1,500 credits and 32 of each. Sized against a welcome grant of a few
    // hundred credits — bots noticeable, not dwarfing a player. Version 1 gave a producer
    // ten times what a player could afford, and in the stress run the bots ended up holding
    // most of the market's money.

    public static final int LATEST_VERSION = 2;

    /** Whether this build knows a version's rules. A bot of a version it does not know is refused. */
    public static boolean knownVersion(int version) {
        return version >= 1 && version <= LATEST_VERSION;
    }

    /** Credits a bot is granted when it is spawned. */
    public static long startingCredits(String type, int version) {
        if (version >= 2) return PRODUCER.equals(type) ? 1_000 : 1_500;
        return PRODUCER.equals(type) ? 500 : 3_000;
    }

    /** How many of an item a bot is granted when it is spawned. product is a version 2 producer's item. */
    public static long startingItems(String type, int version, String itemId, String product) {
        if (!ITEMS.contains(itemId)) return 0;
        if (version >= 2) {
            if (PRODUCER.equals(type)) return itemId.equals(product) ? 256 : 0;
            return 32;
        }
        return PRODUCER.equals(type) ? 512 : 64;
    }

    /** Version 1's credits, for code that predates versions. */
    public static long startingCredits(String type) {
        return startingCredits(type, 1);
    }

    /** Version 1's items of each kind, for code that predates versions. */
    public static long startingItemsEach(String type) {
        return PRODUCER.equals(type) ? 512 : 64;
    }

    // ─────────── production ───────────

    /** A producer gains this many of each item ... */
    public static final long PRODUCTION_LOT = 16;
    /** ... every this many milliseconds, by the timestamps of its BotProduced events. */
    public static final long PRODUCTION_INTERVAL_MS = 10 * 60_000L;
    /** Version 1's ceiling: never past this many of an item, counting what it has on the book. */
    public static final long PRODUCTION_CEILING = 512;

    public static long productionCeiling(int version) {
        return version >= 2 ? 256 : PRODUCTION_CEILING;
    }

    /** Whether a producer makes an item: all of the basket in version 1 (no product), its one item after. */
    public static boolean produces(String type, String product, String itemId) {
        if (!PRODUCER.equals(type) || !ITEMS.contains(itemId)) return false;
        return product == null || product.equals(itemId);
    }

    /** Version 1's rule, for code that predates versions. */
    public static boolean produces(String type, String itemId) {
        return produces(type, null, itemId);
    }

    /**
     * The most a producer may produce of one item, given how long it has been since its last
     * production of it. Continuous at PRODUCTION_LOT per PRODUCTION_INTERVAL_MS, so an
     * interrupted scheduler that catches up later is not short-changed and one that runs
     * early gains nothing.
     */
    public static long productionAllowance(long elapsedMs) {
        if (elapsedMs <= 0) return 0;
        // elapsed * LOT / INTERVAL, computed so it cannot overflow for any realistic gap.
        long whole = elapsedMs / PRODUCTION_INTERVAL_MS;
        long part = (elapsedMs % PRODUCTION_INTERVAL_MS) * PRODUCTION_LOT / PRODUCTION_INTERVAL_MS;
        long lots;
        try {
            lots = Math.addExact(Math.multiplyExact(whole, PRODUCTION_LOT), part);
        } catch (ArithmeticException e) {
            lots = Long.MAX_VALUE;
        }
        return lots;
    }

    // ─────────── batches ───────────

    /** The most actions one BotActions event may carry. */
    public static final int MAX_ACTIONS_PER_BATCH = 8;

    public static final String ACTION_PLACE = "place";
    public static final String ACTION_CANCEL = "cancel";
    /**
     * A bot using up some of its own goods: they leave the market entirely. What lets a
     * version 2 producer buy the other items for a reason, and keeps produced goods from
     * piling up for ever. It can only ever destroy the bot's own items, so it needs no rule
     * beyond "at most what it holds".
     */
    public static final String ACTION_CONSUME = "consume";

    /**
     * Spacing of order ids within a batch.
     *
     * Every other order is numbered by its event's sequence number. A batch places several
     * orders from one event, so order i of the batch at sequence s is numbered
     * s + i * 2^40: the first is s itself, which no other order has, and the rest sit above
     * every sequence number a log will ever reach (2^40 is about a trillion events). So no
     * two orders anywhere share an id. Ids are only used to find an order to cancel; queue
     * position on the book is arrival order, so this changes nobody's priority.
     */
    public static final long BATCH_ORDER_STRIDE = 1L << 40;

    public static long batchOrderId(long seq, int position) {
        return seq + position * BATCH_ORDER_STRIDE;
    }

    /**
     * An order id as a screen shows it. A batch's later orders read "9.1", "9.2" — the batch's
     * sequence number and the order's place in it — rather than the thirteen-digit numbers
     * they are stored as, which overflowed the order book's rows. Every other id is unchanged.
     */
    public static String displayOrderId(long id) {
        if (id < BATCH_ORDER_STRIDE) return Long.toString(id);
        return (id % BATCH_ORDER_STRIDE) + "." + (id / BATCH_ORDER_STRIDE);
    }
}
