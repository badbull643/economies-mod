package io.github.badbull643.economiesmod.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * More than one market in a world, one of them active.
 *
 * <h2>Why switchable and not concurrent</h2>
 *
 * Belonging to two markets at once is sound in principle — currency is not fungible
 * across them and an inventory cannot be spent twice — but every singular assumption in
 * this codebase would have to fan out: NetPosition, the high-water mark, migration, fork
 * detection, fill notifications, and the whole screen. That is a very large change for a
 * fairly niche want.
 *
 * Switching costs almost nothing by comparison, because the logs were already separate
 * files and the holder need only pin a different one. It covers what people actually
 * asked for — a friend group some evenings and a bigger server otherwise — and it takes
 * the one-way-door feeling out of migration, since leaving a market no longer has to
 * mean destroying it.
 *
 * <h2>Layout</h2>
 *
 * The default slot stays exactly where single-market worlds already keep it, so nothing
 * existing moves or needs migrating:
 *
 * <pre>
 *   &lt;world&gt;/economiesmod/market.jsonl          — the default slot
 *   &lt;world&gt;/economiesmod/&lt;name&gt;/market.jsonl   — any other
 * </pre>
 *
 * Everything a market owns — high-water mark, pending ops, known keys — is already
 * resolved as a sibling of its log, so each slot gets its own copy for free. That
 * matters most for the high-water mark, which records one market at a time and would
 * otherwise reset itself every time somebody switched.
 */
public final class MarketSlots {

    private MarketSlots() {}

    /** The slot a world already has, and the one it starts in. */
    public static final String DEFAULT = "default";

    private static final String DIR = "economiesmod";
    private static final String LOG = "market.jsonl";
    private static final String ACTIVE = "active-slot";

    private static final int MAX_NAME = 32;

    /**
     * Whether this is a name a slot may have.
     *
     * A slot name becomes a directory name, so this is the boundary between a label and
     * a path. Anything outside letters, digits, space, dash and underscore is refused
     * rather than escaped — "..", a separator, a drive letter or a NUL would each turn
     * a market name into somewhere else on the disk, and there is no reason to want any
     * of them in one.
     */
    public static boolean isValidName(String name) {
        if (name == null) return false;
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME) return false;
        if (DEFAULT.equalsIgnoreCase(trimmed)) return true;

        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == ' ' || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /** Where a slot's log lives. Returns null for a name that is not allowed. */
    public static Path logPath(Path worldDir, String slot) {
        if (worldDir == null) return null;
        String name = slot == null ? DEFAULT : slot.trim();
        if (!isValidName(name)) return null;

        Path base = worldDir.resolve(DIR);
        return DEFAULT.equalsIgnoreCase(name)
                ? base.resolve(LOG)
                : base.resolve(name).resolve(LOG);
    }

