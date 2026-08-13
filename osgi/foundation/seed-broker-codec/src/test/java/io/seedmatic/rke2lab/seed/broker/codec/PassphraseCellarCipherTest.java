package io.seedmatic.rke2lab.seed.broker.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.seedmatic.rke2lab.seed.broker.port.CellarCipher;
import org.junit.jupiter.api.Test;

/**
 * The degenerate single-recipient cipher: seal hides the plaintext, reveal is its inverse, reveal
 * is self-identifying (a non-sealed payload passes through untouched — how a {@code PLAIN} store
 * round-trips), and a tampered seal fails closed (AES-GCM authentication).
 */
class PassphraseCellarCipherTest {

  private static final String PLAINTEXT = "{\"clientKey\":\"-----BEGIN KEY-----secret\"}";

  @Test
  void sealHidesThePlaintextAndRevealInverts() {
    final CellarCipher cipher = new PassphraseCellarCipher();
    final String sealed = cipher.seal(PLAINTEXT);

    assertFalse(sealed.contains("clientKey"), "the sealed form must not leak the plaintext");
    assertFalse(sealed.contains("secret"), "the sealed form must not leak the plaintext");
    assertEquals(PLAINTEXT, cipher.reveal(sealed), "reveal is the inverse of seal");
  }

  @Test
  void sealIsNonDeterministic() {
    final CellarCipher cipher = new PassphraseCellarCipher();
    // A fresh salt + IV per seal, so the same plaintext seals to distinct ciphertexts (no ECB-style
    // equality leak), yet both reveal back to the original.
    final String a = cipher.seal(PLAINTEXT);
    final String b = cipher.seal(PLAINTEXT);

    assertFalse(a.equals(b), "two seals of the same plaintext must differ");
    assertEquals(PLAINTEXT, cipher.reveal(a));
    assertEquals(PLAINTEXT, cipher.reveal(b));
  }

  @Test
  void revealPassesThroughANonSealedPayload() {
    // Self-identifying: reveal is applied to every fetched payload, so a PLAIN store (never sealed)
    // must round-trip verbatim — no external flag tells sealed from clear.
    final CellarCipher cipher = new PassphraseCellarCipher();
    final String plain = "{\"reservations\":3}";

    assertEquals(plain, cipher.reveal(plain), "a non-sealed payload passes through untouched");
  }

  @Test
  void aTamperedSealFailsClosed() {
    // AES-GCM is authenticated: flipping a ciphertext byte must throw, not yield garbage plaintext.
    final CellarCipher cipher = new PassphraseCellarCipher();
    final String sealed = cipher.seal(PLAINTEXT);
    final String tampered = sealed.substring(0, sealed.length() - 2) + "AA";

    assertThrows(IllegalStateException.class, () -> cipher.reveal(tampered));
  }
}
