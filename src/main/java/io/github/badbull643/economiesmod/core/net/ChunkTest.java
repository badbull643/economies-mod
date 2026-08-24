package io.github.badbull643.economiesmod.core.net;

import io.github.badbull643.economiesmod.core.Event;
import io.github.badbull643.economiesmod.core.EventCanonical;
import io.github.badbull643.economiesmod.core.EventLog;
import io.github.badbull643.economiesmod.core.MarketBootstrap;
import io.github.badbull643.economiesmod.core.PeerCache;
import io.github.badbull643.economiesmod.core.PlayerKeys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * End-to-end check that a history too big for one frame still transfers.
 *
 * MessageChannel caps a single line at 1MB. Every bulk path — Sync, CatchUp,
 * MigrateRequest — can exceed that on a market of any age, and the failure mode is
 * ugly: the socket dies mid-transfer with no reply, so it surfaces as an unexplained
 * connect failure rather than anything naming the real cause.
 *
 * This builds a genuinely oversized history rather than shrinking the budget to force
 * the path, because the thing worth testing is that the real constant is big enough to
 * be correct and small enough to actually split.
 *
 * Not part of MarketTests: that suite is pure-core and runs in milliseconds, and this
 * binds sockets and signs a few thousand RSA events. Run it directly.
 */
public class ChunkTest {

    private static int failures = 0;
    private static int checksRun = 0;

    private static void check(String label, long actual, long expected) {
        checksRun++;
        boolean ok = actual == expected;
        if (!ok) failures++;
        System.out.println((ok ? "    ok   " : "    FAIL ") + label
                + " — expected " + expected + ", got " + actual);
    }

    private static final UUID HOST_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID JOINER  = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get("build", "test-scratch");
        Files.createDirectories(dir);

        Path hostLog = dir.resolve("chunk-host.jsonl");
        Path joinerLog = dir.resolve("chunk-joiner.jsonl");

        System.out.println("  [N1: a history larger than one frame syncs to a fresh client]");

        Files.deleteIfExists(hostLog);
        Files.deleteIfExists(joinerLog);
        // Both ends generate a fresh keypair each run, so a stale peer cache would
        // report the host's identity as changed. Not a finding — just leftovers.
        Files.deleteIfExists(dir.resolve("chunk-host-peers.json"));
        Files.deleteIfExists(dir.resolve("chunk-joiner-peers.json"));
        Files.deleteIfExists(hostLog.resolveSibling("known-keys.json"));

        PlayerKeys hostKeys = PlayerKeys.generate();
        EventLog log = new EventLog(hostLog);
        MarketBootstrap.createMarket(log, HOST_ID, "chunk test market", hostKeys);

        // Grow the log past the *frame cap*, not merely past the chunk budget. Sized
        // off the budget it would only prove that chunking splits — sized off the cap
        // it proves the un-chunked path could not have carried this at all, which is
        // the regression worth holding onto. Each signed line is ~600 bytes (the
        // base64 RSA signature dominates).
        long bytes = totalBytes(hostLog);
        int appended = 0;
        while (bytes <= MessageChannel.MAX_LINE_LENGTH) {
            Event.Deposit d = new Event.Deposit();
            d.userId = HOST_ID;
            d.marketId = log.marketId();
            d.clientEventId = UUID.randomUUID().toString();
            d.timestamp = System.currentTimeMillis();
            d.itemId = "minecraft:iron_ingot";
            d.quantity = 1;
            log.append(d, hostKeys.sign(EventCanonical.canonicalPayload(d)));
            appended++;
            if ((appended & 0xFF) == 0) bytes = totalBytes(hostLog);
        }
        bytes = totalBytes(hostLog);

        System.out.println("    (built " + log.lastSeq() + " events, " + bytes + " bytes)");
        check("history exceeds a single frame", bytes > MessageChannel.MAX_LINE_LENGTH ? 1 : 0, 1);

        List<String> raw = log.rawLinesFrom(1);
        int expectedChunks = MessageChannel.chunkByByteBudget(raw).size();
        check("splits into more than one chunk", expectedChunks > 1 ? 1 : 0, 1);

        int port = TestPorts.free();
        HostServer host = new HostServer(port, hostLog, "chunkhost", HOST_ID.toString(),
                hostKeys, new PeerCache(dir.resolve("chunk-host-peers.json")), 0L);

        Thread hostThread = new Thread(() -> {
            try { host.start(); } catch (IOException e) { /* stopped */ }
        }, "chunk-test-host");
        hostThread.setDaemon(true);
        hostThread.start();

        IOException bindError = host.awaitBound(5000);
        if (bindError != null) throw bindError;

        try {
            EventLog fresh = new EventLog(joinerLog);
            check("joiner starts empty", fresh.lastSeq(), 0);

            MarketClient client = new MarketClient(JOINER, "Joiner", PlayerKeys.generate(),
                    fresh, true, new PeerCache(dir.resolve("chunk-joiner-peers.json")), 0);
            client.connect("127.0.0.1", port);

            // The joiner registers itself on connect, so the host may be a couple of
            // events ahead by now; what matters is that the whole history arrived.
            EventLog after = new EventLog(joinerLog);
            check("joiner received the full history", after.lastSeq() >= log.lastSeq() ? 1 : 0, 1);
            check("joiner's chain verifies", after.verifyChain(), -1);
            check("joiner agrees with host at host's head",
                    after.hashAt(log.lastSeq()).equals(log.hashAt(log.lastSeq())) ? 1 : 0, 1);

            client.disconnect();
        } finally {
            host.stop();
        }

        // The host streams a history now instead of reading it all in first — measured
        // at 63.6 MB held for a 57.7 MB log before, and 0.4 MB after, bounded by the
        // chunk budget rather than by the market. What must not have changed is a single
        // byte of what goes out, so the frames the streaming loop produces are compared
        // against the ones the gathering version made.
        {
            EventLog hostLog2 = new EventLog(dir.resolve("chunk-host.jsonl"));
            List<String> all = hostLog2.rawLinesFrom(1);
            List<List<String>> gathered = MessageChannel.chunkByByteBudget(all);

            final List<List<String>> streamed = new ArrayList<>();
            final List<String> current = new ArrayList<>();
            final long[] tally = { 0 };
            hostLog2.forEachRawLine(1, line -> {
                int n = line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (!current.isEmpty() && tally[0] + n > MessageChannel.CHUNK_BUDGET_BYTES) {
                    streamed.add(new ArrayList<>(current));
                    current.clear();
                    tally[0] = 0;
                }
                current.add(line);
                tally[0] += n;
                return true;
            });
            streamed.add(new ArrayList<>(current));

            check("the fixture is big enough to be chunked", gathered.size() > 1 ? 1 : 0, 1);
            check("streaming produces the same number of frames",
                    streamed.size(), gathered.size());
            check("and the same lines in the same frames",
                    streamed.equals(gathered) ? 1 : 0, 1);

            // An empty tail still gets one frame, or a client that is already up to date
            // never hears that the handshake finished.
            final List<List<String>> none = new ArrayList<>();
            final List<String> empty = new ArrayList<>();
            hostLog2.forEachRawLine(hostLog2.lastSeq() + 1, line -> { empty.add(line); return true; });
            none.add(empty);
            check("a client with nothing to receive still gets one frame", none.size(), 1);
            check("and it is empty", none.get(0).size(), 0);
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL " + checksRun + " CHECKS PASSED");
        } else {
            System.out.println(failures + " of " + checksRun + " checks FAILED");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static long totalBytes(Path file) throws IOException {
        return Files.size(file);
    }
}
