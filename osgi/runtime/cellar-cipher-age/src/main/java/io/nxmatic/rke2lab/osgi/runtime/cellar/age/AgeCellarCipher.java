package io.nxmatic.rke2lab.osgi.runtime.cellar.age;

import com.exceptionfactory.jagged.RecipientStanzaReader;
import com.exceptionfactory.jagged.RecipientStanzaWriter;
import com.exceptionfactory.jagged.framework.stream.StandardDecryptingChannelFactory;
import com.exceptionfactory.jagged.framework.stream.StandardEncryptingChannelFactory;
import com.exceptionfactory.jagged.x25519.X25519RecipientStanzaReaderFactory;
import com.exceptionfactory.jagged.x25519.X25519RecipientStanzaWriterFactory;
import io.nxmatic.rke2lab.seed.broker.port.CellarCipher;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.osgi.service.component.annotations.Component;

/**
 * The age realisation of {@link CellarCipher} — the multi-recipient generalisation the passphrase
 * stand-in named: an age (X25519 + ChaCha20-Poly1305) seal over the JDK JCA, via {@code jagged}
 * (through {@code jagged-wrap}). A cellar SEALED entry is encrypted to a SET of age recipients (the
 * sops key-slot shape: the file key wrapped once per recipient), so a harvest a seeding party
 * writes can be revealed by any recipient it addressed — the seeding-cluster → seeded-cluster
 * relation the seam describes, now real.
 *
 * <p>Its identity/recipient config is the CELLAR's own, taken from the environment — NOT {@code
 * .sops.yaml} (the cellar's recipients are a distinct set). It reveals with the operator age
 * identity in {@code SOPS_AGE_KEY} (the same root of trust that signs commits and reveals sops —
 * {@code ssh-to-age} of the {@code github-signing} key, provided by the keyhole flox env), and
 * seals to the age recipients in {@code RKE2LAB_CELLAR_RECIPIENTS} (comma/space separated {@code
 * age1…}).
 *
 * <p><b>Lazy and fail-loud.</b> The environment is read only when a seal/reveal actually needs it,
 * never at activation — so a PLAIN cellar (nothing sealed) never touches the env, and the component
 * activates unconditionally. When a seal is asked with no recipients configured, or a marked
 * payload must be revealed with no identity, it throws rather than degrade to plaintext: enforcing
 * the foundation is this cipher's whole job. An error naming a recipient may print it (a public
 * {@code age1…} key); an error about the identity NEVER prints its value (it is the private key).
 *
 * <p><b>Self-identifying</b>, like the passphrase impl: {@link #seal} base64-frames the age binary
 * and prefixes {@link #MARK} (distinct from the passphrase mark), so {@link #reveal} needs no
 * external flag — a payload without the mark (a {@code PLAIN} store, or an already-clear value) is
 * returned verbatim. The two ciphers must not be swapped mid-cellar (a value sealed by one is
 * opaque to the other): the active cipher is the cellar's for its whole life, an operational
 * invariant.
 *
 * <p>Published as an {@code immediate} {@code @Component} of the {@link CellarCipher} seam:
 * immediate so it stays active for the bundle's whole life regardless of use-count — a consumer
 * that resolves it by get/unget (the {@code ScenarioCellarExtension}) always sees a live instance,
 * never a deactivated delayed component. Where this bundle is provisioned it wins over the built-in
 * passphrase stand-in; where it is absent, the passphrase stays. See
 * docs/architecture/atlas/cellar-secrets.adoc.
 */
@Component(service = CellarCipher.class, immediate = true)
public final class AgeCellarCipher implements CellarCipher {

  /** The operator age identity ({@code AGE-SECRET-KEY-1…}); reveal reads it, never prints it. */
  static final String IDENTITY_ENV = "SOPS_AGE_KEY";

  /** The cellar's age recipients ({@code age1…}, comma/space separated); seal reads them. */
  static final String RECIPIENTS_ENV = "RKE2LAB_CELLAR_RECIPIENTS";