    /**
     * Every slot this world holds, default first.
     *
     * A slot is a place a world can be, not a place with something in it — an empty one
     * is a market that does not exist yet, and hiding it until it did would leave a
     * newly made slot impossible to switch to. The default is therefore always present,
     * even in a world that has never had a market.
     *
     * Read from the directories themselves rather than a registry, so there is nothing
     * to fall out of step with the files. Same reasoning that keeps market identity in
     * the log rather than beside it.
     */
    public static List<String> list(Path worldDir) {
        List<String> out = new ArrayList<>();
        if (worldDir == null) return out;

        out.add(DEFAULT);

        Path base = worldDir.resolve(DIR);
        if (!Files.isDirectory(base)) return out;

        List<String> named = new ArrayList<>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(base)) {
            for (Path child : dirs) {
                if (!Files.isDirectory(child)) continue;
                String name = child.getFileName().toString();
                if (isValidName(name) && !DEFAULT.equalsIgnoreCase(name)) named.add(name);
            }
        } catch (IOException e) {
            // A world with an unreadable market directory still has whatever was found.
            System.err.println("[economiesmod] could not list markets: " + e);
        }
        Collections.sort(named);
        out.addAll(named);
        return out;
    }

    /**
     * The slot this world is currently using.
     *
     * Falls back to the default whenever the answer is missing or no longer makes sense,
     * so a hand-edited or deleted pointer leaves a usable world rather than a broken one.
     */
    public static String active(Path worldDir) {
        if (worldDir == null) return DEFAULT;
        Path marker = worldDir.resolve(DIR).resolve(ACTIVE);
        try {
            if (Files.exists(marker)) {
                String name = new String(Files.readAllBytes(marker),
                        StandardCharsets.UTF_8).trim();
                if (isValidName(name)) return name;
            }
        } catch (IOException e) {
            System.err.println("[economiesmod] could not read the active market: " + e);
        }
        return DEFAULT;
    }

    /**
     * The name the market in this slot calls itself, or null when it holds none yet.
     *
     * Reads the genesis event and stops, which EventLog is built to make cheap. Worth
     * the read: a slot's directory name is a label somebody never chose, and a list of
     * "market-2" and "market-3" tells a player nothing about which is which.
     */
    public static String marketNameIn(Path worldDir, String slot) {
        Path log = logPath(worldDir, slot);
        if (log == null || !Files.exists(log)) return null;
        try {
            return new EventLog(log).marketName();
        } catch (Exception e) {
            return null;   // damaged or half-written; the slot still exists
        }
    }

    /**
     * Makes room for another market and returns its slot name.
     *
     * Named rather than asked about, because the name that matters is the market's own
     * and this one is only a folder. The slot is left empty: an empty slot is a market
     * that does not exist yet, which the Market screen already knows how to offer
     * Create, Import and Connect for.
     */
    public static String createNext(Path worldDir) throws IOException {
        if (worldDir == null) throw new IOException("no world open");

        List<String> existing = list(worldDir);
        for (int n = 2; n < 100; n++) {
            String name = "market-" + n;
            Path log = logPath(worldDir, name);
            if (log == null) continue;
            if (existing.contains(name) || Files.exists(log.getParent())) continue;

            Files.createDirectories(log.getParent());
            return name;
        }
        throw new IOException("this world already holds a great many markets");
    }

    /**
     * Removes a market from this world entirely, files and all.
     *
     * The default slot cannot be removed. It is where a single-market world keeps its
     * market, so deleting it would mean deleting the thing every other world calls
     * "the market" — and there would then be no slot guaranteed to exist. Resetting it
     * empties it, which is the same outcome by the route built for it.
     *
     * Everything a market owns is a sibling of its log inside the slot directory, so
     * this removes the directory rather than hunting for files: a high-water mark or a
     * pending-ops journal left behind would be picked up by whatever occupied the name
     * next.
     */
    public static void delete(Path worldDir, String slot) throws IOException {
        if (worldDir == null) throw new IOException("no world open");
        if (!isValidName(slot)) throw new IOException("not a usable market name: " + slot);
        if (DEFAULT.equalsIgnoreCase(slot.trim())) {
            throw new IOException("the first market in a world cannot be removed —"
                    + " discard its history instead");
        }

        Path dir = worldDir.resolve(DIR).resolve(slot.trim());
        if (!Files.isDirectory(dir)) return;

        // Deepest first: a directory cannot go while it still holds anything.
        List<Path> paths = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.forEach(paths::add);
        }
        Collections.sort(paths, Collections.reverseOrder());
        for (Path p : paths) Files.deleteIfExists(p);
    }

    /** Remembers which slot this world is using, so it survives a restart. */
    public static void setActive(Path worldDir, String slot) throws IOException {
        if (worldDir == null) return;
        String name = slot == null ? DEFAULT : slot.trim();
        if (!isValidName(name)) throw new IOException("not a usable market name: " + slot);

        Path base = worldDir.resolve(DIR);
        Files.createDirectories(base);
        Files.write(base.resolve(ACTIVE), name.getBytes(StandardCharsets.UTF_8));
    }
}
