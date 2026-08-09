package io.github.badbull643.economiesmod.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Addresses of peers we've connected to or been connected by. Used to find
 * whoever is hosting without anyone having to share an address.
 */
public class PeerCache {

    public static class Peer {
        public String userId;
        public String displayName;
        public String address;
        public int port;
        public long lastSeen;
    }

    private final Path file;
    private final Map<String, Peer> peers = new LinkedHashMap<>();   // by userId

    public PeerCache(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            List<Peer> loaded = new Gson().fromJson(r,
                    new TypeToken<List<Peer>>() {}.getType());
            if (loaded != null) {
                for (Peer p : loaded) peers.put(p.userId, p);
            }
        } catch (IOException e) {
            System.err.println("[peers] load failed: " + e);
        }
        System.out.println("[peers] loaded " + peers.size() + " known peers");
    }

    private void save() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                new Gson().toJson(new ArrayList<>(peers.values()), w);
            }
        } catch (IOException e) {
            System.err.println("[peers] save failed: " + e);
        }
    }

    public synchronized void record(String userId, String displayName,
                                    String address, int port) {
        if (userId == null || address == null) return;

        Peer p = peers.get(userId);
        if (p == null) {
            p = new Peer();
            p.userId = userId;
            peers.put(userId, p);
        }
        p.displayName = displayName;
        p.address = address;
        p.port = port;
        p.lastSeen = System.currentTimeMillis();
        save();
    }

    public synchronized List<Peer> all() {
        return new ArrayList<>(peers.values());
    }

    /** Merges peers learned from someone else's cache. Doesn't overwrite fresher entries. */
    public synchronized void merge(List<Peer> incoming) {
        if (incoming == null) return;
        boolean changed = false;
        for (Peer in : incoming) {
            if (in.userId == null || in.address == null) continue;
            Peer existing = peers.get(in.userId);
            if (existing == null || in.lastSeen > existing.lastSeen) {
                peers.put(in.userId, in);
                changed = true;
            }
        }
        if (changed) save();
    }
}