  private static final String MARK = "cellar:age:v1:";

  @Override
  public String seal(String plaintext) {
    return sealWith(plaintext, recipientsFromEnv());
  }

  @Override
  public String reveal(String payload) {
    if (payload == null || !payload.startsWith(MARK)) {
      return payload; // not age-sealed — a PLAIN store, or an already-clear value
    }
    return revealWith(payload, identitiesFromEnv());
  }

  /**
   * The crypto core of {@link #seal}, decoupled from the env-sourced recipients so it is directly
   * testable with a freshly minted keypair: age-encrypt {@code plaintext} to {@code writers}, then
   * base64-frame the binary under {@link #MARK}.
   */
  static String sealWith(String plaintext, List<RecipientStanzaWriter> writers) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (WritableByteChannel channel =
        new StandardEncryptingChannelFactory()
            .newEncryptingChannel(Channels.newChannel(out), writers)) {
      channel.write(ByteBuffer.wrap(plaintext.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalStateException("cellar age seal failed", e);
    }
    return MARK + Base64.getEncoder().encodeToString(out.toByteArray());
  }

  /**
   * The crypto core of {@link #reveal}, decoupled from the env-sourced identity: strip {@link
   * #MARK}, base64-decode, and age-decrypt with {@code readers}. {@code payload} must carry the
   * mark (the public {@link #reveal} handles the pass-through of an unmarked value).
   */
  static String revealWith(String payload, List<RecipientStanzaReader> readers) {
    final byte[] ciphertext = Base64.getDecoder().decode(payload.substring(MARK.length()));
    try (ReadableByteChannel channel =
        new StandardDecryptingChannelFactory()
            .newDecryptingChannel(
                Channels.newChannel(new ByteArrayInputStream(ciphertext)), readers)) {
      return new String(Channels.newInputStream(channel).readAllBytes(), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalStateException(
          "cellar age reveal failed — wrong identity or tampered payload", e);
    }
  }

  private static List<RecipientStanzaWriter> recipientsFromEnv() {
    final String raw = System.getenv(RECIPIENTS_ENV);
    if (raw == null || raw.isBlank()) {
      throw new IllegalStateException(
          RECIPIENTS_ENV + " is not set — the age cellar cipher has no recipient to seal to");
    }
    final List<RecipientStanzaWriter> writers = new ArrayList<>();
    for (String recipient : raw.split("[,\\s]+")) {
      if (recipient.isBlank()) {
        continue;
      }
      try {
        writers.add(X25519RecipientStanzaWriterFactory.newRecipientStanzaWriter(recipient.trim()));
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException(
            "invalid age recipient in " + RECIPIENTS_ENV + ": " + recipient, e);
      }
    }
    if (writers.isEmpty()) {
      throw new IllegalStateException(
          RECIPIENTS_ENV + " holds no usable age recipient — nothing to seal to");
    }
    return writers;
  }

  private static List<RecipientStanzaReader> identitiesFromEnv() {
    final String raw = System.getenv(IDENTITY_ENV);
    if (raw == null || raw.isBlank()) {
      throw new IllegalStateException(
          IDENTITY_ENV + " is not set — the age cellar cipher has no identity to reveal with");
    }
    final List<RecipientStanzaReader> readers = new ArrayList<>();
    for (String identity : raw.split("\\R")) {
      if (identity.isBlank() || identity.startsWith("#")) {
        continue; // an age-key file carries "# public key:" comment lines
      }
      try {
        readers.add(X25519RecipientStanzaReaderFactory.newRecipientStanzaReader(identity.trim()));
      } catch (GeneralSecurityException e) {
        // NEVER echo the value — it is the private identity.
        throw new IllegalStateException("invalid age identity in " + IDENTITY_ENV, e);
      }
    }
    if (readers.isEmpty()) {
      throw new IllegalStateException(
          IDENTITY_ENV + " holds no usable age identity — nothing to reveal with");
    }
    return readers;
  }
}
