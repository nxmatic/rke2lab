package io.seedmatic.rke2lab.bbox.core;

import io.seedmatic.rke2lab.bbox.contract.BboxReservationRequest;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Enumerates the canonical RKE2 reservation requests from {@link ClusterNetworkBlueprint}.
 *
 * <p>Owns the canonical clusters/nodes lists and the MAC-prefix safety check. Knows nothing about
 * the bbox API — it produces flat {@link BboxReservationRequest}s (the bbox-record vocabulary)
 * carrying the {@code (cluster, node)} identity alongside the MAC/IP/hostname triple; the bbox-edge
 * turns each into the library's reservation behind the contact. Pure (bbox-record + netplan-port,
 * no Pulumi), it is the bbox scion's collaborator that is not a resolved service — so it lives here
 * in the domain core, wired bundle-to-bundle to the scion that drives it.
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

  /**
   * Canonical node names within every cluster — the netplan domain owns the topology, so this reads
   * its single source rather than restating the list.
   */
  public static final List<String> CANONICAL_NODES = ClusterNetworkBlueprint.CANONICAL_NODE_NAMES;

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
   * Materialise the cartesian product of clusters × nodes as {@link BboxReservationRequest}s,
   * deriving each from the blueprint and asserting the RKE2 MAC-prefix invariant.
   */
  public List<BboxReservationRequest> rows() {
    final List<BboxReservationRequest> out = new ArrayList<>(clusters.size() * nodes.size());
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
            new BboxReservationRequest(
                cluster,
                node,
                mac,
                bp.lan().hostInetaddr().getHostAddress(),
                cluster + "-" + node));
      }
    }
    return List.copyOf(out);
  }
}
