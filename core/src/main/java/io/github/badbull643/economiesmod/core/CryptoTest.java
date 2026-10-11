package io.github.badbull643.economiesmod.core;

import io.github.badbull643.economiesmod.core.crypto.bc.math.ec.rfc8032.Ed25519;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * The signing keys: the vendored Ed25519 is correct, and RSA identities still work beside it.
 *
 * Crypto that compiles is not crypto that works, so this checks the vendored Bouncy Castle code
 * against the RFC's own test vectors rather than against itself, and — on any JVM new enough to
 * have its own Ed25519 (Java 15 and up) — against the JDK's independent implementation in both
 * directions. Run it on Java 8 for the vectors and on a newer Java for the cross-check; it says
 * which it did.
 */
public class CryptoTest {

    private static int failures = 0;
    private static int checksRun = 0;

    private static void check(String label, boolean ok) {
        checksRun++;
        if (!ok) failures++;
        System.out.println((ok ? "    ok   " : "    FAIL ") + label);
    }

    /** RFC 8032 section 7.1, TEST 1 to 3: secret key, public key, message, signature. */
    private static final String[][] RFC8032 = {
        {"9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
         "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
         "",
         "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"},
        {"4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
         "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
         "72",
         "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"},
        {"c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
         "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
         "af82",
         "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"},
    };

