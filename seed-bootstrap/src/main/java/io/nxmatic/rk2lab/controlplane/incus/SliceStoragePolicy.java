package io.nxmatic.rk2lab.controlplane.incus;

/**
 * Storage policy for provisioning slices.
 *
 * <p>Determines where slice checksums are persisted and what triggers when they change.
 */
public enum SliceStoragePolicy {
  /**
   * Static slice — checksum stored in instance config.
   *
   * <p>Changes trigger full instance renewal (delete + recreate).
   */
  STATIC,

  /**
   * Hot-reload slice — checksum stored in Pulumi outputs or dynamic ConfigMap.
   *
   * <p>Changes trigger reconciliation via DaemonSet sidecar, no instance renewal.
   */
  HOT_RELOAD
}
