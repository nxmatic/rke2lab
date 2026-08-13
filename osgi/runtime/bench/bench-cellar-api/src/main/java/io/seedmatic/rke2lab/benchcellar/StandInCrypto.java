package io.seedmatic.rke2lab.benchcellar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The bench's faithful crypto stand-in. The key-slot MECHANISM is real AES-GCM — a fresh data key
 * encrypts the payload once, and is itself wrapped once per recipient — so the multi-recipient
 * property the bench proves is genuine. The ONLY thing faked is the asymmetry: a recipient's key is
 * derived symmetrically from its id ({@link #keyOf(String)}), where real age would wrap the data
 * key to a public key that only the matching private key unwraps. That fake is deliberate and
 * scoped to the bench; the production {@code CellarCipher} generalises the passphrase to age/jagged
 * with the same slot shape.
 */
public final class StandInCrypto {

  private static final int GCM_TAG_BITS = 128;
  private static final int IV_BYTES = 12;
  private static final int DATA_KEY_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();

  private StandInCrypto() {}

  /** A fresh data key — the per-seal symmetric key the payload is encrypted under. */
  public static byte[] freshDataKey() {
    final byte[] key = new byte[DATA_KEY_BYTES];
    RANDOM.nextBytes(key);
    return key;
  }

  /** The symmetric key of an identity — the stand-in for its age key pair. */
  public static byte[] keyOf(String id) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** Wrap the data key under an identity's key — one recipient's slot. */
  public static byte[] wrap(byte[] dataKey, byte[] identityKey) {
    return gcm(Cipher.ENCRYPT_MODE, identityKey, dataKey);
  }

  /** Unwrap a slot back to the data key with the same identity key. */
  public static byte[] unwrap(byte[] slot, byte[] identityKey) {
    return gcm(Cipher.DECRYPT_MODE, identityKey, slot);
  }

  /** Encrypt the payload under the fresh data key. */
  public static byte[] encrypt(String plaintext, byte[] dataKey) {
    return gcm(Cipher.ENCRYPT_MODE, dataKey, plaintext.getBytes(StandardCharsets.UTF_8));
  }

  /** Decrypt the payload with the recovered data key. */
  public static String decrypt(byte[] ciphertext, byte[] dataKey) {
    return new String(gcm(Cipher.DECRYPT_MODE, dataKey, ciphertext), StandardCharsets.UTF_8);
  }

  private static byte[] gcm(int mode, byte[] key, byte[] input) {
    try {
      final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      final SecretKeySpec spec = new SecretKeySpec(key, "AES");
      if (mode == Cipher.ENCRYPT_MODE) {
        final byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        cipher.init(mode, spec, new GCMParameterSpec(GCM_TAG_BITS, iv));
        final byte[] body = cipher.doFinal(input);
        final byte[] out = new byte[IV_BYTES + body.length];
        System.arraycopy(iv, 0, out, 0, IV_BYTES);
        System.arraycopy(body, 0, out, IV_BYTES, body.length);
        return out;
      }
      cipher.init(mode, spec, new GCMParameterSpec(GCM_TAG_BITS, input, 0, IV_BYTES));
      return cipher.doFinal(input, IV_BYTES, input.length - IV_BYTES);
    } catch (Exception e) {
      throw new IllegalStateException("AES-GCM stand-in failed", e);
    }
  }
}
