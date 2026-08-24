package io.github.badbull643.economiesmod.core;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The furthest this market has ever been seen to advance, from any source.
 *
 * Raft won't let a node become leader unless its log is at least as up to date as the
 * voters', which is what stops a node that missed events from overwriting committed
 * ones. There is no quorum to ask here, so this stands in for it: a note of how far
 * ahead the market was last time we saw anyone serving it.
 *
 * Checking live peers alone would not do, because discovery only finds hosts that are
 * running right now — and the case that actually costs you is the opposite one. Someone
 * returns after a week, nobody else happens to be online, discovery finds nothing, and
 * they host a log that is hundreds of events behind. Everyone else is then refused, and
 * if that host trades at all the market genuinely forks. The watermark is what lets them
 * be told they are behind when there is nobody around to tell them.
 */
public class MarketHighWater {

    private static final Gson gson = new Gson();

    /**
     * On-disk shape. Carries the market id so a reset or a different market resets it,
     * and who said so, so the claim can be withdrawn by whoever made it.
     */
    private static class Record {
        String marketId;
        long seq;
        /** The peer whose head this is. Null in files written before provenance. */
        String fromUserId;
    }

    private final Path file;
    private Record record = new Record();

    public MarketHighWater(Path file) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Record loaded = gson.fromJson(json, Record.class);
                // A record with no source cannot be reasoned about: the rule below is
                // "the peer who said it can unsay it", and there is nobody to ask. Older
                // files are all like this, and one of them is the reason provenance
                // exists — a mark left standing for a branch its own reporter had since
                // abandoned. Discarded rather than trusted; the next poll rebuilds it,
                // and the cost is the offline warning being unavailable until then.
                if (loaded != null && loaded.marketId != null && loaded.fromUserId == null) {
                    System.out.println("[economiesmod] discarding a high-water mark with"
                            + " no source (" + loaded.seq + ") — it will be relearned from"
                            + " the next host seen");
                    loaded = null;
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ignored) {
                        // Not worth failing over: the in-memory record is already empty.
                    }
                }
                if (loaded != null) record = loaded;
            }
        } catch (Exception e) {
            System.err.println("[economiesmod] could not read high-water mark: " + e);
        }
    }

    /**
     * Notes that {@code fromUserId} is on this market at {@code seq}, and is on our chain.
     *
     * <h2>Why the reporter is recorded, and why their own number can go down</h2>
     *
     * This was a bare maximum and could only ever rise. That is right for the case it was
     * built for — somebody returns after a week to a market that moved on without them —
     * and wrong for the one that turned up in play: a mark recorded honestly is
     * invalidated by <em>your own</em> later fork, and a number with no source cannot
     * notice.
     *
     * Measured. A host sat at 123 while a peer reported 129, and their chains agreed
     * through 123 — so the peer genuinely extended them and 129 was the true height of
     * this market. Four seconds later the host appended its own event 124 and left that
     * chain. The 129 now described a branch nobody was on, `eventsBehind` read 1, and
     * since it gates Host the participant was told that serving their own market would
     * split it.
     *
     * So a claim belongs to whoever made it. A peer's current head replaces their
     * previous one <b>in either direction</b>: they are not asserting a record, they are
     * telling you where they are, and when they come back lower — because they reset, or
     * because the branch they were on is gone — the evidence for the old number has gone
     * with it. A different peer can only raise the mark, because their being at 50 says
     * nothing about whether somebody else's 300 was real.
     *
     * Callers must only pass a peer they have confirmed is on their own chain. That is
     * the other half of this: a forked peer's head is not this market advancing, and the
     * discovery poll checks before it calls.
     */
    public void observe(UUID marketId, long seq, String fromUserId) {
        if (marketId == null) return;
        String id = marketId.toString();

        if (!id.equals(record.marketId)) {
            record = new Record();
            record.marketId = id;
            record.seq = seq;
            record.fromUserId = fromUserId;
            save();
            return;
        }

        boolean theirsToChange = fromUserId != null && fromUserId.equals(record.fromUserId);
        if (theirsToChange ? seq != record.seq : seq > record.seq) {
            record.seq = seq;
            record.fromUserId = fromUserId;
            save();
        }
    }

    /** Highest seq seen for this market, or 0 if we've never seen it advance. */
    public long seenFor(UUID marketId) {
        if (marketId == null || !marketId.toString().equals(record.marketId)) return 0;
        return record.seq;
    }

    /** Forgotten along with the market it describes. */
    public void clear() {
        record = new Record();
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println("[economiesmod] could not clear high-water mark: " + e);
        }
    }

    private void save() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.write(file, gson.toJson(record).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[economiesmod] could not save high-water mark: " + e);
        }
    }
}
