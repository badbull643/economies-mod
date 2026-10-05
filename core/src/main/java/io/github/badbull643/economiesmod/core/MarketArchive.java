package io.github.badbull643.economiesmod.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Moving a market's history between players out-of-band.
 *
 * This is the piece that actually solves bootstrapping. Without it, joining an
 * existing market requires someone who holds it to be online at the same moment as
 * you — and any group that fails to arrange that will, with nobody making a mistake,
 * start a second market that can never be merged with the first. With it, a log can
 * be handed over on Discord or a USB stick and adopted whenever.
 *
 * The asymmetry that matters: a log you built yourself, by syncing from a host that
 * validated every event as it arrived, is trusted. An imported log is a file from
 * someone else and is trusted for nothing. The hash chain alone does not help here —
 * anyone can author a fully self-consistent chain of invented events. Only the
 * signatures distinguish a real history from a fabricated one, so import verifies
 * every single one.
 */
public class MarketArchive {

    /** Thrown when an imported log fails verification, naming where it failed. */
    public static class InvalidArchive extends IOException {
        public InvalidArchive(String message) { super(message); }
    }

    // ─────────── export ───────────

    /**
     * Writes a copy of the log to {@code dest}.
     *
     * A plain copy is enough because the log is self-authenticating: identities are
     * bound to keys by MarketCreated and KeyRegistered events inside the chain, so
     * nothing has to travel alongside it and be taken on faith.
     */
    public static void export(Path logFile, Path dest) throws IOException {
        if (!Files.exists(logFile)) {
            throw new IOException("no log to export");
        }
        EventLog log = new EventLog(logFile);
        if (log.lastSeq() == 0) {
            throw new IOException("nothing to export — this world holds no market");
        }
        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }
        Files.copy(logFile, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    // ─────────── import ───────────

    public static class Summary {
        public final UUID marketId;
        public final String marketName;
        public final long events;
        public final int participants;

        Summary(UUID marketId, String marketName, long events, int participants) {
            this.marketId = marketId;
            this.marketName = marketName;
            this.events = events;
            this.participants = participants;
        }

        @Override
        public String toString() {
            return "'" + marketName + "' — " + events + " events, "
                    + participants + " participants";
        }
    }

    /**
     * Verifies an archive and, if it is sound, installs it as the local log.
     *
     * Refuses to overwrite existing history: adopting a market is for someone who
     * holds none, and silently discarding a log is not import's decision to make.
     */
    public static Summary importInto(Path archive, Path logFile) throws IOException {
        if (!Files.exists(archive)) {
            throw new InvalidArchive("no such file: " + archive.getFileName());
        }

        EventLog existing = new EventLog(logFile);
        if (existing.lastSeq() != 0) {
            throw new InvalidArchive("this world already holds a market — reset it first");
        }
        // lastSeq() alone is not enough: a log this build can't parse stops being read
        // at its first bad line and reports 0, which would let the copy below destroy
        // history that a matching build could still read.
        if (existing.isUnreadable()) {
            throw new InvalidArchive("this world's log can't be read by this build"
                    + " — Reset log first if you're sure you want to discard it");
        }

        Summary summary = verify(archive);

        if (logFile.getParent() != null) {
            Files.createDirectories(logFile.getParent());
        }
        Files.copy(archive, logFile, StandardCopyOption.REPLACE_EXISTING);
        return summary;
    }

    /** A verified history, plus the state it replays to. */
    public static class Verified {
        public final MarketState state;
        public final Summary summary;
        public final long headSeq;
        public final String headHash;

        Verified(MarketState state, Summary summary, long headSeq, String headHash) {
            this.state = state;
            this.summary = summary;
            this.headSeq = headSeq;
            this.headHash = headHash;
        }
    }

    /**
     * Checks an archive without installing it: chain integrity, genesis, and every
     * event's signature against the key registered for its author at that point.
     */
    public static Summary verify(Path archive) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(archive, StandardCharsets.UTF_8)) {
            if (!line.trim().isEmpty()) lines.add(line);
        }
        return verifyLines(lines).summary;
    }

    /**
     * The same checks against lines held in memory — used when a history arrives over
     * the network rather than as a file. A migrating client's branch is exactly as
     * untrusted as a file from a stranger, so it goes through the identical path.
     */
    public static Verified verifyLines(List<String> rawLines) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : rawLines) {
            if (line != null && !line.trim().isEmpty()) lines.add(line);
        }
        if (lines.isEmpty()) {
            throw new InvalidArchive("archive is empty");
        }

        // Replay into a throwaway state. This gives us the key directory as it stood
        // at each event, which is what a signature has to be checked against — a key
        // registered later must not validate an earlier event.
        MarketState state = new MarketState();
        String expectedPrev = "0";
        long expectedSeq = 1;

        for (String line : lines) {
            SequencedEvent se;
            try {
                se = EventLog.parseLine(line);
            } catch (Exception e) {
                throw new InvalidArchive("unreadable event at position " + expectedSeq
                        + " — the file may be from a newer version: " + e.getMessage());
            }

            if (se.seq != expectedSeq) {
                throw new InvalidArchive("expected event " + expectedSeq
                        + " but found " + se.seq);
            }
            if (!expectedPrev.equals(se.prevHash)) {
                throw new InvalidArchive("broken chain at event " + se.seq);
            }
            if (!EventLog.recomputeHash(se).equals(se.hash)) {
                throw new InvalidArchive("altered content at event " + se.seq);
            }

            // Same check the host applies to a live proposal, and the same one a client
            // applies to a broadcast — an archive is no more and no less trusted.
            String problem = EventVerifier.verify(state, se.event, se.signature);
            if (problem != null) {
                throw new InvalidArchive("event " + se.seq + ": " + problem
                        + " — this history has been tampered with");
            }

            // Validate, then apply — both, and in that order, exactly as the host does
            // when it sequences a live proposal. This used to call apply alone, and
            // apply enforces none of the money rules: they live in validate, because
            // that is where the host asks them. So a hand-built history could hold a
            // welcome grant for any sum, repeated, or a stipend in a market that pays
            // none, and every one of them applied. The balance that came out of it is
            // what a migration then carries in, and migrationObjection weighs items
            // against the depositor's statistics but never credits — nothing else was
            // ever going to catch it.
            //
            // An honest log passes: every event in one was validated against this same
            // state by whoever sequenced it, and validate is a pure function of the
            // history before the event.
            EventApplier.Result check = EventApplier.validate(state, se);
            if (!check.accepted) {
                throw new InvalidArchive("event " + se.seq + " breaks this market's own"
                        + " rules: " + check.reason);
            }

            EventApplier.Result r = EventApplier.apply(state, se);
            if (!r.accepted) {
                throw new InvalidArchive("event " + se.seq + " is not valid against the"
                        + " history before it: " + r.reason);
            }

            expectedPrev = se.hash;
            expectedSeq++;
        }

        if (state.marketId() == null) {
            throw new InvalidArchive("this log belongs to no market");
        }

        Summary summary = new Summary(state.marketId(), state.marketName(),
                expectedSeq - 1, state.registeredCount());
        return new Verified(state, summary, expectedSeq - 1, expectedPrev);
    }
}
