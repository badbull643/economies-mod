package io.github.badbull643.economiesmod.core;

import io.github.badbull643.economiesmod.core.crypto.bc.math.ec.rfc8032.Ed25519;

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
 * A signing identity: a player, a server, or a bot.
 *
 * Two algorithms. RSA-2048 is what every identity made before this class knew Ed25519 has, and
 * those keep working unchanged for as long as they exist — an identity is permanently bound to
 * its key, so nothing here ever converts one. Ed25519 is what new identities get: its signature
 * is 88 characters against RSA's 344, which is a third of a typical log line, every line.
 *
 * Java 8 has no Ed25519, and the server jar and the 1.16.5 mod run on Java 8, so the
 * implementation is Bouncy Castle's, vendored into core (see core.crypto.bc).
 *
 * The algorithm travels in the key itself. An Ed25519 public key is written "ed25519:" followed
 * by its 32 raw bytes in base64; an RSA key stays the bare base64 it always was. So
 * KeyRegistered, the creator key in MarketCreated, probe replies and known-keys.json all say
 * which algorithm a key is without any of them gaining a field, and nothing that was ever
 * signed has a different payload now.
 */
public class PlayerKeys {

    public enum Algorithm { RSA, ED25519 }

    /** Marks an Ed25519 key, public or private, wherever one is written down. */
    public static final String ED25519_PREFIX = "ed25519:";

    private static final String RSA = "RSA";
    private static final String RSA_SIGNATURE = "SHA256withRSA";
    private static final int RSA_KEY_SIZE = 2048;

    private final Algorithm algorithm;

    // RSA only.
    private final KeyPair rsa;

    // Ed25519 only: the 32-byte private seed and the public key it gives.
    private final byte[] edSecret;
    private final Ed25519PublicKey edPublic;

    private PlayerKeys(KeyPair rsa) {
        this.algorithm = Algorithm.RSA;
        this.rsa = rsa;
        this.edSecret = null;
        this.edPublic = null;
    }

    private PlayerKeys(byte[] edSecret, byte[] edPublic) {
        this.algorithm = Algorithm.ED25519;
        this.rsa = null;
        this.edSecret = edSecret.clone();
        this.edPublic = new Ed25519PublicKey(edPublic);
    }

    public Algorithm algorithm() { return algorithm; }

    public PublicKey publicKey() {
        return algorithm == Algorithm.RSA ? rsa.getPublic() : edPublic;
    }

    /** The public key as it is written into events and replies. */
    public String publicKeyString() {
        return encodePublic(publicKey());
    }

    // ─────────── load / create ───────────

    /**
     * Loads the keypair from disk, generating and saving one if absent.
     * The private key file should be treated like a password — anyone with it
     * can act as this player.
     *
     * A file that exists is loaded as whatever it is, RSA or Ed25519. Only a missing file is
     * generated, and then as Ed25519.
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

    /** A new identity. Ed25519: everything new is. */
    public static PlayerKeys generate() throws GeneralSecurityException {
        return generate(Algorithm.ED25519);
    }

    /** A new identity of a given algorithm — RSA only for tests that need the old kind. */
    public static PlayerKeys generate(Algorithm algorithm) throws GeneralSecurityException {
        if (algorithm == Algorithm.RSA) {
            KeyPairGenerator gen = KeyPairGenerator.getInstance(RSA);
            gen.initialize(RSA_KEY_SIZE);
            return new PlayerKeys(gen.generateKeyPair());
        }
        byte[] secret = new byte[Ed25519.SECRET_KEY_SIZE];
        Ed25519.generatePrivateKey(new SecureRandom(), secret);
        byte[] pub = new byte[Ed25519.PUBLIC_KEY_SIZE];
        Ed25519.generatePublicKey(secret, 0, pub, 0);
        return new PlayerKeys(secret, pub);
    }

    /** Writes this identity to a file in the format load reads. */
    public void saveTo(Path keyFile) throws IOException {
        save(keyFile);
    }

    /** Reads an identity file written by save or saveTo, whichever algorithm it holds. */
    public static PlayerKeys load(Path keyFile) throws GeneralSecurityException, IOException {
        String content = new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8).trim();
        String[] parts = content.split("\n");
        if (parts.length < 2) {
            throw new IOException("malformed key file: " + keyFile);
        }
        String first = parts[0].trim();
        String second = parts[1].trim();

        if (first.startsWith(ED25519_PREFIX)) {
            byte[] secret = Base64.getDecoder().decode(first.substring(ED25519_PREFIX.length()));
            byte[] pub = decodeEd25519Public(second);
            if (secret.length != Ed25519.SECRET_KEY_SIZE) {
                throw new IOException("malformed Ed25519 key file: " + keyFile);
            }
            // The public half is derivable from the private one, so it is checked rather than
            // trusted: a file whose two lines disagree would sign as one identity and announce
            // another.
            byte[] derived = new byte[Ed25519.PUBLIC_KEY_SIZE];
            Ed25519.generatePublicKey(secret, 0, derived, 0);
            if (!MessageDigest.isEqual(derived, pub)) {
                throw new IOException("key file's public key does not match its private key: "
                        + keyFile);
            }
            return new PlayerKeys(secret, pub);
        }

        byte[] privBytes = Base64.getDecoder().decode(first);
        byte[] pubBytes  = Base64.getDecoder().decode(second);

