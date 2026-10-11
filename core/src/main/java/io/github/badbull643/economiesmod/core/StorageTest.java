package io.github.badbull643.economiesmod.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.badbull643.economiesmod.core.net.LineCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * How the log is stored and sent: compact lines on disk, sealed history on a dedicated server,
 * full lines on the wire, packed into gzip.
 *
 * Everything here is about one promise: the stored form may change, the chain may not. Every
 * check that a log "still verifies" compares against hashes computed before any of this
 * existed, so a mistake in compaction shows up as a broken chain, not as a test adjusted to
 * match it.
 */
public class StorageTest {

    private static int failures = 0;
    private static int checksRun = 0;

    private static void check(String label, boolean ok) {
        checksRun++;
        if (!ok) failures++;
        System.out.println((ok ? "    ok   " : "    FAIL ") + label);
    }

    private static final UUID CREATOR = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static Path dir;
    private static int n = 0;

    public static void main(String[] args) throws Exception {
        dir = Paths.get("build", "test-scratch");
        Files.createDirectories(dir);

        System.out.println("  [S1: lines are stored compact]");
        {
            Built b = build(20);
            List<String> stored = storedLines(b.path);
            check("every line was written", stored.size() == b.log.lastSeq());
            boolean noPrev = true, genesisKeepsId = false, othersDropId = true;
            for (int i = 0; i < stored.size(); i++) {
                JsonObject o = new JsonParser().parse(stored.get(i)).getAsJsonObject();
                if (o.has("prevHash")) noPrev = false;
                boolean hasId = o.getAsJsonObject("event").has("marketId");
                if (i == 0) genesisKeepsId = hasId;
                else if (hasId) othersDropId = false;
            }
            check("no stored line carries prevHash", noPrev);
            check("the genesis line keeps its marketId", genesisKeepsId);
            check("no later line repeats it", othersDropId);

            long storedBytes = bytes(stored);
            long fullBytes = bytes(b.log.rawLinesFrom(1));
            System.out.println("         " + stored.size() + " events: " + fullBytes
                    + " bytes full, " + storedBytes + " stored ("
                    + (100 - storedBytes * 100 / fullBytes) + "% smaller)");
            check("stored is smaller than full", storedBytes < fullBytes);
        }

        System.out.println("  [S2: and read back to exactly the chain that was written]");
        {
            Built b = build(20);
            check("the chain verifies", b.log.verifyChain() == -1);
            final List<String> recomputed = new ArrayList<>();
            final boolean[] allMatch = { true };
            b.log.forEach(1, se -> {
                if (!EventLog.recomputeHash(se).equals(se.hash)) allMatch[0] = false;
                recomputed.add(se.hash);
                return true;
            });
            check("every entry hashes to the hash written for it", allMatch[0]);
            check("the head hash is the one the writer kept",
                    recomputed.get(recomputed.size() - 1).equals(b.log.lastHash()));
            MarketState fromLog = EventApplier.replay(new EventLog(b.path));
            check("it replays", fromLog.marketId() != null
                    && fromLog.itemBalances().getBalance(CREATOR, "minecraft:iron_ingot") == 20 * 3);
        }

        System.out.println("  [S3: the wire and exports get full lines]");
        {
            Built b = build(12);
            List<String> wire = b.log.rawLinesFrom(1);
            boolean allFull = true, allParse = true;
            UUID market = b.log.marketId();
            for (String line : wire) {
                JsonObject o = new JsonParser().parse(line).getAsJsonObject();
                if (!o.has("prevHash") || !o.getAsJsonObject("event").has("marketId")) allFull = false;
                SequencedEvent se = EventLog.parseLine(line);   // the context-free wire parser
                if (!EventLog.recomputeHash(se).equals(se.hash)) allParse = false;
                if (!market.equals(se.event.marketId)) allParse = false;
            }
            check("every wire line carries prevHash and marketId", allFull);
            check("and verifies with no context at all", allParse);
            check("one line on its own is full too",
                    new JsonParser().parse(b.log.rawLineFor(5)).getAsJsonObject().has("prevHash"));

            Path export = dir.resolve("storage-export-" + (++n) + ".jsonl");
            Files.deleteIfExists(export);
            MarketArchive.export(b.path, export);
            boolean verified;
            try { MarketArchive.verify(export); verified = true; } catch (Exception e) { verified = false; }
            check("an export verifies as an archive", verified);
            boolean exportFull = true;
            for (String line : Files.readAllLines(export, StandardCharsets.UTF_8)) {
                if (!line.trim().isEmpty() && !line.contains("\"prevHash\"")) exportFull = false;
            }
            check("and is written in full form", exportFull);

            boolean rawCopyVerifies;
            try { MarketArchive.verify(b.path); rawCopyVerifies = true; } catch (Exception e) { rawCopyVerifies = false; }
            check("a stored file copied as-is still verifies as an archive", rawCopyVerifies);
        }

        System.out.println("  [S4: logs written before this read and grow as they did]");
        {
            // A log in the old format: every line full. New appends go on compact.
            Built b = build(6);
            Path old = dir.resolve("storage-old-" + (++n) + ".jsonl");
            Files.write(old, b.log.rawLinesFrom(1), StandardCharsets.UTF_8);
            EventLog legacy = new EventLog(old);
            check("an all-full log verifies", legacy.verifyChain() == -1);
            appendDeposit(legacy, b.keys, 3);
            appendDeposit(legacy, b.keys, 3);
            check("appending to it keeps the chain", new EventLog(old).verifyChain() == -1);
            List<String> mixed = storedLines(old);
            check("its old lines stay full and its new ones are compact",
                    mixed.get(0).contains("\"prevHash\"")
                            && !mixed.get(mixed.size() - 1).contains("\"prevHash\""));
        }

        System.out.println("  [S5: tampering with a compact line is still caught]");
        {
            Built b = build(10);
            List<String> lines = storedLines(b.path);
            List<String> edited = new ArrayList<>(lines);
            edited.set(5, lines.get(5).replace("\"quantity\":3", "\"quantity\":300"));
            Path forged = dir.resolve("storage-forged-" + (++n) + ".jsonl");
            Files.write(forged, edited, StandardCharsets.UTF_8);
            check("an edited quantity breaks the chain", new EventLog(forged).verifyChain() == 6);

            List<String> missing = new ArrayList<>(lines);
            missing.remove(4);
            Path gap = dir.resolve("storage-gap-" + (++n) + ".jsonl");
            Files.write(gap, missing, StandardCharsets.UTF_8);
            check("a deleted line breaks the chain", new EventLog(gap).verifyChain() != -1);
        }

        System.out.println("  [S6: a line that would not survive compaction is stored full]");
        {
            // An event carrying no marketId in a market that has one — as events written
            // before market identity do. Left out, the reader would fill one in and the hash
            // would change, so the writer keeps it whole.
            Built b = build(2);
            Event.Deposit legacy = new Event.Deposit();
            legacy.userId = CREATOR;
            legacy.itemId = "minecraft:iron_ingot";
            legacy.quantity = 1;
            legacy.timestamp = 1L;
            legacy.marketId = null;
            b.log.append(legacy, b.keys.sign(EventCanonical.canonicalPayload(legacy)));
            List<String> lines = storedLines(b.path);
            check("it is written full", lines.get(lines.size() - 1).contains("\"prevHash\""));
            check("and the chain still verifies", new EventLog(b.path).verifyChain() == -1);
        }

        System.out.println("  [S7: a dedicated server seals old history]");
        {
            Built b = build(0);
            b.log.enableHistorySealing(10, 3);
            for (int i = 0; i < 30; i++) appendDeposit(b.log, b.keys, 1);
            Path hist = EventLog.historyPathFor(b.path);
            check("a history file was written", Files.exists(hist));
            long live = storedLines(b.path).size();
            check("the live file stays small (" + live + " lines)", live < 10);
            EventLog fresh = new EventLog(b.path);
            check("the whole chain still verifies", fresh.verifyChain() == -1);
            check("and ends where the writer says", fresh.lastSeq() == b.log.lastSeq());
            final long[] count = { 0 }, expect = { 1 };
            final boolean[] inOrder = { true };
            fresh.forEach(1, se -> {
                if (se.seq != expect[0]++) inOrder[0] = false;
                count[0]++;
                return true;
            });
            check("every event is read, in order, across both files",
                    inOrder[0] && count[0] == b.log.lastSeq());
            check("the wire still gets all of it", b.log.rawLinesFrom(1).size() == b.log.lastSeq());
            long mid = b.log.lastSeq() / 2;
            check("a hash in the sealed part is found",
                    b.log.hashAtSeqFast(mid) != null && b.log.hashAtSeqFast(mid).equals(b.log.hashAt(mid)));
            final long[] after = { 0 };
            fresh.forEachAfter(mid, se -> { after[0]++; return true; });
            check("a walk starting inside the sealed part finds the rest",
                    after[0] == b.log.lastSeq() - mid);
            Path export = dir.resolve("storage-sealed-export-" + (++n) + ".jsonl");
            Files.deleteIfExists(export);
            MarketArchive.export(b.path, export);
            boolean ok;
            try { ok = MarketArchive.verify(export).events == b.log.lastSeq(); } catch (Exception e) { ok = false; }
            check("an export carries the sealed history too", ok);
            check("filesFor names both files",
                    EventLog.filesFor(b.path).contains(hist) && EventLog.filesFor(b.path).contains(b.path));
        }

        System.out.println("  [S8: an interrupted seal loses nothing and repeats nothing]");
        {
            Built b = build(0);
            b.log.enableHistorySealing(10, 3);
            for (int i = 0; i < 12; i++) appendDeposit(b.log, b.keys, 1);
            Path hist = EventLog.historyPathFor(b.path);
            // Put the last few sealed lines back at the front of the live file, as if the
            // history had been rewritten and the live file had not.
            List<String> sealed = gunzipLines(hist);
            List<String> live = storedLines(b.path);
            List<String> interrupted = new ArrayList<>(sealed.subList(sealed.size() - 4, sealed.size()));
            interrupted.addAll(live);
            Files.write(b.path, interrupted, StandardCharsets.UTF_8);

            EventLog after = new EventLog(b.path);
            check("the doubled lines are read once", after.verifyChain() == -1
                    && after.rawLinesFrom(1).size() == b.log.lastSeq());
            after.enableHistorySealing(10, 3);
            for (int i = 0; i < 12; i++) appendDeposit(after, b.keys, 1);
            // The four doubled lines were already in the history; resealing must not add them
            // to it a second time.
            boolean unique = true;
            List<String> hl = gunzipLines(hist);
            java.util.Set<Long> sealedSeqs = new java.util.HashSet<>();
            for (String line : hl) {
                if (!sealedSeqs.add(new JsonParser().parse(line).getAsJsonObject().get("seq").getAsLong())) unique = false;
            }
            check("the next seal does not seal anything twice", unique);
            check("and the chain is whole", new EventLog(b.path).verifyChain() == -1);
        }

        System.out.println("  [S9: packing lines for the wire]");
        {
            Built b = build(30);
            List<String> wire = b.log.rawLinesFrom(1);
            String packed = LineCodec.pack(wire);
            check("lines come back exactly", LineCodec.unpack(packed).equals(wire));
            long plain = bytes(wire);
            System.out.println("         " + wire.size() + " full lines: " + plain
                    + " bytes plain, " + packed.length() + " packed (base64 included, "
                    + (100 - packed.length() * 100L / plain) + "% smaller)");
            check("packed is smaller than plain", packed.length() < plain);
            check("an empty list packs and unpacks", LineCodec.unpack(LineCodec.pack(new ArrayList<>())).isEmpty());

            // A few kilobytes that would inflate to 64 MB: refused, not inflated.
            ByteArrayOutputStream bomb = new ByteArrayOutputStream();
            try (OutputStream gz = new GZIPOutputStream(bomb)) {
                byte[] zeros = new byte[1 << 20];
                for (int i = 0; i < 64; i++) gz.write(zeros);
            }
            boolean refused;
            try {
                LineCodec.unpack(Base64.getEncoder().encodeToString(bomb.toByteArray()));
                refused = false;
            } catch (IOException e) {
                refused = true;
            }
            check("a decompression bomb (" + bomb.size() + " bytes) is refused", refused);
            boolean junkRefused;
            try { LineCodec.unpack("not base64 at all!"); junkRefused = false; } catch (IOException e) { junkRefused = true; }
            check("garbage is refused, not thrown", junkRefused);
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL " + checksRun + " CHECKS PASSED");
        } else {
            System.out.println(failures + " of " + checksRun + " checks FAILED");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    // ─────────── helpers ───────────

    private static final class Built {
        final Path path;
        final EventLog log;
        final PlayerKeys keys;
        Built(Path path, EventLog log, PlayerKeys keys) { this.path = path; this.log = log; this.keys = keys; }
    }

    /** A fresh market and then `deposits` deposits of 3 iron by its creator. */
    private static Built build(int deposits) throws Exception {
        Path p = dir.resolve("storage-" + (++n) + ".jsonl");
        for (Path f : EventLog.filesFor(p)) Files.deleteIfExists(f);
        Files.deleteIfExists(p.resolveSibling(p.getFileName() + ".snapshot.json"));
        EventLog log = new EventLog(p);
        PlayerKeys keys = PlayerKeys.generate();
        MarketBootstrap.createMarket(log, CREATOR, "storage test", keys);
        for (int i = 0; i < deposits; i++) appendDeposit(log, keys, 3);
        return new Built(p, log, keys);
    }

    private static void appendDeposit(EventLog log, PlayerKeys keys, long qty) throws Exception {
        Event.Deposit d = new Event.Deposit();
        d.userId = CREATOR;
        d.marketId = log.marketId();
        d.itemId = "minecraft:iron_ingot";
        d.quantity = qty;
        d.timestamp = System.currentTimeMillis();
        log.append(d, keys.sign(EventCanonical.canonicalPayload(d)));
    }

    private static List<String> storedLines(Path p) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (!line.trim().isEmpty()) out.add(line);
        }
        return out;
    }

    private static List<String> gunzipLines(Path p) throws IOException {
        List<String> out = new ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                new GZIPInputStream(Files.newInputStream(p)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) if (!line.trim().isEmpty()) out.add(line);
        }
        return out;
    }

    private static long bytes(List<String> lines) {
        long total = 0;
        for (String l : lines) total += l.getBytes(StandardCharsets.UTF_8).length + 1;
        return total;
    }
}
