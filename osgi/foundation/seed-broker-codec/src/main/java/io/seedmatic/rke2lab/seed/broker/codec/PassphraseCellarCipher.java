package io.seedmatic.rke2lab.seed.broker.codec;

import io.seedmatic.rke2lab.seed.broker.port.CellarCipher;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The degenerate single-local-recipient {@link CellarCipher}: a passphrase-derived AES-GCM seal —
 * the mono case of the cellar's clean/smudge filter, sufficient while the writer and the reader are
 * the same party (today's single live master, which runs on bioskop or nikopol). It holds NO
 * recipient set; the multi-recipient generalisation (age via {@code jagged}, a data key wrapped per
 * recipient) is a later impl of the same seam, proven at {@code bench-cellar}. Pure-Java, no CLI,
 * no {@code .sops.yaml} — the passphrase is the cellar's own (mono) identity config.
 *
 * <p>Self-identifying: {@link #seal} frames a random salt + IV with the ciphertext and prefixes
 * {@link #MARK}, so {@link #reveal} needs no external flag — a payload without the mark (a {@code
 * PLAIN} store, or an already-revealed value) is returned verbatim. AES-GCM is authenticated, so a
 * tampered or foreign payload fails closed rather than yielding garbage. The passphrase is a dummy
 * on the live master (the cellar holds nothing secret yet); it is the single-local-recipient
 * stand-in until the age impl lands. See docs/architecture/atlas/cellar-secrets.adoc.
 */
public final class PassphraseCellarCipher implements CellarCipher {

  // The mono passphrase — the single-local-recipient stand-in. A dummy on the live master: the
  // cellar holds nothing secret yet, and the writer reads its own seals. Replaced (not extended) by
  // the age/jagged recipient set when the cellar travels.
  private static final char[] PASSPHRASE = "rke2lab-cellar".toCharArray();

  private static final String MARK = "cellar:sealed:v1:";
  private static final int SALT_LEN = 16;
  private static final int IV_LEN = 12;
  private static final int TAG_BITS = 128;
  private static final int KEY_BITS = 256;
  private static final int PBKDF2_ITERATIONS = 200_000;

  private final SecureRandom random = new SecureRandom();

  @Override
  public String seal(String plaintext) {
    try {
      final byte[] salt = new byte[SALT_LEN];
      final byte[] iv = new byte[IV_LEN];
      random.nextBytes(salt);
      random.nextBytes(iv);
      final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, keyFrom(salt), new GCMParameterSpec(TAG_BITS, iv));
      final byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      final byte[] framed = new byte[SALT_LEN + IV_LEN + ciphertext.length];
      System.arraycopy(salt, 0, framed, 0, SALT_LEN);
      System.arraycopy(iv, 0, framed, SALT_LEN, IV_LEN);
      System.arraycopy(ciphertext, 0, framed, SALT_LEN + IV_LEN, ciphertext.length);
      return MARK + Base64.getEncoder().encodeToString(framed);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("cellar seal failed", e);
    }
  }

  @Override
  public String reveal(String payload) {
    if (payload == null || !payload.startsWith(MARK)) {
      return payload; // not sealed — a PLAIN store, or an already-clear value
    }
    try {
      final byte[] framed = Base64.getDecoder().decode(payload.substring(MARK.length()));
      final byte[] salt = Arrays.copyOfRange(framed, 0, SALT_LEN);
      final byte[] iv = Arrays.copyOfRange(framed, SALT_LEN, SALT_LEN + IV_LEN);
      final byte[] ciphertext = Arrays.copyOfRange(framed, SALT_LEN + IV_LEN, framed.length);
      final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, keyFrom(salt), new GCMParameterSpec(TAG_BITS, iv));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(
          "cellar reveal failed — wrong identity or tampered payload", e);
    }
  }

  private static SecretKey keyFrom(byte[] salt) throws GeneralSecurityException {
    final SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
    final KeySpec spec = new PBEKeySpec(PASSPHRASE, salt, PBKDF2_ITERATIONS, KEY_BITS);
    return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
  }
}
