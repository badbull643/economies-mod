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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** The prevHash of the first entry, and the hash of the empty prefix at seq 0. */
    public static final String GENESIS_HASH = "0";

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
        // Where the log ends is worked out on demand rather than here — see ensureHead.
        this.knownSize = Files.size(file);
    }

    /**
     * Whether lastSeq/lastHash have been worked out yet.
     *
     * They used to be found in the constructor, by walking the whole file. That is a
     * pass over every event to open a log at all, including one opened only to ask
     * whether a snapshot still describes it — and once the snapshot made the rest of a
     * load cheap, this was the entire remaining cost of opening a long-lived market.
     *
     * Deferred rather than dropped. Anything that needs the head still gets a walk; what
     * changed is that a caller who never asks no longer pays, and a caller who has just
     * walked the file itself can say so — see {@link #headIs}.
     */
    private volatile boolean headKnown;

    /**
     * Finds the head, for a caller that can handle being told the file is unreadable.
     *
     * Every write goes through this rather than {@link #ensureHead()}, because a write
     * is where a wrong head does damage — appending at a sequence number that already
     * exists is the duplicate-seq break this class spends most of its comments on — and
     * because append and appendRaw already declare IOException, so their callers are
     * written to cope.
     */
    private void ensureHeadChecked() throws IOException {
        if (headKnown) return;
        synchronized (this) {
            if (headKnown) return;
            forEach(0, se -> {
                this.lastSeq = se.seq;
                this.lastHash = se.hash;
                return true;
            });
            headKnown = true;
        }
    }

    /**
     * The same, for the readers — {@code lastSeq()} and {@code lastHash()}, which are
     * asked from render code and comparisons and cannot usefully declare a checked
     * exception.
     *
     * Failing here is loud rather than quiet, and that is the whole choice: the
     * alternative is answering "this log ends at zero" about a log that does not, which
     * is the one wrong answer nothing downstream survives.
     *
     * The window is narrow by construction. The constructor already stats the file, so a
     * log that cannot be read at all still fails there, as it always did; what is left
     * is the file being removed or locked between opening it and first asking where it
     * ends.
     */
    private void ensureHead() {
        try {
            ensureHeadChecked();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file.getFileName()
                    + " to find where it ends", e);
        }
    }

    /**
     * Told where the log ends by something that has just read to the end of it.
     *
     * Only for a replay that walked this file through to its last entry, which is the
     * one caller that already knows the answer and would otherwise make this object go
     * and find it again.
     *
     * This is the safe direction of the trap in {@link #lastSeq()}. That bug was a
     * cached head going stale against state built later; here the head and the state
     * come from the same walk and cannot disagree — which is what EventApplier.Replayed
     * exists to keep together.
     *
     * The other caller is a client that has deliberately thrown its position away — one
     * that holds a snapshot of a market it has just been told to archive, and is about
     * to ask for the history from the beginning. It sets this to zero for the same
     * reason: so the log object and the state agree about where this replica is.
     */
    public void headIs(long seq, String hash) {
        synchronized (this) {
            this.lastSeq = seq;
            this.lastHash = hash;
            this.headKnown = true;
        }
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

    /**
     * Where *this instance* believes the log ends.
     *
     * Cached, and it has to be: append needs the next sequence number without reading
     * the file, or writing an event would cost a pass over every event before it. That
     * is exactly right for whoever is doing the appending and quietly wrong for anybody
     * watching a log that somebody else is writing — the value was true when this object
     * was built and says nothing about what has landed since.
     *
     * That distinction has now cost three separate bugs: a client that believed it was
     * an event behind state it already held and applied a welcome grant to itself twice;
     * and two tests that asked their own handle about a file the host was appending to,
     * and passed by never changing. If you are watching somebody else's writes, you want
     * {@link #headSeqOnDisk()} and the name is the whole point of it existing.
     */
    public long lastSeq() { ensureHead(); return lastSeq; }

    /**
     * Where the file ends right now, read fresh.
     *
     * For anyone observing a log another writer owns. Costs a pass over the file, which
     * is why it is not what {@link #lastSeq()} does — but a cheap wrong answer is not a
     * saving.
     */
    public long headSeqOnDisk() throws IOException {
        final long[] head = { 0 };
        forEach(0, se -> {
            head[0] = se.seq;
            return true;
        });
        return head[0];
    }
    public String lastHash() { ensureHead(); return lastHash; }

    /** Where this log lives, so a snapshot can be kept beside it. */
    public Path file() { return file; }

    /**
     * The hash at a sequence number, without parsing everything before it.
     *
     * For the one question a snapshot asks on every load: is the chain I computed this
     * state from still the chain on disk? {@link #hashAt} answers it by parsing every
     * line up to that point, which is most of what a snapshot exists to avoid.
     *
     * A verified chain has one entry per non-empty line, numbered from 1, so entry N is
     * line N — the lines are counted and only the one wanted is parsed. That assumption
     * is checked rather than trusted: if the line found does not carry the sequence
     * number expected, this falls back to the honest walk, and a log that cannot answer
     * simply invalidates the snapshot.
     */
    public String hashAtSeqFast(long seq) throws IOException {
        if (seq == 0) return GENESIS_HASH;
        if (seq < 0) return null;
        final String[] found = { null };
        forEachAfter(seq - 1, se -> {
            if (se.seq == seq) found[0] = se.hash;
            return false;
        });
        return found[0];
    }

    /**
     * Walks entries after a sequence number, without reading what comes before it.
     *
     * The difference from {@code forEach(afterSeq + 1, ...)} is that this one does not
     * parse the entries it is skipping. It counts lines instead, which is what makes a
     * snapshot worth having: with one, everything below the snapshot point is bytes to
     * be stepped over rather than JSON to be rebuilt.
     *
     * A chain that verifies has one entry per non-empty line numbered from 1, so entry N
     * is line N. That is checked, not assumed — if the line landed on does not carry the
     * sequence number expected, this gives up on counting and walks the file properly.
     *
     * <b>The prefix is not examined at all</b> on the fast path, so a line below
     * afterSeq that this build cannot parse goes unnoticed here where {@code forEach}
     * would have stopped at it. That is the point rather than an oversight: the only
     * caller is a replay carrying on from a snapshot, and a snapshot is used only when
     * the chain hash it recorded is still the one on disk at that sequence number.
     */
    public void forEachAfter(long afterSeq, Visitor visitor) throws IOException {
        if (afterSeq <= 0) {
            forEach(afterSeq + 1, visitor);
            return;
        }
        if (!Files.exists(file)) return;

        try (java.io.BufferedReader reader =
                     Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            long line = 0;
            String text;
            boolean checked = false;
            while ((text = reader.readLine()) != null) {
                if (text.trim().isEmpty()) continue;
                if (++line <= afterSeq) continue;       // skipped without parsing

                SequencedEvent se;
                try {
                    se = parseLine(text);
                } catch (Exception e) {
                    if (unreadableAt == -1) {
                        unreadableAt = line;
                        System.err.println("[economiesmod] cannot read event " + line
                                + " in " + file.getFileName() + ": " + e.getMessage());
                    }
                    return;
                }

                if (!checked) {
                    checked = true;
                    if (se == null || se.seq != afterSeq + 1) {
                        // Line numbers and sequence numbers have parted company, so the
                        // counting was wrong. Start again and read every entry.
                        forEach(afterSeq + 1, visitor);
                        return;
                    }
                }
                if (se != null && !visitor.visit(se)) return;
            }
        }
    }

    /**
     * Appends an event, assigning it the next sequence number and chaining its hash.
     *
     * The signature is stored, not just checked in flight — it is what makes the log
     * verifiable by anyone who did not witness it being written (import, migration,
     * an audit of someone else's replica). recomputeHash covers the signature field, so
     * the chain binds authorship as well as ordering.
     */
    public synchronized SequencedEvent append(Event event, String signature) throws IOException {
        ensureHeadChecked();
        SequencedEvent se = new SequencedEvent();
        se.seq = lastSeq + 1;
        se.prevHash = lastHash;
        se.eventType = event.getClass().getSimpleName();
        se.event = event;
        se.signature = signature;

        se.hash = recomputeHash(se);

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

    /**
     * Whether this log contains a line this build cannot parse.
     *
     * Reads the file if nothing has read it yet, because the answer is otherwise "no,
     * as far as anyone has looked", and that is a different claim wearing the same
     * words. Callers who have just walked the log themselves want
     * {@link #unreadableAtSoFar()}, which asks what that walk found without starting
     * another one.
     */
    public boolean isUnreadable() { ensureHead(); return unreadableAt != -1; }

    /**
     * The bad line any read so far has hit, or -1.
     *
     * For a caller that has just finished walking this log and wants to know whether its
     * own walk stopped early. Never triggers a read, which is the whole difference from
     * {@link #isUnreadable()} — asking this from inside a load path is free, and asking
     * that one would start a second pass over the file.
     */
    long unreadableAtSoFar() { return unreadableAt; }

    /** One entry of a walk. Return false to stop early. */
    @FunctionalInterface
    public interface Visitor {
        boolean visit(SequencedEvent se);
    }

    /**
     * Walks entries from fromSeq onward, holding one at a time.
     *
     * This is the primitive; {@link #readFrom} is this plus a list, and everything that
     * only needs to *look* at each event should use this instead. The difference is not
     * a micro-optimisation: readFrom materialises the whole log into an ArrayList before
     * its caller sees a single event, measured at about a kilobyte of heap per event, so
     * a long-lived market's log became a several-hundred-megabyte allocation inside a
     * Minecraft client on every world load and every connect. Transient, but unlike a
     * slow load it fails hard.
     *
     * Stops at the first line it cannot parse rather than throwing. A log from another
     * version is a damaged log, not a crash: throwing here escaped through loadLocal
     * and took the whole world down at startup, which left no way to reach the Reset
     * button that would have fixed it.
     */
    public void forEach(long fromSeq, Visitor visitor) throws IOException {
        if (!Files.exists(file)) return;

        long position = 0;
        try (java.io.BufferedReader reader =
                     Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
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
                    return;
                }

                if (se != null && se.seq >= fromSeq && !visitor.visit(se)) {
                    return;
                }
            }
        }
    }

    /**
     * Reads every entry with seq greater than or equal to fromSeq, in order.
     *
     * Prefer {@link #forEach} unless you genuinely need every event at once — see there
     * for what this costs on a long log.
     */
    public List<SequencedEvent> readFrom(long fromSeq) throws IOException {
        final List<SequencedEvent> out = new ArrayList<>();
        forEach(fromSeq, se -> {
            out.add(se);
            return true;
        });
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
        // Only the first entry can be a genesis, so only the first entry is read. This
        // used to load every line in the file to look at line one.
        final Event.MarketCreated[] found = { null };
        forEach(0, se -> {
            if (se.event instanceof Event.MarketCreated) {
                found[0] = (Event.MarketCreated) se.event;
            }
            return false;
        });
        return found[0];
    }

    /** Human-readable reason this log can't be used, or null if it's fine. */
    public String damageReason() throws IOException {
        return damageReasonFor(verifyChain());
    }

    /**
     * The same, for a caller that has already established where the chain breaks.
     *
     * The load path verified the chain and then asked why the log was damaged, and
     * asking re-derived whether it was — a second full pass over the file to answer a
     * question the caller was holding the answer to.
     */
    public String damageReasonFor(long chainBrokenAt) {
        if (unreadableAt != -1) {
            return "contains an event this version can't read (line " + unreadableAt
                    + ") — it was written by a different build";
        }
        return chainBrokenAt == -1 ? null : "chain is broken at event " + chainBrokenAt;
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
            case "Stipend":       return Event.Stipend.class;
            case "HostDefaults":  return Event.HostDefaults.class;
            default:
                throw new IllegalStateException("Unknown event type in log: " + typeName);
        }
    }

    /** Verifies the whole chain hashes correctly. Returns the seq of the first bad entry, or -1. */
    public long verifyChain() throws IOException {
        // An unreadable log is a damaged log — same remedy, same banner, same refusal
        // to host or connect on it.
        if (unreadableAt != -1) return unreadableAt;

        final String[] expectedPrev = { GENESIS_HASH };
        final long[] expectedSeq = { 1 };
        final long[] bad = { -1 };

        forEach(0, se -> {
            if (se.seq != expectedSeq[0]
                    || !expectedPrev[0].equals(se.prevHash)
                    || !recomputeHash(se).equals(se.hash)) {
                bad[0] = se.seq;
                return false;
            }
            expectedPrev[0] = se.hash;
            expectedSeq[0]++;
            return true;
        });
        // A walk that stopped at a line it could not parse found damage without finding
        // a bad hash. The check at the top of this method only sees that when some
        // earlier read already hit it, which stopped being guaranteed when finding the
        // head became lazy — so the same question is asked again on the way out.
        if (bad[0] == -1 && unreadableAt != -1) return unreadableAt;
        return bad[0];
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * One digest per thread, because this is called from the sequencer, from
     * per-connection handshake threads and from a replay on the game thread, and
     * MessageDigest is not thread-safe. Reused rather than allocated per event: at
     * 50,000 events getInstance alone was 6.6 ms of pure ceremony.
     */
    private static final ThreadLocal<MessageDigest> DIGEST = new ThreadLocal<MessageDigest>() {
        @Override protected MessageDigest initialValue() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }
    };

    /**
     * An entry's hash, covering everything except the hash field itself — so the chain
     * binds authorship as well as ordering.
     *
     * There were two of these until 2026-08-23, a public static one and a private
     * instance one, with byte-identical bodies. That is the recurring defect of this
     * project in the worst possible place: the hash function has to agree with itself
     * or a log stops verifying against its own writer, and nothing made the two copies
     * agree except that whoever wrote the second had just read the first.
     *
     * The hex loop was 45% of the cost of loading a market — String.format("%02x")
     * sixty-four times per event, parsing a format string each time. The table below
     * produces the same characters, checked byte-for-byte against the old spelling on
     * real logs before the change was trusted. No existing log is invalidated by this.
     */
    public static String recomputeHash(SequencedEvent se) {
        String payload = se.seq + "|" + se.prevHash + "|" + se.eventType
                + "|" + gson.toJson(se.event) + "|" + se.signature;
        MessageDigest md = DIGEST.get();
        md.reset();
        byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
        char[] out = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            out[i * 2]     = HEX[(digest[i] >> 4) & 0xf];
            out[i * 2 + 1] = HEX[digest[i] & 0xf];
        }
        return new String(out);
    }

    /** The hash at a given seq, or null if it doesn't exist. "0" for genesis. */
    public String hashAt(long seq) throws IOException {
        if (seq == 0) return GENESIS_HASH;
        final String[] found = { null };
        // The first entry at or past seq settles it either way, so stop there rather
        // than reading the rest of the log to find out nothing.
        forEach(seq, se -> {
            if (se.seq == seq) found[0] = se.hash;
            return false;
        });
        return found[0];
    }

    /**
     * The hashes at several sequence numbers, from one pass over the file.
     *
     * hashAt re-reads and re-parses the whole log every call, which is fine for the one
     * lookup it was written for and quadratic for a search. Locating where two chains
     * diverge asks for a batch of points at a time precisely so it can be one read
     * rather than one read per probe — see the split-point search, which would otherwise
     * be O(n log n) disk on the path that already annoys people with long logs.
     *
     * Missing sequence numbers are absent from the result rather than mapped to null, so
     * "we have no such event" and "we have one and it hashes to nothing" stay different
     * answers. Seq 0 is the empty prefix and always hashes to "0", which is what makes
     * it the floor a search can start from without asking anybody.
     */
    public Map<Long, String> hashesAt(Collection<Long> seqs) throws IOException {
        Map<Long, String> out = new HashMap<>();
        if (seqs == null || seqs.isEmpty()) return out;

        final Set<Long> wanted = new HashSet<>(seqs);
        if (wanted.remove(0L)) out.put(0L, "0");
        if (wanted.isEmpty()) return out;

        forEach(0, se -> {
            if (wanted.remove(se.seq)) {
                out.put(se.seq, se.hash);
                return !wanted.isEmpty();
            }
            return true;
        });
        return out;
    }

    /** Raw JSONL lines from seq onward — used to send the wire format unchanged. */
    /**
     * One raw line of a walk. Return false to stop early.
     *
     * Allowed to fail, unlike {@link Visitor}: the caller this exists for is writing
     * each line to a socket as it arrives, and a broken connection partway through a
     * history is an ordinary thing rather than a bug to be swallowed.
     */
    @FunctionalInterface
    public interface RawVisitor {
        boolean visit(String line) throws IOException;
    }

    /**
     * Hands over raw lines from fromSeq onward, one at a time.
     *
     * For sending a history rather than replaying one — the wire format is the file's
     * own lines, so nothing here parses an event it is only going to pass along.
     *
     * The reason this exists rather than {@link #rawLinesFrom} is the same reason
     * {@link #forEach} exists: a host serving a joiner built the entire market in memory
     * before sending a byte of it. Measured at roughly the size of the log again, so a
     * large market meant the better part of a gigabyte allocated per person arriving,
     * and several seconds before anything went out. Reading was always line by line;
     * only the piling-up was the problem.
     */
    public void forEachRawLine(long fromSeq, RawVisitor visitor) throws IOException {
        if (!Files.exists(file)) return;
        try (java.io.BufferedReader reader =
                     Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                long seq = new JsonParser().parse(line).getAsJsonObject().get("seq").getAsLong();
                if (seq >= fromSeq && !visitor.visit(line)) return;
            }
        }
    }

    public List<String> rawLinesFrom(long fromSeq) throws IOException {
        List<String> out = new ArrayList<>();
        forEachRawLine(fromSeq, line -> {
            out.add(line);
            return true;
        });
        return out;
    }

    /**
     * The raw line for one specific seq, or null.
     *
     * Reads to that line and stops. It used to collect every line from seq to the end
     * of the log and then look through them for the one it already knew the number of.
     */
    public String rawLineFor(long seq) throws IOException {
        try (java.io.BufferedReader reader =
                     Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                long s = new JsonParser().parse(line).getAsJsonObject().get("seq").getAsLong();
                if (s == seq) return line;
                if (s > seq) return null;
            }
        }
        return null;
    }

    /**
     * Appends a line received from a host verbatim. The host already assigned the
     * sequence number and computed the hash — recomputing would risk divergence.
     */
    public synchronized void appendRaw(String line) throws IOException {
        ensureHeadChecked();
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
