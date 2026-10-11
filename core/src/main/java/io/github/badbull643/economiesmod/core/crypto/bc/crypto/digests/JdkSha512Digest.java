package io.github.badbull643.economiesmod.core.crypto.bc.crypto.digests;

import io.github.badbull643.economiesmod.core.crypto.bc.crypto.Digest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-512 for the vendored Ed25519, from the JDK rather than from Bouncy Castle.
 *
 * Not part of Bouncy Castle: the one class in this tree written for this project. BC's own
 * SHA512Digest pulls in its service registry and, through that, ASN.1 and generic curve maths —
 * about 190 classes — to compute a hash every Java 8 runtime already has. This adapter is the
 * whole of the change made to the vendored code: Ed25519.createDigest constructs this instead.
 *
 * SHA-512 is required by the Java specification on every platform, so getInstance never fails
 * on a conforming runtime; the exception is there to say so loudly if one ever does.
 */
public final class JdkSha512Digest implements Digest {

    private final MessageDigest md;

    public JdkSha512Digest() {
        try {
            md = MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this Java runtime has no SHA-512", e);
        }
    }

    @Override public String getAlgorithmName() { return "SHA-512"; }

    @Override public int getDigestSize() { return 64; }

    @Override public void update(byte in) { md.update(in); }

    @Override public void update(byte[] in, int inOff, int len) { md.update(in, inOff, len); }

    @Override
    public int doFinal(byte[] out, int outOff) {
        byte[] digest = md.digest();   // also resets, as BC's doFinal does
        System.arraycopy(digest, 0, out, outOff, digest.length);
        return digest.length;
    }

    @Override public void reset() { md.reset(); }
}
