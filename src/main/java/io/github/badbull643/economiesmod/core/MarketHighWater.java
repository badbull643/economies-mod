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

    /** On-disk shape. Carries the market id so a reset or a different market resets it. */
    private static class Record {
        String marketId;
        long seq;
    }

    private final Path file;
    private Record record = new Record();

    public MarketHighWater(Path file) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Record loaded = gson.fromJson(json, Record.class);
                if (loaded != null) record = loaded;
            }
        } catch (Exception e) {
            System.err.println("[economiesmod] could not read high-water mark: " + e);
        }
    }

    /**
     * Notes that this market was seen at {@code seq}. Only ever moves forward, and
     * starts over if this is a different market than the one on record.
     */
    public void observe(UUID marketId, long seq) {
        if (marketId == null) return;
        String id = marketId.toString();

        if (!id.equals(record.marketId)) {
            record = new Record();
            record.marketId = id;
            record.seq = seq;
            save();
            return;
        }
        if (seq > record.seq) {
            record.seq = seq;
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
