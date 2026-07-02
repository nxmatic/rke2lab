package io.nxmatic.rke2lab.controlplane.incus;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Deployment metadata — when, how, and by what this deployment was triggered.
 *
 * @param git git context (branch, SHA)
 * @param timestamp deployment initiation time
 */
public record DeploymentMetadata(GitMetadata git, Instant timestamp) {

  public static DeploymentMetadata capture() {
    return new DeploymentMetadata(GitMetadata.capture(), Instant.now());
  }

  /** Git context at deployment time. */
  public record GitMetadata(String branch, String commitSha) {

    static GitMetadata capture() {
      final Path repoRoot = Path.of(System.getProperty("user.dir"));
      return GitMetadataExtractor.extract(repoRoot, false)
          .map(info -> new GitMetadata(info.branch(), info.commitFull()))
          .orElseGet(() -> new GitMetadata("unknown", "unknown"));
    }
  }
}
