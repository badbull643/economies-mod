package io.github.badbull643.economiesmod.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.nio.file.*;
import java.util.*;

//the final peer cache file

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
        public String publicKey;     // pinned on first sight
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
                                    String address, int port, String publicKey) {
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
        if (publicKey != null) p.publicKey = publicKey;
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

    /**
     * Trust-on-first-use for a peer's key. Returns false if we've seen this peer
     * before with a different key — i.e. someone is claiming their identity.
     */
    /** True if this peer is known with a different key than the one presented. */
    public synchronized boolean keyChanged(String userId, String publicKey) {
        if (userId == null || publicKey == null) return false;
        Peer p = peers.get(userId);
        return p != null && p.publicKey != null && !p.publicKey.equals(publicKey);
    }
}