package io.nxmatic.rke2lab.osgi.runtime.cellar.age;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.exceptionfactory.jagged.RecipientStanzaReader;
import com.exceptionfactory.jagged.RecipientStanzaWriter;
import com.exceptionfactory.jagged.x25519.X25519KeyPairGenerator;
import com.exceptionfactory.jagged.x25519.X25519RecipientStanzaReaderFactory;
import com.exceptionfactory.jagged.x25519.X25519RecipientStanzaWriterFactory;
import java.security.KeyPair;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The real age crypto core: {@link AgeCellarCipher#sealWith}/{@link AgeCellarCipher#revealWith}
 * over freshly minted X25519 keypairs, exercised WITHOUT the environment (the public {@link
 * AgeCellarCipher#seal}/{@link AgeCellarCipher#reveal} only add the env-sourced recipient/identity
 * on top). Proves the jagged wiring round-trips, that the ciphertext leaks no plaintext, that the
 * seal self-identifies, that a foreign identity fails closed, and that reveal passes an unmarked
 * value through untouched.
 */
class AgeCellarCipherTest {

  private static RecipientStanzaWriter writerFor(KeyPair keyPair) throws Exception {
    return X25519RecipientStanzaWriterFactory.newRecipientStanzaWriter(
        keyPair.getPublic().toString()); // the age1… recipient
  }

  private static RecipientStanzaReader readerFor(KeyPair keyPair) throws Exception {
    return X25519RecipientStanzaReaderFactory.newRecipientStanzaReader(
        keyPair.getPrivate().toString()); // the AGE-SECRET-KEY-1… identity
  }

  @Test
  void sealsToRecipientAndRevealsBackWithTheIdentity() throws Exception {
    final KeyPair keyPair = new X25519KeyPairGenerator().generateKeyPair();
    final String secret = "-----BEGIN KEY-----topsecret-github-token";

    final String sealed = AgeCellarCipher.sealWith(secret, List.of(writerFor(keyPair)));

    assertTrue(sealed.startsWith("cellar:age:v1:"), "the sealed payload self-identifies as age");
    assertFalse(sealed.contains("topsecret"), "the age binary leaks no plaintext");
    assertEquals(
        secret,
        AgeCellarCipher.revealWith(sealed, List.of(readerFor(keyPair))),
        "reveal recovers the exact plaintext with the matching identity");
  }

  @Test
  void sealsToMultipleRecipientsEachCanReveal() throws Exception {
    final KeyPair a = new X25519KeyPairGenerator().generateKeyPair();
    final KeyPair b = new X25519KeyPairGenerator().generateKeyPair();
    final String secret = "cluster-pki-admin-credentials";

    final String sealed = AgeCellarCipher.sealWith(secret, List.of(writerFor(a), writerFor(b)));

    assertEquals(secret, AgeCellarCipher.revealWith(sealed, List.of(readerFor(a))));
    assertEquals(secret, AgeCellarCipher.revealWith(sealed, List.of(readerFor(b))));
  }

  @Test
  void aForeignIdentityCannotReveal() throws Exception {
    final KeyPair addressed = new X25519KeyPairGenerator().generateKeyPair();
    final KeyPair stranger = new X25519KeyPairGenerator().generateKeyPair();

    final String sealed = AgeCellarCipher.sealWith("secret", List.of(writerFor(addressed)));

    assertThrows(
        IllegalStateException.class,
        () -> AgeCellarCipher.revealWith(sealed, List.of(readerFor(stranger))),
        "a recipient the seal did not address fails closed, not silently");
  }

  @Test
  void revealPassesAnUnmarkedValueThrough() {
    final AgeCellarCipher cipher = new AgeCellarCipher();
    assertEquals(
        "a-plain-value",
        cipher.reveal("a-plain-value"),
        "an unmarked payload (a PLAIN store) is returned verbatim — no env needed");
    assertNull(cipher.reveal(null), "a null payload passes through");
  }
}
