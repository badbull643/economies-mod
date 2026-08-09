package io.github.badbull643.economiesmod.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Maps player UUIDs to their public keys. The host consults this to verify
 * that a proposal really came from the player it claims to be from.
 *
 * Stored as a plain JSON map of uuid -> base64 public key, so an admin can
 * edit it by hand.
 */
public class KeyRegistry {

    private final Map<String, String> keys = new HashMap<>();   // uuid -> base64
    private final Path file;
    private final boolean trustOnFirstUse;

    /**
     * @param trustOnFirstUse if true, an unknown UUID's first key is accepted and
     *                        recorded. Convenient for a friend group; unsafe if
     *                        strangers can reach the port.
     */
    public KeyRegistry(Path file, boolean trustOnFirstUse) throws IOException {
        this.file = file;
        this.trustOnFirstUse = trustOnFirstUse;
        load();
    }

    private void load() throws IOException {
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            Map<String, String> loaded = new Gson().fromJson(r,
                    new TypeToken<Map<String, String>>() {}.getType());
            if (loaded != null) keys.putAll(loaded);
        }
        System.out.println("[keys] loaded " + keys.size() + " known keys");
    }

    private void save() throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file)) {
            new Gson().toJson(keys, w);
        }
    }

    /**
     * Registers a key for a user at connection time.
     * Returns false if the user is known and presented a *different* key —
     * i.e. someone is trying to impersonate them.
     */
    public synchronized boolean register(UUID userId, String publicKeyB64) throws IOException {
        String known = keys.get(userId.toString());
        if (known == null) {
            if (!trustOnFirstUse) return false;
            keys.put(userId.toString(), publicKeyB64);
            save();
            System.out.println("[keys] registered new identity " + userId);
            return true;
        }
        return known.equals(publicKeyB64);
    }

    public synchronized PublicKey lookup(UUID userId) {
        String encoded = keys.get(userId.toString());
        if (encoded == null) return null;
        try {
            return PlayerKeys.decodePublic(encoded);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    public synchronized boolean isKnown(UUID userId) {
        return keys.containsKey(userId.toString());
    }
}