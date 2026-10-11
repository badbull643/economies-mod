/**
 * Ed25519 from Bouncy Castle 1.86 (bcprov-jdk18on), vendored: the source of the seventeen
 * classes Ed25519 actually needs, copied in under this package.
 *
 * Why vendored rather than a dependency. Java 8 has no Ed25519, and the server jar and the
 * 1.16.5 mod run on Java 8. A library dependency would have to be bundled into the server jar
 * and into every one of the version folders' mod jars, and a mod that ships org.bouncycastle
 * classes can collide with another mod shipping a different Bouncy Castle. Source compiled into
 * our own package does neither: every build already compiles core, so every build gets this.
 *
 * What was copied, unchanged except for the package and import lines: crypto/Digest,
 * math/ec/rfc7748/X25519 and X25519Field, math/ec/rfc8032/Codec, Ed25519, Scalar25519,
 * ScalarUtil and Wnaf, math/raw/Bits, Interleave, Mod, Nat and Nat256, util/Arrays, Integers,
 * Objects and Pack. The set is the class closure of Ed25519 computed from the 1.86 jar.
 *
 * The one change: Ed25519.createDigest constructs JdkSha512Digest (the JDK's SHA-512, written
 * for this project) instead of BC's SHA512Digest, whose service registry would otherwise drag in
 * about 190 further classes. Marked with a comment at that line.
 *
 * Licence: MIT, Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc. The full text is in
 * licenses/bouncycastle-LICENSE.txt and travels in every jar that contains these classes.
 *
 * To update: re-copy the same files from a newer bcprov-jdk18on sources jar, re-apply the
 * package rename and the createDigest change, and run the Ed25519 checks in MarketTests.
 */
package io.github.badbull643.economiesmod.core.crypto.bc;
