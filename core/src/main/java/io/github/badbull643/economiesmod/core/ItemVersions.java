package io.github.badbull643.economiesmod.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which Minecraft versions have which items, for markets shared across versions.
 *
 * A market that several versions trade on has to answer one question for every deposit,
 * buy and withdrawal: does this player's game have this item? The answer is a property of
 * the item, the same for everyone, so it lives here as a table rather than on each order.
 * Matching never asks it: every order in an item's book is reachable by everybody who can
 * see that book.
 *
 * Items are named in a shared market by their FIRST name. Mojang has renamed four items
 * between 1.16.5 and 26.3 ({@code chain} became {@code iron_chain}, and so on); a log has to
 * mean the same thing forever, and the newest name is the one that keeps moving. Clients
 * translate to their own version's name when they show or withdraw something.
 *
 * Only vanilla items are in the table. Anything else is unknown to it, and a shared market
 * refuses it: there is no way to tell which versions another mod's item exists in.
 *
 * The data is generated (ItemVersionsData, from tools/item_versions.py), never typed.
 */
public final class ItemVersions {
    private ItemVersions() {}

    private static final String NS = "minecraft:";

    /** Every version the table covers, oldest first. */
    public static final List<String> VERSIONS =
            Collections.unmodifiableList(Arrays.asList(ItemVersionsData.VERSIONS));

    /** First name, without namespace, to the index of the first version that has it. */
    private static final Map<String, Integer> FIRST = new HashMap<>();
    /** A later name to the first name. */
    private static final Map<String, String> FIRST_NAME_OF = new HashMap<>();
    /** First name to {index the new name starts at, new name}. */
    private static final Map<String, Object[]> RENAME = new HashMap<>();

    static {
        for (int i = 0; i < ItemVersionsData.ADDED.length; i++) {
            for (String name : ItemVersionsData.ADDED[i].split(" ")) {
                if (!name.isEmpty()) FIRST.put(name, i);
            }
        }
        for (String[] r : ItemVersionsData.RENAMED) {
            FIRST_NAME_OF.put(r[2], r[0]);
            RENAME.put(r[0], new Object[]{VERSIONS.indexOf(r[1]), r[2]});
        }
    }

    /** Whether the table knows this version, so a market may be shared with it. */
    public static boolean knowsVersion(String version) {
        return VERSIONS.contains(GameVersions.normalize(version));
    }

    /**
     * The id a shared market uses for this item: its first name. A later name becomes the
     * first one; everything else, including other mods' items, comes back unchanged.
     */
    public static String canonical(String id) {
        if (id == null || !id.startsWith(NS)) return id;
        String first = FIRST_NAME_OF.get(id.substring(NS.length()));
        return first == null ? id : NS + first;
    }

    /** Whether this is a vanilla item's first name, so a shared market can trade it. */
    public static boolean isKnownItem(String id) {
        return id != null && id.startsWith(NS) && FIRST.containsKey(id.substring(NS.length()));
    }

    /**
     * Whether a game on {@code version} has this item, under whatever name it uses there.
     * False for anything the table doesn't know, and for versions it doesn't cover.
     */
    public static boolean availableIn(String id, String version) {
        String c = canonical(id);
        if (!isKnownItem(c)) return false;
        int v = VERSIONS.indexOf(GameVersions.normalize(version));
        return v >= 0 && FIRST.get(c.substring(NS.length())) <= v;
    }

    /** What a game on {@code version} calls this item: the id to look up in its registry. */
    public static String nameIn(String id, String version) {
        String c = canonical(id);
        if (c == null || !c.startsWith(NS)) return c;
        Object[] r = RENAME.get(c.substring(NS.length()));
        if (r == null) return c;
        int v = VERSIONS.indexOf(GameVersions.normalize(version));
        return v >= (Integer) r[0] ? NS + r[1] : c;
    }

    /** The first version that has this item, or null if the table doesn't know it. */
    public static String firstVersion(String id) {
        String c = canonical(id);
        if (!isKnownItem(c)) return null;
        return VERSIONS.get(FIRST.get(c.substring(NS.length())));
    }
}
