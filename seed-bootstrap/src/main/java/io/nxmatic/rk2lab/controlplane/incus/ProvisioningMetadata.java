package io.nxmatic.rk2lab.controlplane.incus;

import java.util.Map;

/**
 * Provisioning metadata — what resources are being provisioned and where they reside.
 *
 * @param slices per-slice checksums for independently reconcilable components
 * @param paths filesystem paths for provisioning assets
 */
public record ProvisioningMetadata(Slices slices, Paths paths) {

  /**
   * Per-slice checksums for hot-reload reconciliation.
   *
   * <p>Core slice changes trigger full renewal; component slice changes trigger hot-reload.
   *
   * @param checksums map from slice name (core, floxRuntime, kdns, ...) to SHA-256 checksum
   */
  public record Slices(Map<String, String> checksums) {

    public static Slices of(Map<String, String> checksums) {
      return new Slices(Map.copyOf(checksums));
    }
  }

  /**
   * Filesystem paths for provisioning assets.
   *
   * @param hostSourceDirRelative relative path from worktree root to assets directory
   */
  public record Paths(String hostSourceDirRelative) {}
}
