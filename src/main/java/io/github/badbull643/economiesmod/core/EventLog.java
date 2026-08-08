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
    }

    public long lastSeq() { return lastSeq; }
    public String lastHash() { return lastHash; }

    /** Appends an event, assigning it the next sequence number and chaining its hash. */
    public synchronized SequencedEvent append(Event event) throws IOException {
        SequencedEvent se = new SequencedEvent();
        se.seq = lastSeq + 1;
        se.prevHash = lastHash;
        se.eventType = event.getClass().getSimpleName();
        se.event = event;
        se.signature = null;   // stubbed until real crypto

        se.hash = computeHash(se);

        String line = gson.toJson(se);
        try (BufferedWriter w = Files.newBufferedWriter(file,
                StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            w.write(line);
            w.newLine();
        }

        lastSeq = se.seq;
        lastHash = se.hash;
        return se;
    }

    /** Reads every entry with seq greater than or equal to fromSeq, in order. */
    public List<SequencedEvent> readFrom(long fromSeq) throws IOException {
        List<SequencedEvent> out = new ArrayList<>();
        if (!Files.exists(file)) return out;

        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) continue;
            SequencedEvent se = parseLine(line);
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

    public static Class<? extends Event> classFor(String typeName) {
        switch (typeName) {
            case "Deposit":       return Event.Deposit.class;
            case "Withdraw":      return Event.Withdraw.class;
            case "PlaceOrder":    return Event.PlaceOrder.class;
            case "CancelOrder":   return Event.CancelOrder.class;
            case "InjectCredits": return Event.InjectCredits.class;
            default:
                throw new IllegalStateException("Unknown event type in log: " + typeName);
        }
    }

    /** Verifies the whole chain hashes correctly. Returns the seq of the first bad entry, or -1. */
    public long verifyChain() throws IOException {
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

}
