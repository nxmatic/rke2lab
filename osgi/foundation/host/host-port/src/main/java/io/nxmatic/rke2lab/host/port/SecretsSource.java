package io.nxmatic.rke2lab.host.port;

import java.nio.file.Path;

/**
 * The host-access seam for the worktree {@code .secrets} bytes. The rke2lab repo declares a {@code
 * filter=sops-yaml} smudge filter, so the worktree copy of {@code .secrets} is plaintext during
 * normal operation; this seam yields those raw bytes. The {@code host-edge} provides it by reading
 * the file; the CALLER parses and validates (the YAML key traversal, the {@code sops:}
 * encrypted-at-rest guard, the key-path lookups) — the seam moves bytes, the domain gives them
 * meaning.
 */
public interface SecretsSource {

  /**
   * The plaintext bytes of {@code <worktreeRoot>/.secrets}.
   *
   * @throws IllegalStateException if the file is missing or unreadable — the caller adds the
   *     encrypted-at-rest and key-shape diagnostics once it has parsed the bytes.
   */
  byte[] readSecrets(Path worktreeRoot);
}
