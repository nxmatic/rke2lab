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
   * Per-slice checksums partitioned by storage policy.
   *
   * <p>Static slices stored in instance config trigger full renewal on change. Hot-reload slices
   * trigger reconciliation without instance renewal.
   *
   * @param staticSlices checksums for STATIC policy slices (stored in instance config)
   * @param hotReloadSlices checksums for HOT_RELOAD policy slices (stored in outputs/ConfigMap)
   */
  public record Slices(Map<String, String> staticSlices, Map<String, String> hotReloadSlices) {

    public static Slices of(Map<String, String> staticSlices, Map<String, String> hotReloadSlices) {
      return new Slices(Map.copyOf(staticSlices), Map.copyOf(hotReloadSlices));
    }

    /** All slice checksums regardless of policy. */
    public Map<String, String> all() {
      final var combined = new java.util.LinkedHashMap<String, String>();
      combined.putAll(staticSlices);
      combined.putAll(hotReloadSlices);
      return Map.copyOf(combined);
    }
  }

  /**
   * Filesystem paths for provisioning assets.
   *
   * @param hostSourceDirRelative relative path from worktree root to assets directory
   */
  public record Paths(String hostSourceDirRelative) {}
}
