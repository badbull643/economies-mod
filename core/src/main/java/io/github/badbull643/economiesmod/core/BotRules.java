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

    /** Credits a bot of this type is granted when it is spawned. */
    public static long startingCredits(String type) {
        return PRODUCER.equals(type) ? 500 : 3_000;
    }

    /** How many of each basket item a bot of this type is granted when it is spawned. */
    public static long startingItemsEach(String type) {
        return PRODUCER.equals(type) ? 512 : 64;
    }

    // ─────────── production ───────────

    /** A producer gains this many of each item ... */
    public static final long PRODUCTION_LOT = 16;
    /** ... every this many milliseconds, by the timestamps of its BotProduced events. */
    public static final long PRODUCTION_INTERVAL_MS = 10 * 60_000L;
    /** And never past this many of an item, counting what it has on the book. */
    public static final long PRODUCTION_CEILING = 512;

    public static boolean produces(String type, String itemId) {
        return PRODUCER.equals(type) && ITEMS.contains(itemId);
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
}
