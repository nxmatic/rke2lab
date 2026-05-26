package io.nxmatic.rk2lab.controlplane.incus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Self-contained provisioning slice component.
 *
 * <p>Each slice knows:
 *
 * <ul>
 *   <li>What to materialize (systemd, manifests, node runtime)
 *   <li>Its storage policy (STATIC → instance config, HOT_RELOAD → reconciler)
 *   <li>Its materialized paths (for checksum computation)
 * </ul>
 *
 * <p>Execution domains:
 *
 * <ul>
 *   <li><b>infrastructure</b>: Node infrastructure (systemd, cloud-init, RKE2 bootstrap) - STATIC
 *   <li><b>k8s</b>: Kubernetes API resources (manifests) - HOT_RELOAD
 *   <li><b>node</b>: Node runtime (NRI plugin, binaries) - HOT_RELOAD
 * </ul>
 */
public interface ProvisioningSlice {

  /** Unique slice identifier (e.g., "infrastructure", "k8s", "node"). */
  String name();

  /** Storage policy determines lifecycle behavior. */
  SliceStoragePolicy storagePolicy();

  /**
   * Materialize this slice's resources to filesystem.
   *
   * @param paths bootstrap filesystem paths
   * @throws IOException if materialization fails
   */
  void materialize(IncusResourceBootstrap.BootstrapPaths paths) throws IOException;

  /**
   * Paths materialized by this slice for checksum computation.
   *
   * @return immutable list of filesystem paths
   */
  List<Path> getMaterializedPaths();
}
