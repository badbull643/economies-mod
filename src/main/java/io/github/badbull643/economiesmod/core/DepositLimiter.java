package io.github.badbull643.economiesmod.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How much each identity has deposited lately, so a host can refuse the implausible.
 *
 * This is the one anti-cheat layer a modified client cannot defeat, because it asks
 * the client for nothing. Every other layer available here — attestation, provenance —
 * is a claim the client makes about itself and can therefore lie about. This is the
 * host counting what it was actually handed. Somebody depositing ten thousand diamonds
 * in an hour did not mine them, and no amount of client cooperation is needed to notice.
 *
 * What it is not: proof of cheating. A prolific player on a long session can look like
 * a modest one on a fast machine, and the ceiling that catches a creative-mode dump is
 * far above anything survival play reaches. It buys a bound on how fast fabricated
 * goods can enter a market, which is a different and smaller claim than stopping them.
 *
 * <h2>Why this cannot live in EventApplier</h2>
 *
 * Every other rule about what may enter the log is enforced there, so that every
 * replica reaches the same verdict. This one must not be. Its answer depends on when
 * the question is asked — a replica replaying the log a week later would evaluate a
 * window that has long since passed and reject events the market legitimately contains.
 * That is a fork. So the cap is host policy, checked once by whoever sequences, exactly
 * like admission: refusing a deposit is not a market fact, it is a host declining to
 * write one.
 *
 * <h2>Why the host's clock, not the event's</h2>
 *
 * Events carry a client-supplied timestamp, signed but not verifiable. A client that
 * wanted more headroom would simply backdate its deposits out of the window. Every
 * measurement here uses the clock of the machine doing the counting.
 *
 * <h2>What it forgets</h2>
 *
 * The window lives in memory and starts empty. Restarting a host clears it, so an
 * operator who bounces their server hands everyone a fresh allowance. Seeding it from
 * the log was considered and rejected: the only timing information there is the
 * client's own timestamp, which is precisely the number this refuses to trust, so
 * seeding would reintroduce the evasion it was built to close. A limit that is honest
 * about resetting beats one that can be walked through by lying about the past.
 */
public class DepositLimiter {

    /** One deposit, as the host saw it. */
    private static final class Entry {
        final long atMillis;
        final long units;

        Entry(long atMillis, long units) {
            this.atMillis = atMillis;
            this.units = units;
        }
    }

    private final Map<UUID, Deque<Entry>> recent = new HashMap<>();

    private final long maxUnits;
    private final long windowMillis;

    /**
     * @param maxUnits     items per window per identity; zero or less disables the cap
     * @param windowMillis how far back to count
     */
    public DepositLimiter(long maxUnits, long windowMillis) {
        this.maxUnits = maxUnits;
        this.windowMillis = windowMillis;
    }

    /** Whether a ceiling is being enforced. */
    public boolean enabled() { return maxUnits > 0 && windowMillis > 0; }

    /**
     * Whether deposits are being counted at all, which is not the same question.
     *
     * The attestation check needs a running total whether or not a ceiling is set — it
     * compares what an identity has handed over against the play time it claims, and
     * with counting tied to the cap it would have seen each deposit alone and never a
     * sum. Somebody could then hand over a hundred units as often as they liked.
     */
    public boolean tracking() { return windowMillis > 0; }

    public long maxUnits() { return maxUnits; }

    /**
     * Whether this deposit fits under the identity's remaining allowance.
     *
     * Asks without recording, so a deposit rejected for some other reason does not
     * consume anything. Call {@link #record} only once it is actually going into the
     * log.
     */
    public synchronized boolean allows(UUID userId, long units, long nowMillis) {
        if (!enabled() || userId == null) return true;
        if (units <= 0) return true;
        return usedBy(userId, nowMillis) + units <= maxUnits;
    }

    /** Notes a deposit that was actually written. */
    public synchronized void record(UUID userId, long units, long nowMillis) {
        if (!tracking() || userId == null || units <= 0) return;
        recent.computeIfAbsent(userId, k -> new ArrayDeque<>())
                .addLast(new Entry(nowMillis, units));
    }

    /** Units this identity has deposited inside the window. */
    public synchronized long usedBy(UUID userId, long nowMillis) {
        if (!tracking() || userId == null) return 0;

        Deque<Entry> entries = recent.get(userId);
        if (entries == null) return 0;

        // Dropped on read rather than on a timer: there is no thread here, and an
        // identity that stopped depositing should stop costing memory the next time
        // anyone asks about it.
        long cutoff = nowMillis - windowMillis;
        while (!entries.isEmpty() && entries.peekFirst().atMillis <= cutoff) {
            entries.removeFirst();
        }
        if (entries.isEmpty()) {
            recent.remove(userId);
            return 0;
        }

        long total = 0;
        for (Entry e : entries) total += e.units;
        return total;
    }

    /** What this identity may still deposit right now. */
    public synchronized long remainingFor(UUID userId, long nowMillis) {
        if (!enabled()) return Long.MAX_VALUE;
        return Math.max(0, maxUnits - usedBy(userId, nowMillis));
    }
}
