package io.github.badbull643.economiesmod.core;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Preferences that outlive the game session.
 *
 * Everything the UI remembered before this lived in static fields on the screen, so a
 * port typed once had to be typed again after every restart — and the fields were lost
 * on close anyway if the screen never got the chance to save them.
 *
 * Global rather than per-world, because these describe the player and their setup, not
 * a market: the port you can forward and the notifications you want are the same in
 * every world. That puts the file in the config directory alongside the identity key
 * and peer cache, and — like those — it must be suffixed with the player's name, since
 * the clientAlice/clientBob dev launches run at once and would otherwise overwrite each
 * other's settings.
 */
public class Settings {

    private static final Gson gson = new Gson();

    /** On-disk shape. Flat and defaulted, so a file written by an older build that
     *  lacks a field still loads and simply keeps the default for it. */
    private static class Record {
        int hostPort = 25555;
        String lastHostAddress = "localhost:25555";
        String lastItem = "minecraft:iron_ingot";
        String lastMarketName = "";

        // Fill notifications. Both surfaces are independent: chat keeps a scrollback
        // you can read after the fact, the action bar is glanceable and transient.
        boolean notifyChat = true;
        boolean notifyActionBar = false;
        /** Above this many notifications a minute, fills are batched into one summary
         *  rather than dropped — a market-maker on a busy host loses detail, not news. */
        int notifyMaxPerMinute = 20;

        /** The listings panel beside the inventory. On, because it is the only thing in
         *  the mod that shows you a market you did not go looking at. */
        boolean inventoryPanel = true;

        /**
         * How many listings that panel shows.
         *
         * Capped rather than free, and the cap is the interesting part: this is a glance
         * at what is new, and a market with enough volume to fill twenty rows is a market
         * where the last twenty listings stopped being news. The panel is not a book —
         * that is what the Market screen is for, and it scrolls.
         */
        int inventoryPanelRows = 6;

        /**
         * Market ids whose whole history this machine keeps even though a dedicated
         * server serves them.
         *
         * A list of the exceptions rather than a single switch, because the decision is
         * about a market and not about a player: somebody can be the archive for the
         * server they care about without carrying every public market they ever visit.
         *
         * Empty by default, which is the default of step 4 — a client of a dedicated
         * market keeps a snapshot. A rotating host is not affected and is not listed
         * here; there the history is how hosting rotates at all.
         */
        java.util.List<String> archiveMarkets = new java.util.ArrayList<>();
    }

    private final Path file;
    private Record record = new Record();

    public Settings(Path file) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Record loaded = gson.fromJson(json, Record.class);
                if (loaded != null) record = loaded;
            }
        } catch (Exception e) {
            // Catches Exception, not IOException: a malformed settings file must never
            // stop a world loading. Falling back to defaults costs the player their
            // preferences, which is recoverable; failing to load is not.
            System.err.println("[economiesmod] could not read settings: " + e);
        }
    }

    public int hostPort() { return record.hostPort; }
    public String lastHostAddress() { return record.lastHostAddress; }
    public String lastItem() { return record.lastItem; }
    public String lastMarketName() { return record.lastMarketName; }
    public boolean notifyChat() { return record.notifyChat; }
    public boolean notifyActionBar() { return record.notifyActionBar; }
    public int notifyMaxPerMinute() { return record.notifyMaxPerMinute; }

    /**
     * Whether to keep the full history of this market when a dedicated server serves it.
     *
     * A null market id answers true: not knowing which market this is, is not a reason
     * to throw a history away. Everything that decides to stop persisting has to get a
     * definite no out of this, never a shrug.
     */
    public boolean archives(java.util.UUID marketId) {
        if (marketId == null) return true;
        return record.archiveMarkets != null
                && record.archiveMarkets.contains(marketId.toString());
    }

    public void setArchives(java.util.UUID marketId, boolean on) {
        if (marketId == null) return;
        if (record.archiveMarkets == null) {
            record.archiveMarkets = new java.util.ArrayList<>();
        }
        String id = marketId.toString();
        if (on) {
            if (!record.archiveMarkets.contains(id)) record.archiveMarkets.add(id);
        } else {
            record.archiveMarkets.remove(id);
        }
        save();
    }

    public void setHostPort(int port) {
        if (port < 1024 || port > 65535 || port == record.hostPort) return;
        record.hostPort = port;
        save();
    }

    public void setLastHostAddress(String address) {
        if (address == null || address.equals(record.lastHostAddress)) return;
        record.lastHostAddress = address;
        save();
    }

    public void setLastItem(String itemId) {
        if (itemId == null || itemId.equals(record.lastItem)) return;
        record.lastItem = itemId;
        save();
    }

    public void setLastMarketName(String name) {
        if (name == null || name.equals(record.lastMarketName)) return;
        record.lastMarketName = name;
        save();
    }

    public void setNotifyChat(boolean on) {
        if (on == record.notifyChat) return;
        record.notifyChat = on;
        save();
    }

    public void setNotifyActionBar(boolean on) {
        if (on == record.notifyActionBar) return;
        record.notifyActionBar = on;
        save();
    }

    public void setNotifyMaxPerMinute(int max) {
        if (max < 0 || max == record.notifyMaxPerMinute) return;
        record.notifyMaxPerMinute = max;
        save();
    }

    /** The most rows the inventory panel will show, whatever a file asks for. */
    public static final int MAX_INVENTORY_PANEL_ROWS = 15;

    public boolean inventoryPanel() { return record.inventoryPanel; }

    /**
     * Clamped on the way out, not only on the way in.
     *
     * A hand-edited settings file is the other way this number arrives, and a 400 there
     * would draw a panel taller than the window with no way to reach the control that
     * fixed it. Clamping in the setter alone protects the path that already had a person
     * looking at it.
     */
    public int inventoryPanelRows() {
        return Math.max(1, Math.min(MAX_INVENTORY_PANEL_ROWS, record.inventoryPanelRows));
    }

    public void setInventoryPanel(boolean on) {
        if (on == record.inventoryPanel) return;
        record.inventoryPanel = on;
        save();
    }

    /** @return what was actually stored, which is the request clamped to the cap. */
    public int setInventoryPanelRows(int rows) {
        int clamped = Math.max(1, Math.min(MAX_INVENTORY_PANEL_ROWS, rows));
        if (clamped != record.inventoryPanelRows) {
            record.inventoryPanelRows = clamped;
            save();
        }
        return clamped;
    }

    private void save() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.write(file, gson.toJson(record).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[economiesmod] could not save settings: " + e);
        }
    }
}
