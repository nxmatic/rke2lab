package io.nxmatic.rke2lab.bbox.contract;

/**
 * One desired DHCP reservation to reconcile, flat: the {@code (mac, ip, hostname)} triple the bbox
 * sees, plus the {@code (cluster, node)} identity rke2lab uses to name and correlate the row. The
 * home mirror of the host's {@code DesiredRow} + the library's {@code DesiredReservation}, carrying
 * no library type across the seam. The bbox-edge rebuilds the library reservation from the triple.
 */
public record BboxReservationRequest(
    String cluster, String node, String mac, String ip, String hostname) {}
