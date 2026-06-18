package io.nxmatic.rke2lab.controlplane.incus;

/**
 * Reload policy for a provisioning target.
 *
 * <p>Categorises the target's nature with respect to its inputs: a {@link #STATIC} target consumes
 * its inputs once (typically at first boot), so any change requires recreating whatever holds the
 * inputs (the instance). A {@link #DYNAMIC} target keeps watching its inputs and re-applies on
 * change via its own reload protocol, so we don't need to recreate anything.
 *
 * <p>The policy also drives where the target's checksum is persisted: STATIC targets land in
 * instance config (Pulumi sees a {@code config.*} diff and replaces the instance), DYNAMIC targets
 * land in Pulumi outputs / a hot-reload ConfigMap.
 */
public enum TargetReloadPolicy {
  /**
   * Static target — consumes its inputs once and never re-reads.
   *
   * <p>Checksum stored in instance config. Changes trigger full instance renewal (delete +
   * recreate). Use for inputs that define instance identity, e.g. cloud-init seed data.
   */
  STATIC,

  /**
   * Dynamic target — keeps watching its inputs and re-applies on change.
   *
   * <p>Checksum stored in Pulumi outputs or a dynamic ConfigMap. Changes trigger reconciliation via
   * the workload's own reload protocol (systemd daemon-reload, rke2 manifest watch, DaemonSet
   * hot-reload sidecar) without instance renewal.
   */
  DYNAMIC
}
