package io.nxmatic.rke2lab.controlplane.incus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provisioning metadata — what is being provisioned and where its inputs reside.
 *
 * @param targets per-target checksums for independently reconcilable downstream consumers
 * @param paths filesystem paths for provisioning assets
 */
public record ProvisioningMetadata(Targets targets, Paths paths) {

  /**
   * Per-target checksums partitioned by reload policy.
   *
   * <p>Static targets stored in instance config trigger full renewal on change. Dynamic targets
   * trigger reconciliation without instance renewal.
   *
   * @param staticTargets checksums for {@link TargetReloadPolicy#STATIC} targets (stored in
   *     instance config)
   * @param dynamicTargets checksums for {@link TargetReloadPolicy#DYNAMIC} targets (stored in
   *     outputs/ConfigMap)
   */
  public record Targets(Map<String, String> staticTargets, Map<String, String> dynamicTargets) {

    public static Targets of(
        Map<String, String> staticTargets, Map<String, String> dynamicTargets) {
      return new Targets(Map.copyOf(staticTargets), Map.copyOf(dynamicTargets));
    }

    /** All target checksums regardless of policy. */
    public Map<String, String> all() {
      final var combined = new LinkedHashMap<String, String>();
      combined.putAll(staticTargets);
      combined.putAll(dynamicTargets);
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
