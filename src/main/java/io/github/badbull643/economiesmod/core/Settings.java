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

    private void save() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.write(file, gson.toJson(record).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[economiesmod] could not save settings: " + e);
        }
    }
}