    public static void main(String[] args) throws Exception {
        System.out.println("  running on Java " + System.getProperty("java.version"));

        System.out.println("  [C1: the vendored Ed25519 reproduces RFC 8032 exactly]");
        for (int i = 0; i < RFC8032.length; i++) {
            byte[] sk = hex(RFC8032[i][0]);
            byte[] expectedPk = hex(RFC8032[i][1]);
            byte[] msg = hex(RFC8032[i][2]);
            byte[] expectedSig = hex(RFC8032[i][3]);

            byte[] pk = new byte[32];
            Ed25519.generatePublicKey(sk, 0, pk, 0);
            check("TEST " + (i + 1) + ": public key", java.util.Arrays.equals(pk, expectedPk));

            byte[] sig = new byte[64];
            Ed25519.sign(sk, 0, msg, 0, msg.length, sig, 0);
            check("TEST " + (i + 1) + ": signature", java.util.Arrays.equals(sig, expectedSig));
            check("TEST " + (i + 1) + ": verifies",
                    Ed25519.verify(expectedSig, 0, expectedPk, 0, msg, 0, msg.length));
        }

        System.out.println("  [C2: a signature cannot be re-shaped into a second valid one]");
        {
            // S + L is the classic malleation: it is congruent to S, so a lax verifier accepts
            // it, and it is a different string, so it would be a different event in the chain.
            byte[] pk = hex(RFC8032[1][1]);
            byte[] msg = hex(RFC8032[1][2]);
            byte[] sig = hex(RFC8032[1][3]);
            BigInteger l = BigInteger.ONE.shiftLeft(252)
                    .add(new BigInteger("27742317777372353535851937790883648493"));
            byte[] sLe = java.util.Arrays.copyOfRange(sig, 32, 64);
            BigInteger s = new BigInteger(1, reverse(sLe));
            byte[] malleated = sig.clone();
            System.arraycopy(reverse(toFixed32(s.add(l))), 0, malleated, 32, 32);
            check("S + L is refused", !Ed25519.verify(malleated, 0, pk, 0, msg, 0, msg.length));
            byte[] flipped = sig.clone();
            flipped[5] ^= 1;
            check("a flipped bit is refused", !Ed25519.verify(flipped, 0, pk, 0, msg, 0, msg.length));
        }

        System.out.println("  [C3: PlayerKeys, both algorithms]");
        {
            PlayerKeys ed = PlayerKeys.generate();
            check("new identities are Ed25519", ed.algorithm() == PlayerKeys.Algorithm.ED25519);
            check("an Ed25519 key says so in its string",
                    ed.publicKeyString().startsWith(PlayerKeys.ED25519_PREFIX));
            String sig = ed.sign("payload");
            check("an Ed25519 signature is 88 characters", sig.length() == 88);
            PublicKey decoded = PlayerKeys.decodePublic(ed.publicKeyString());
            check("its key round-trips and verifies", PlayerKeys.verify("payload", sig, decoded));
            check("a different payload does not", !PlayerKeys.verify("payloaD", sig, decoded));

            PlayerKeys rsa = PlayerKeys.generate(PlayerKeys.Algorithm.RSA);
            String rsaSig = rsa.sign("payload");
            check("RSA keys still sign and verify",
                    PlayerKeys.verify("payload", rsaSig, PlayerKeys.decodePublic(rsa.publicKeyString())));
            check("an RSA key carries no prefix", !rsa.publicKeyString().startsWith("ed25519:"));
            check("an RSA signature does not verify under an Ed25519 key",
                    !PlayerKeys.verify("payload", rsaSig, decoded));
            check("nor the other way round",
                    !PlayerKeys.verify("payload", sig, PlayerKeys.decodePublic(rsa.publicKeyString())));
        }

        System.out.println("  [C4: keys that are not keys are refused when read]");
        {
            byte[] notAPoint = new byte[32];
            java.util.Arrays.fill(notAPoint, (byte) 0xFF);
            check("an off-curve key", refused(PlayerKeys.ED25519_PREFIX
                    + Base64.getEncoder().encodeToString(notAPoint)));
            check("a short key", refused(PlayerKeys.ED25519_PREFIX
                    + Base64.getEncoder().encodeToString(new byte[31])));
            check("garbage after the prefix", refused(PlayerKeys.ED25519_PREFIX + "%%%"));
            // The identity point (y = 1) is a valid encoding of small order; a key like that
            // would let signatures verify for messages its holder never signed.
            byte[] identity = new byte[32];
            identity[0] = 1;
            check("a small-order key", refused(PlayerKeys.ED25519_PREFIX
                    + Base64.getEncoder().encodeToString(identity)));
        }

        System.out.println("  [C5: key files, both algorithms, survive a save and a load]");
        {
            Path dir = Paths.get("build", "test-scratch");
            Files.createDirectories(dir);
            for (PlayerKeys.Algorithm a : PlayerKeys.Algorithm.values()) {
                Path f = dir.resolve("crypto-" + a + ".key");
                Files.deleteIfExists(f);
                PlayerKeys k = PlayerKeys.generate(a);
                k.saveTo(f);
                PlayerKeys back = PlayerKeys.load(f);
                check(a + " reloads as the same identity",
                        back.publicKeyString().equals(k.publicKeyString())
                                && back.algorithm() == a);
                check(a + " reloaded key signs for the original",
                        PlayerKeys.verify("x", back.sign("x"), k.publicKey()));
            }
            // A file whose public line belongs to a different key is refused, not believed.
            Path bad = dir.resolve("crypto-mismatched.key");
            PlayerKeys a = PlayerKeys.generate(), b = PlayerKeys.generate();
            Path af = dir.resolve("crypto-a.key");
            Files.deleteIfExists(af);
            a.saveTo(af);
            String secretLine = new String(Files.readAllBytes(af), StandardCharsets.UTF_8).split("\n")[0];
            Files.write(bad, (secretLine + "\n" + b.publicKeyString() + "\n").getBytes(StandardCharsets.UTF_8));
            boolean refusedMismatch;
            try { PlayerKeys.load(bad); refusedMismatch = false; } catch (Exception e) { refusedMismatch = true; }
            check("a key file whose halves disagree is refused", refusedMismatch);
        }

        System.out.println("  [C6: agrees with the JDK's own Ed25519, both ways]");
        if (!jdkHasEd25519()) {
            System.out.println("    (skipped: Java " + System.getProperty("java.version")
                    + " has no Ed25519 — run this suite on Java 15+ for the cross-check)");
        } else {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            for (int i = 0; i < RFC8032.length; i++) {
                byte[] sk = hex(RFC8032[i][0]);
                byte[] pk = hex(RFC8032[i][1]);
                byte[] msg = hex(RFC8032[i][2]);
                PublicKey jdkPub = kf.generatePublic(new X509EncodedKeySpec(
                        concat(hex("302a300506032b6570032100"), pk)));
                PrivateKey jdkPriv = kf.generatePrivate(new PKCS8EncodedKeySpec(
                        concat(hex("302e020100300506032b657004220420"), sk)));

                byte[] ours = new byte[64];
                Ed25519.sign(sk, 0, msg, 0, msg.length, ours, 0);
                Signature v = Signature.getInstance("Ed25519");
                v.initVerify(jdkPub);
                v.update(msg);
                check("TEST " + (i + 1) + ": the JDK verifies our signature", v.verify(ours));

                Signature s = Signature.getInstance("Ed25519");
                s.initSign(jdkPriv);
                s.update(msg);
                byte[] theirs = s.sign();
                check("TEST " + (i + 1) + ": we verify the JDK's signature",
                        Ed25519.verify(theirs, 0, pk, 0, msg, 0, msg.length));
                check("TEST " + (i + 1) + ": and they are byte-identical",
                        java.util.Arrays.equals(ours, theirs));
            }
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL " + checksRun + " CHECKS PASSED");
        } else {
            System.out.println(failures + " of " + checksRun + " checks FAILED");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static boolean refused(String key) {
        try {
            PlayerKeys.decodePublic(key);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean jdkHasEd25519() {
        try {
            KeyFactory.getInstance("Ed25519");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] reverse(byte[] b) {
        byte[] r = new byte[b.length];
        for (int i = 0; i < b.length; i++) r[i] = b[b.length - 1 - i];
        return r;
    }

    /** A non-negative BigInteger as exactly 32 big-endian bytes. */
    private static byte[] toFixed32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