        KeyFactory kf = KeyFactory.getInstance(RSA);
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
        PublicKey pub   = kf.generatePublic(new X509EncodedKeySpec(pubBytes));

        return new PlayerKeys(new KeyPair(pub, priv));
    }

    private void save(Path keyFile) throws IOException {
        if (keyFile.getParent() != null) {
            Files.createDirectories(keyFile.getParent());
        }
        String content;
        if (algorithm == Algorithm.RSA) {
            content = Base64.getEncoder().encodeToString(rsa.getPrivate().getEncoded())
                    + "\n"
                    + Base64.getEncoder().encodeToString(rsa.getPublic().getEncoded())
                    + "\n";
        } else {
            content = ED25519_PREFIX + Base64.getEncoder().encodeToString(edSecret)
                    + "\n"
                    + encodePublic(edPublic)
                    + "\n";
        }
        Files.write(keyFile, content.getBytes(StandardCharsets.UTF_8));
    }

    // ─────────── sign / verify ───────────

    /** Signs a payload, returning a base64 signature. */
    public String sign(String payload) throws GeneralSecurityException {
        byte[] message = payload.getBytes(StandardCharsets.UTF_8);
        if (algorithm == Algorithm.ED25519) {
            byte[] sig = new byte[Ed25519.SIGNATURE_SIZE];
            Ed25519.sign(edSecret, 0, edPublic.raw, 0, message, 0, message.length, sig, 0);
            return Base64.getEncoder().encodeToString(sig);
        }
        Signature sig = Signature.getInstance(RSA_SIGNATURE);
        sig.initSign(rsa.getPrivate());
        sig.update(message);
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    /**
     * Verifies a base64 signature against a public key, of either algorithm.
     *
     * Ed25519 verification is Bouncy Castle's, which is the strict RFC 8032 check: among other
     * things it refuses a signature whose scalar is out of range, which is what stops anyone
     * deriving a second valid signature from someone else's. That matters here more than most
     * places, because a signature is part of what the chain hashes — a re-shaped signature
     * would be a different event with the same content.
     */
    public static boolean verify(String payload, String signatureB64, PublicKey publicKey) {
        try {
            byte[] message = payload.getBytes(StandardCharsets.UTF_8);
            byte[] signature = Base64.getDecoder().decode(signatureB64);
            if (publicKey instanceof Ed25519PublicKey) {
                if (signature.length != Ed25519.SIGNATURE_SIZE) return false;
                return Ed25519.verify(signature, 0, ((Ed25519PublicKey) publicKey).raw, 0,
                        message, 0, message.length);
            }
            Signature sig = Signature.getInstance(RSA_SIGNATURE);
            sig.initVerify(publicKey);
            sig.update(message);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;   // malformed signature, wrong key, whatever — all mean "no"
        }
    }

    // ─────────── encoding helpers ───────────

    public static String encodePublic(PublicKey key) {
        if (key instanceof Ed25519PublicKey) {
            return ED25519_PREFIX + Base64.getEncoder().encodeToString(((Ed25519PublicKey) key).raw);
        }
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey decodePublic(String encoded) throws GeneralSecurityException {
        if (encoded.startsWith(ED25519_PREFIX)) {
            return new Ed25519PublicKey(decodeEd25519Public(encoded));
        }
        byte[] bytes = Base64.getDecoder().decode(encoded);
        return KeyFactory.getInstance(RSA)
                .generatePublic(new X509EncodedKeySpec(bytes));
    }

    /**
     * The raw 32 bytes of an "ed25519:" public key, refused unless it is a valid point of the
     * right order.
     *
     * Checked when a key is read, not only when a signature is: a key that is not a proper
     * group element can make some signatures verify for more than one message, and a market
     * registers keys forever.
     */
    private static byte[] decodeEd25519Public(String encoded) throws GeneralSecurityException {
        if (!encoded.startsWith(ED25519_PREFIX)) {
            throw new InvalidKeyException("not an Ed25519 key");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encoded.substring(ED25519_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new InvalidKeyException("malformed Ed25519 key");
        }
        if (raw.length != Ed25519.PUBLIC_KEY_SIZE || !Ed25519.validatePublicKeyFull(raw, 0)) {
            throw new InvalidKeyException("invalid Ed25519 key");
        }
        return raw;
    }

    /**
     * An Ed25519 public key as a java.security.PublicKey, so everything that holds keys keeps
     * holding PublicKey and never needs to know there are two kinds.
     */
    static final class Ed25519PublicKey implements PublicKey {
        private static final long serialVersionUID = 1L;
        final byte[] raw;

        Ed25519PublicKey(byte[] raw) { this.raw = raw.clone(); }

        @Override public String getAlgorithm() { return "Ed25519"; }
        @Override public String getFormat()    { return "RAW"; }
        @Override public byte[] getEncoded()   { return raw.clone(); }

        @Override
        public boolean equals(Object o) {
            return o instanceof Ed25519PublicKey
                    && MessageDigest.isEqual(raw, ((Ed25519PublicKey) o).raw);
        }

        @Override public int hashCode() { return java.util.Arrays.hashCode(raw); }
    }

    public static void main(String[] args) throws Exception {
        Path keyFile = Paths.get("./test-identity.key");
        Files.deleteIfExists(keyFile);

        PlayerKeys keys = PlayerKeys.loadOrCreate(keyFile);
        String payload = "hello world";
        String sig = keys.sign(payload);

        System.out.println("algorithm: " + keys.algorithm());
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
    }
}
