package io.github.badbull643.economiesmod.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * A player's signing identity. RSA-2048 for now, since Java 8 has no Ed25519.
 * When this project moves to a newer Minecraft (and therefore newer Java),
 * switch to Ed25519 — smaller signatures, faster verification. The signature
 * field is just a string, so swapping the algorithm means regenerating keys,
 * not restructuring anything.
 */
public class PlayerKeys {

    private static final String ALGORITHM = "RSA";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final int KEY_SIZE = 2048;

    private final KeyPair keyPair;

    private PlayerKeys(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public PublicKey publicKey()   { return keyPair.getPublic(); }
    public PrivateKey privateKey() { return keyPair.getPrivate(); }

    /** Base64 of the public key, suitable for sharing with a host. */
    public String publicKeyString() {
        return encodePublic(keyPair.getPublic());
    }

    // ─────────── load / create ───────────

    /**
     * Loads the keypair from disk, generating and saving one if absent.
     * The private key file should be treated like a password — anyone with it
     * can act as this player.
     */
    public static PlayerKeys loadOrCreate(Path keyFile) throws GeneralSecurityException, IOException {
        if (Files.exists(keyFile)) {
            return load(keyFile);
        }
        PlayerKeys keys = generate();
        keys.save(keyFile);
        // Loud, because this file is the identity — not a cache of it. A player who
        // moves to another computer without it arrives as a stranger to every market
        // they were part of, with the same name and no way to prove it.
        System.out.println("[keys] ─────────────────────────────────────────────");
        System.out.println("[keys] generated a NEW identity: " + keyFile.toAbsolutePath());
        System.out.println("[keys] this file IS your identity in every market.");
        System.out.println("[keys] copy it to move to another computer; never share it.");
        System.out.println("[keys] ─────────────────────────────────────────────");
        return keys;
    }

    public static PlayerKeys generate() throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance(ALGORITHM);
        gen.initialize(KEY_SIZE);
        return new PlayerKeys(gen.generateKeyPair());
    }

    private static PlayerKeys load(Path keyFile) throws GeneralSecurityException, IOException {
        String content = new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8).trim();
        String[] parts = content.split("\n");
        if (parts.length < 2) {
            throw new IOException("malformed key file: " + keyFile);
        }

        byte[] privBytes = Base64.getDecoder().decode(parts[0].trim());
        byte[] pubBytes  = Base64.getDecoder().decode(parts[1].trim());

        KeyFactory kf = KeyFactory.getInstance(ALGORITHM);
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
        PublicKey pub   = kf.generatePublic(new X509EncodedKeySpec(pubBytes));

        return new PlayerKeys(new KeyPair(pub, priv));
    }

    private void save(Path keyFile) throws IOException {
        if (keyFile.getParent() != null) {
            Files.createDirectories(keyFile.getParent());
        }
        String content = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\n"
                + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n";
        Files.write(keyFile, content.getBytes(StandardCharsets.UTF_8));
    }

    // ─────────── sign / verify ───────────

    /** Signs a payload, returning a base64 signature. */
    public String sign(String payload) throws GeneralSecurityException {
        Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
        sig.initSign(keyPair.getPrivate());
        sig.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    /** Verifies a base64 signature against a public key. */
    public static boolean verify(String payload, String signatureB64, PublicKey publicKey) {
        try {
            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initVerify(publicKey);
            sig.update(payload.getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(signatureB64));
        } catch (Exception e) {
            return false;   // malformed signature, wrong key, whatever — all mean "no"
        }
    }

    // ─────────── encoding helpers ───────────

    public static String encodePublic(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey decodePublic(String encoded) throws GeneralSecurityException {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        return KeyFactory.getInstance(ALGORITHM)
                .generatePublic(new X509EncodedKeySpec(bytes));
    }
    public static void main(String[] args) throws Exception {
        Path keyFile = Paths.get("./test-identity.key");
        Files.deleteIfExists(keyFile);

        PlayerKeys keys = PlayerKeys.loadOrCreate(keyFile);
        String payload = "hello world";
        String sig = keys.sign(payload);

        System.out.println("signature length: " + sig.length());
        System.out.println("verifies: " + PlayerKeys.verify(payload, sig, keys.publicKey()));
        System.out.println("tampered payload verifies: "
                + PlayerKeys.verify("hello worlds", sig, keys.publicKey()));

        // Reload from disk and confirm the same key
        PlayerKeys reloaded = PlayerKeys.loadOrCreate(keyFile);
        System.out.println("same public key: "
                + reloaded.publicKeyString().equals(keys.publicKeyString()));
        System.out.println("reloaded key verifies original signature: "
                + PlayerKeys.verify(payload, sig, reloaded.publicKey()));

        // A different identity must not verify
        PlayerKeys other = PlayerKeys.generate();
        System.out.println("other key verifies: "
                + PlayerKeys.verify(payload, sig, other.publicKey()));
    }}