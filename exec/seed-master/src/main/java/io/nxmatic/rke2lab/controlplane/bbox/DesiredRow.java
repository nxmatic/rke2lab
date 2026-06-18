package io.nxmatic.rke2lab.controlplane.bbox;

import io.nxmatic.bbox.reconcile.DesiredReservation;

/**
 * One desired RKE2 reservation, paired with the {@code (cluster, node)} identity rke2lab uses for
 * Pulumi resource naming.
 *
 * <p>The library's {@link DesiredReservation} is intentionally cluster/node-agnostic — it carries
 * only the MAC/IP/hostname triple the bbox sees. We wrap it here so the rest of rke2lab can pass
 * {@code (cluster, node)} alongside without losing typing.
 */
public record DesiredRow(String cluster, String node, DesiredReservation reservation) {

  /** Stable, concise resource name for Pulumi: {@code "{cluster}-{node}"}. */
  public String resourceName() {
    return cluster + "-" + node;
  }

  /** Convenience accessors; just unwrap the inner reservation. */
  public String mac() {
    return reservation.mac();
  }

  public String ip() {
    return reservation.ip();
  }

  public String hostname() {
    return reservation.hostname();
  }
}
