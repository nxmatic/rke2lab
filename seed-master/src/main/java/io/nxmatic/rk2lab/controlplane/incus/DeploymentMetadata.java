package io.nxmatic.rk2lab.controlplane.incus;

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
      return new GitMetadata(
          io.nxmatic.rk2lab.controlplane.incus.GitMetadata.currentBranch(),
          io.nxmatic.rk2lab.controlplane.incus.GitMetadata.currentCommitSha());
    }
  }
}
