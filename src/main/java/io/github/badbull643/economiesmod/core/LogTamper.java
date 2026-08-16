package io.github.badbull643.economiesmod.core;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Adversarial test instrument: forges an event in a log and repairs the hash chain
 * around it, producing a file that passes every integrity check except the signature.
 *
 * This exists because the mod cannot produce the attack it defends against. Everything
 * it writes is correctly signed, and editing a log by hand breaks the chain — which
 * HostServer catches at startup, so the tampered log never reaches a client and the
 * client-side check never gets tested. Re-chaining is the step that turns a broken
 * file into a genuinely hostile one.
 *
 * The resulting log will:
 *   - pass EventLog.verifyChain()          (hashes are recomputed and consistent)
 *   - start a HostServer without complaint
 *   - be REFUSED by any client that verifies signatures, and by MarketArchive.verify
 *
 * Usage:
 *   java ...core.LogTamper &lt;market.jsonl&gt; &lt;seq&gt; &lt;find&gt; &lt;replace&gt;
 *
 * Example — make event 5 claim ten thousand iron instead of ten:
 *   java ...core.LogTamper run/saves/World/economiesmod/market.jsonl 5 '"quantity":10' '"quantity":10000'
 */
public class LogTamper {

    private static final Gson gson = new Gson();

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("usage: LogTamper <logfile> <seq> <find> <replace>");
            System.out.println("  edits the event at <seq>, then re-chains everything after it");
            return;
        }

        Path file = Paths.get(args[0]);
        long targetSeq = Long.parseLong(args[1]);
        String find = args[2];
        String replace = args[3];

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        lines.removeIf(l -> l.trim().isEmpty());

        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            long seq = new JsonParser().parse(lines.get(i)).getAsJsonObject()
                    .get("seq").getAsLong();
            if (seq == targetSeq) { idx = i; break; }
        }
        if (idx < 0) {
            System.out.println("no event with seq " + targetSeq + " in " + file);
            return;
        }

        String before = lines.get(idx);
        String after = before.replace(find, replace);
        if (after.equals(before)) {
            System.out.println("'" + find + "' not found in event " + targetSeq + ":");
            System.out.println("  " + before);
            return;
        }
        lines.set(idx, after);

        // Re-chain from the edited entry onward. Everything before it is left byte for
        // byte as it was, so only the tampered event and its successors change hash.
        String prevHash = idx == 0
                ? "0"
                : EventLog.parseLine(lines.get(idx - 1)).hash;

        for (int i = idx; i < lines.size(); i++) {
            SequencedEvent se = EventLog.parseLine(lines.get(i));
            se.prevHash = prevHash;
            se.hash = EventLog.recomputeHash(se);
            lines.set(i, gson.toJson(se));
            prevHash = se.hash;
        }

        Files.write(file, lines, StandardCharsets.UTF_8);

        System.out.println("tampered event " + targetSeq + " and re-chained "
                + (lines.size() - idx) + " entries");
        System.out.println("  before: " + before);
        System.out.println("  after:  " + lines.get(idx));

        EventLog check = new EventLog(file);
        System.out.println("verifyChain() now returns " + check.verifyChain()
                + "  (-1 means the chain looks perfectly intact — that is the point)");

        // Show both verdicts side by side: the file is structurally sound and
        // cryptographically bogus, which is precisely the case signatures exist for.
        try {
            MarketArchive.verify(file);
            System.out.println("WARNING: signature check PASSED — the tamper did not"
                    + " touch a signed field, so this file is not a useful test");
        } catch (MarketArchive.InvalidArchive e) {
            System.out.println("signature check rejects it: " + e.getMessage());
        }
    }
}
