package io.github.badbull643.economiesmod.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.UUID;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Append-only, hash-chained event log stored as JSON Lines (one entry per line).
 *
 * The log is the source of truth. Market state is derived by replaying it.
 * Each entry carries the hash of the previous entry, so any tampering or fork
 * is detectable by walking the chain.
 */
public class EventLog {

    private static final String GENESIS_HASH = "0";

    private final Path file;
    private static final Gson gson = new Gson();

    private long lastSeq = 0;
    private String lastHash = GENESIS_HASH;

    public EventLog(Path file) throws IOException {
        this.file = file;
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        if (!Files.exists(file)) {
            Files.createFile(file);
        }
        // Catch up on where the existing log ends.
        List<SequencedEvent> existing = readFrom(0);
        if (!existing.isEmpty()) {
            SequencedEvent last = existing.get(existing.size() - 1);
            this.lastSeq = last.seq;
            this.lastHash = last.hash;
        }
        this.knownSize = Files.size(file);
    }

    /**
     * File size as of our last read or write. If it changes underneath us, someone
     * else is writing to this log.
     */
    private long knownSize;

    /**
     * Refuses to write if another EventLog instance has appended to the same file.
     *
     * Two instances on one file has bitten this project repeatedly: each holds its own
     * in-memory lastSeq/lastHash, so both happily write what looks locally like a valid
     * next entry and the result is duplicate sequence numbers and a broken chain. The
     * damage is silent and only shows up on the next verifyChain, by which point the
     * log is unusable. Cheap size check, caught at the moment of the write instead.
     */
    private void assertSoleWriter() throws IOException {
        long actual = Files.size(file);
        if (actual != knownSize) {
            throw new IOException("log file changed underneath us (expected " + knownSize
                    + " bytes, found " + actual + ") — another writer is active on "
                    + file.getFileName() + "; refusing to append");
        }
    }

    public long lastSeq() { return lastSeq; }
    public String lastHash() { return lastHash; }

