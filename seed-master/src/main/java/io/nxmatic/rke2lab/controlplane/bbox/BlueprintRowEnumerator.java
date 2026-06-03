package io.nxmatic.rke2lab.controlplane.bbox;

import io.nxmatic.bbox.reconcile.DesiredReservation;
import io.nxmatic.rke2lab.netplan.ClusterNetworkBlueprint;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Enumerates the canonical RKE2 rows from {@link ClusterNetworkBlueprint}.
 *
 * <p>Owns the canonical clusters/nodes lists and the MAC-prefix safety check. Knows nothing about
 * the bbox API — the bbox-side reconciliation comes from {@code java-bbox-api-client}'s {@code
 * ReservationReconciler}, which in turn knows nothing about the blueprint. The two sides meet
 * through {@link DesiredReservation}; rke2lab wraps each one in a {@link DesiredRow} so the {@code
 * (cluster, node)} pair is preserved for Pulumi resource naming.
 */
public final class BlueprintRowEnumerator {

  /**
   * MAC prefix the {@link ClusterNetworkBlueprint} assigns to RKE2 LAN interfaces: {@code
   * 10:66:6a:4c:{clusterId:02x}:{nodeId:02x}}. Used here as the ownership boundary so non-RKE2
   * reservations stay out of scope.
   */
  public static final String RKE2_LAN_MAC_PREFIX = "10:66:6a:4c:";

  /**
   * Canonical clusters reconciled by this provisioner — must match {@code BlueprintExportCommand}.
   */
  public static final List<String> CANONICAL_CLUSTERS = List.of("bioskop", "nikopol");

  /** Canonical node names within every cluster — must match {@code BlueprintExportCommand}. */
  public static final List<String> CANONICAL_NODES =
      List.of("master", "peer1", "peer2", "peer3", "worker1", "worker2");

  private final List<String> clusters;
  private final List<String> nodes;

  public BlueprintRowEnumerator() {
    this(CANONICAL_CLUSTERS, CANONICAL_NODES);
  }

  /** Visible for tests — pin the cluster / node lists explicitly. */
  public BlueprintRowEnumerator(List<String> clusters, List<String> nodes) {
    this.clusters = List.copyOf(clusters);
    this.nodes = List.copyOf(nodes);
  }

  /**
   * Materialise the cartesian product of clusters × nodes as {@link DesiredRow}s, deriving each row
   * from the blueprint and asserting the RKE2 MAC-prefix invariant.
   */
  public List<DesiredRow> rows() {
    final List<DesiredRow> out = new ArrayList<>(clusters.size() * nodes.size());
    for (String cluster : clusters) {
      for (String node : nodes) {
        final ClusterNetworkBlueprint bp =
            ClusterNetworkBlueprint.builder()
                .cluster(cluster)
                .node(node)
                .deriveRecipeModel()
                .build();
        final String mac = bp.lan().hostMacaddr().value();
        if (!mac.toLowerCase(Locale.ROOT).startsWith(RKE2_LAN_MAC_PREFIX)) {
          throw new IllegalStateException(
              "Blueprint produced non-RKE2 MAC for "
                  + cluster
                  + "/"
                  + node
                  + " ("
                  + mac
                  + "); reconciliation aborted to avoid scope creep.");
        }
        out.add(
            new DesiredRow(
                cluster,
                node,
                new DesiredReservation(
                    mac, bp.lan().hostInetaddr().getHostAddress(), cluster + "-" + node)));
      }
    }
    return List.copyOf(out);
  }
}
