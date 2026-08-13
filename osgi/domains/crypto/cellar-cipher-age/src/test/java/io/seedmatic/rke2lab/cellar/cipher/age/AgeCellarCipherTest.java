package io.seedmatic.rke2lab.cellar.cipher.age;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.exceptionfactory.jagged.RecipientStanzaReader;
import com.exceptionfactory.jagged.RecipientStanzaWriter;
import com.exceptionfactory.jagged.ssh.SshEd25519RecipientStanzaReaderFactory;
import com.exceptionfactory.jagged.ssh.SshEd25519RecipientStanzaWriterFactory;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real age crypto core over jagged's ssh-ed25519 recipient, with a throwaway OpenSSH keypair
 * ({@code ssh-keygen}) standing in for the {@code rke2-cluster} inventory key. Proves the
 * round-trip (seal to the ssh public, reveal with the ssh private), no plaintext leak,
 * self-identification, a foreign key failing closed, and — through a mocked {@link
 * NdhKeystoreReader} — the whole {@link AgeCellarCipher} component including the unmarked
 * pass-through that never touches the inventory.
 */
class AgeCellarCipherTest {

  @TempDir Path tmp;

  @Test
  void sealsToTheSshRecipientAndRevealsBackWithTheSshIdentity() throws Exception {
    final SshKeyPair kp = keyPairAt("a");
    final String secret = "-----BEGIN KEY-----topsecret-github-token";

    final String sealed = AgeCellarCipher.sealWith(secret, List.of(kp.writer()));

    assertTrue(sealed.startsWith("cellar:age:v1:"), "the sealed payload self-identifies as age");
    assertFalse(sealed.contains("topsecret"), "the age binary leaks no plaintext");
    assertEquals(
        secret,
        AgeCellarCipher.revealWith(sealed, List.of(kp.reader())),
        "reveal recovers the exact plaintext with the matching ssh identity");
  }

  @Test
  void aForeignSshIdentityCannotReveal() throws Exception {
    final SshKeyPair addressed = keyPairAt("addressed");
    final SshKeyPair stranger = keyPairAt("stranger");

    final String sealed = AgeCellarCipher.sealWith("secret", List.of(addressed.writer()));

    assertThrows(
        IllegalStateException.class,
        () -> AgeCellarCipher.revealWith(sealed, List.of(stranger.reader())),
        "a key the seal did not address fails closed, not silently");
  }

  @Test
  void theComponentSealsAndRevealsThroughTheNdhKeystore() throws Exception {
    final SshKeyPair kp = keyPairAt("component");
    final AgeCellarCipher cipher = new AgeCellarCipher(keystoreOf(kp));
    final String secret = "cluster-age-key-identity";

    final String sealed = cipher.seal(secret);

    assertTrue(sealed.startsWith("cellar:age:v1:"));
    assertFalse(sealed.contains(secret));
    assertEquals(secret, cipher.reveal(sealed), "the component round-trips via keys.rke2-cluster");
  }

  @Test
  void revealPassesAnUnmarkedValueThroughWithoutTouchingTheKeystore() {
    final AgeCellarCipher cipher = new AgeCellarCipher(untouchedKeystore());
    assertEquals(
        "a-plain-value",
        cipher.reveal("a-plain-value"),
        "an unmarked payload (a PLAIN store) is returned verbatim — the inventory is never read");
    assertNull(cipher.reveal(null), "a null payload passes through");
  }

  private SshKeyPair keyPairAt(String name) throws Exception {
    return SshKeyPair.generate(tmp.resolve(name));
  }

  /** A throwaway ed25519 keypair — OpenSSH private + {@code ssh-ed25519 AAAA…} public. */
  private record SshKeyPair(String publicKey, String privateKey) {

    static SshKeyPair generate(Path keyFile) throws Exception {
      final Process process;
      try {
        process =
            new ProcessBuilder(
                    "ssh-keygen",
                    "-t",
                    "ed25519",
                    "-N",
                    "",
                    "-C",
                    "cellar-test",
                    "-f",
                    keyFile.toString())
                .redirectErrorStream(true)
                .start();
      } catch (IOException unavailable) {
        abort("ssh-keygen unavailable: " + unavailable.getMessage());
        throw new AssertionError(unavailable); // unreachable — abort throws
      }
      assumeTrue(process.waitFor() == 0, "ssh-keygen must succeed to exercise the ssh path");
      return new SshKeyPair(Files.readString(Path.of(keyFile + ".pub")), Files.readString(keyFile));
    }

    RecipientStanzaWriter writer() throws Exception {
      return SshEd25519RecipientStanzaWriterFactory.newRecipientStanzaWriter(
          publicKey.getBytes(StandardCharsets.UTF_8));
    }

    RecipientStanzaReader reader() throws Exception {
      return SshEd25519RecipientStanzaReaderFactory.newRecipientStanzaReader(
          privateKey.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static NdhKeystoreReader keystoreOf(SshKeyPair kp) {
    return new NdhKeystoreReader() {
      @Override
      public boolean present() {
        return true;
      }

      @Override
      public String sshPublic(String keyName) {
        assertEquals(AgeCellarCipher.CLUSTER_KEY, keyName);
        return kp.publicKey();
      }

      @Override
      public String sshPrivate(String keyName) {
        assertEquals(AgeCellarCipher.CLUSTER_KEY, keyName);
        return kp.privateKey();
      }

      @Override
      public String authorityCert(String authority) {
        throw new UnsupportedOperationException();
      }

      @Override
      public String authorityDomain(String authority) {
        throw new UnsupportedOperationException();
      }

      @Override
      public String authorityPrivate(String authority) {
        throw new UnsupportedOperationException();
      }
    };
  }

  /** A keystore that fails the test if any accessor is touched — for the unmarked pass-through. */
  private static NdhKeystoreReader untouchedKeystore() {
    return new NdhKeystoreReader() {
      @Override
      public boolean present() {
        throw new AssertionError("the keystore must not be consulted for an unmarked payload");
      }

      @Override
      public String sshPublic(String keyName) {
        throw new AssertionError("the keystore must not be consulted for an unmarked payload");
      }

      @Override
      public String sshPrivate(String keyName) {
        throw new AssertionError("the keystore must not be consulted for an unmarked payload");
      }

      @Override
      public String authorityCert(String authority) {
        throw new AssertionError("unused");
      }

      @Override
      public String authorityDomain(String authority) {
        throw new AssertionError("unused");
      }

      @Override
      public String authorityPrivate(String authority) {
        throw new AssertionError("unused");
      }
    };
  }
}