    /**
     * Appends an event, assigning it the next sequence number and chaining its hash.
     *
     * The signature is stored, not just checked in flight — it is what makes the log
     * verifiable by anyone who did not witness it being written (import, migration,
     * an audit of someone else's replica). computeHash covers the signature field, so
     * the chain binds authorship as well as ordering.
     */
    public synchronized SequencedEvent append(Event event, String signature) throws IOException {
        SequencedEvent se = new SequencedEvent();
        se.seq = lastSeq + 1;
        se.prevHash = lastHash;
        se.eventType = event.getClass().getSimpleName();
        se.event = event;
        se.signature = signature;

        se.hash = computeHash(se);

        assertSoleWriter();
        String line = gson.toJson(se);
        try (BufferedWriter w = Files.newBufferedWriter(file,
                StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            w.write(line);
            w.newLine();
        }
        knownSize = Files.size(file);

        lastSeq = se.seq;
        lastHash = se.hash;
        return se;
    }

    /**
     * Position of the first line this build cannot read, or -1 if the whole file
     * parses. Set when a log contains an event type this version doesn't know —
     * typically a log written by an older or newer build.
     */
    // Volatile: assigned lazily inside readFrom, which runs on the sequencer thread and
    // on per-connection handshake threads, but is read by the game thread when the UI
    // asks whether the log is usable.
    private volatile long unreadableAt = -1;

    public boolean isUnreadable() { return unreadableAt != -1; }

    /**
     * Reads every entry with seq greater than or equal to fromSeq, in order.
     *
     * Stops at the first line it cannot parse rather than throwing. A log from another
     * version is a damaged log, not a crash: throwing here escaped through loadLocal
     * and took the whole world down at startup, which left no way to reach the Reset
     * button that would have fixed it.
     */
    public List<SequencedEvent> readFrom(long fromSeq) throws IOException {
        List<SequencedEvent> out = new ArrayList<>();
        if (!Files.exists(file)) return out;

        long position = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) continue;
            position++;

            SequencedEvent se;
            try {
                se = parseLine(line);
            } catch (Exception e) {
                // Everything past an unreadable line is unusable too — the chain can't
                // be carried across a gap — so stop rather than skip.
                if (unreadableAt == -1) {
                    unreadableAt = position;
                    System.err.println("[economiesmod] cannot read event " + position
                            + " in " + file.getFileName() + ": " + e.getMessage());
                }
                break;
            }

            if (se != null && se.seq >= fromSeq) {
                out.add(se);
            }
        }
        return out;
    }

    /**
     * Parses a line, dispatching to the right Event subclass based on eventType.
     * Gson can't infer the subclass on its own, so we read the type first.
     */
    public static SequencedEvent parseLine(String line) {
        JsonObject obj = new JsonParser().parse(line).getAsJsonObject();

        SequencedEvent se = new SequencedEvent();
        se.seq = obj.get("seq").getAsLong();
        se.prevHash = obj.has("prevHash") ? obj.get("prevHash").getAsString() : "0";
        se.hash = obj.get("hash").getAsString();
        se.eventType = obj.get("eventType").getAsString();
        se.signature = obj.has("signature") && !obj.get("signature").isJsonNull()
                ? obj.get("signature").getAsString() : null;

        JsonObject eventJson = obj.getAsJsonObject("event");
        se.event = gson.fromJson(eventJson, classFor(se.eventType));
        return se;
    }

    /**
     * The market this log belongs to, read from the genesis event, or null if the log
     * is empty or predates market identity.
     *
     * Deliberately cheap — the handshake needs this on every connection and must not
     * pay for a full replay to get it.
     */
    public UUID marketId() throws IOException {
        Event.MarketCreated genesis = genesis();
        return genesis != null ? genesis.marketId : null;
    }

    /** The market's human-readable name, or null if the log has no genesis event. */
    public String marketName() throws IOException {
        Event.MarketCreated genesis = genesis();
        return genesis != null ? genesis.marketName : null;
    }

    private Event.MarketCreated genesis() throws IOException {
        if (!Files.exists(file)) return null;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) continue;
            SequencedEvent se;
            try {
                se = parseLine(line);
            } catch (Exception e) {
                return null;   // unreadable first line — treat as no identity
            }
            return se.event instanceof Event.MarketCreated
                    ? (Event.MarketCreated) se.event : null;
        }
        return null;
    }

    /** Human-readable reason this log can't be used, or null if it's fine. */
    public String damageReason() throws IOException {
        if (unreadableAt != -1) {
            return "contains an event this version can't read (line " + unreadableAt
                    + ") — it was written by a different build";
        }
        long bad = verifyChain();
        return bad == -1 ? null : "chain is broken at event " + bad;
    }

    public static Class<? extends Event> classFor(String typeName) {
        switch (typeName) {
            case "MarketCreated": return Event.MarketCreated.class;
            case "KeyRegistered": return Event.KeyRegistered.class;
            case "WelcomeGrant":  return Event.WelcomeGrant.class;
            case "MigrateBalance": return Event.MigrateBalance.class;
            case "Deposit":       return Event.Deposit.class;
            case "Withdraw":      return Event.Withdraw.class;
            case "PlaceOrder":    return Event.PlaceOrder.class;
            case "CancelOrder":   return Event.CancelOrder.class;
            case "DepositAndList": return Event.DepositAndList.class;
            case "MarketPolicy":  return Event.MarketPolicy.class;
            default:
                throw new IllegalStateException("Unknown event type in log: " + typeName);
        }
    }

    /** Verifies the whole chain hashes correctly. Returns the seq of the first bad entry, or -1. */
    public long verifyChain() throws IOException {
        // An unreadable log is a damaged log — same remedy, same banner, same refusal
        // to host or connect on it.
        if (unreadableAt != -1) return unreadableAt;

        String expectedPrev = GENESIS_HASH;
        long expectedSeq = 1;

        for (SequencedEvent se : readFrom(0)) {
            if (se.seq != expectedSeq) return se.seq;
            if (!expectedPrev.equals(se.prevHash)) return se.seq;
            if (!computeHash(se).equals(se.hash)) return se.seq;
            expectedPrev = se.hash;
            expectedSeq++;
        }
        return -1;
    }

    /** Recomputes an entry's hash — used to check an entry we didn't write ourselves. */
    public static String recomputeHash(SequencedEvent se) {
        String payload = se.seq + "|" + se.prevHash + "|" + se.eventType
                + "|" + gson.toJson(se.event) + "|" + se.signature;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Hash covers everything except the hash field itself. */
    private String computeHash(SequencedEvent se) {
        String payload = se.seq + "|" + se.prevHash + "|" + se.eventType
                + "|" + gson.toJson(se.event) + "|" + se.signature;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The hash at a given seq, or null if it doesn't exist. "0" for genesis. */
    public String hashAt(long seq) throws IOException {
        if (seq == 0) return "0";
        for (SequencedEvent se : readFrom(seq)) {
            if (se.seq == seq) return se.hash;
            if (se.seq > seq) break;
        }
        return null;
    }

    /** Raw JSONL lines from seq onward — used to send the wire format unchanged. */
    public List<String> rawLinesFrom(long fromSeq) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) continue;
            long seq = new JsonParser().parse(line).getAsJsonObject().get("seq").getAsLong();
            if (seq >= fromSeq) out.add(line);
        }
        return out;
    }

    /** The raw line for one specific seq, or null. */
    public String rawLineFor(long seq) throws IOException {
        for (String line : rawLinesFrom(seq)) {
            long s = new JsonParser().parse(line).getAsJsonObject().get("seq").getAsLong();
            if (s == seq) return line;
        }
        return null;
    }

    /**
     * Appends a line received from a host verbatim. The host already assigned the
     * sequence number and computed the hash — recomputing would risk divergence.
     */
    public synchronized void appendRaw(String line) throws IOException {
        SequencedEvent se = parseLine(line);
        if (se.seq != lastSeq + 1) {
            throw new IOException("out of order: expected " + (lastSeq + 1) + " got " + se.seq);
        }
        if (!lastHash.equals(se.prevHash)) {
            throw new IOException("chain break at seq " + se.seq);
        }

        assertSoleWriter();
        try (BufferedWriter w = Files.newBufferedWriter(file,
                StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            w.write(line);
            w.newLine();
        }
        knownSize = Files.size(file);

        lastSeq = se.seq;
        lastHash = se.hash;
    }

}
