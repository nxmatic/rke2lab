package io.nxmatic.rke2lab.host.edge;

import io.nxmatic.rke2lab.host.port.SecretsSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.osgi.service.component.annotations.Component;

/**
 * The live {@link SecretsSource}: reads the plaintext bytes of {@code <worktreeRoot>/.secrets}. The
 * worktree copy is smudged plaintext by the {@code sops-yaml} filter during normal operation; this
 * edge moves the bytes only — the caller parses the YAML and applies the encrypted-at-rest and
 * key-shape diagnostics.
 */
@Component(service = SecretsSource.class)
public final class LiveSecretsSource implements SecretsSource {

  private static final String SECRETS_FILENAME = ".secrets";

  @Override
  public byte[] readSecrets(Path worktreeRoot) {
    final Path secretsPath = worktreeRoot.resolve(SECRETS_FILENAME);
    if (!Files.isReadable(secretsPath)) {
      throw new IllegalStateException(
          "Cannot read secrets file at "
              + secretsPath
              + " — is the sops-yaml smudge filter active?");
    }
    try {
      return Files.readAllBytes(secretsPath);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to read " + secretsPath, ex);
    }
  }
}
