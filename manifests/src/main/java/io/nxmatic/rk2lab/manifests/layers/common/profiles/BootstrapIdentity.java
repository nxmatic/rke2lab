// @codebase
package io.nxmatic.rk2lab.manifests.layers.common.profiles;

/**
 * Cross-cutting identity slice published to synth-time layers via {@link
 * io.nxmatic.rk2lab.manifests.layers.common.ManifestSynthesisContext}. Replaces the kpt-setter
 * {@code ${cluster-name}} / {@code ${cluster-env}} / {@code ${node-name}} placeholders the
 * deprecated branch carried through {@code apply-setters} — those values now resolve at synth time
 * because the rendered objects ship straight to the rke2 addon controller (no kpt round trip).
 *
 * <p>Mirrors the canonical accessors on {@link
 * io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContext} (which env contributors already use), but
 * deliberately exposes only the identity subset — bootstrap paths and per-package config stay in
 * their owning layers.
 *
 * <p>Add new fields here as Stage B / multi-cluster work surfaces them (cluster fqdn, region, peer
 * cluster set, etc.). The default instance backs unit tests and ephemeral synth runs that don't go
 * through seed-bootstrap.
 */
public record BootstrapIdentity(
    String clusterName,
    int clusterId,
    String clusterToken,
    String clusterDomain,
    String clusterEnv,
    String nodeName,
    int nodeId,
    String nodeKind) {

  /** Sentinel used when seed-bootstrap hasn't supplied identity (tests, ephemeral runs). */
  public static final String UNKNOWN = "unknown";

  private static final BootstrapIdentity DEFAULT =
      new BootstrapIdentity(UNKNOWN, 0, UNKNOWN, "cluster.local", UNKNOWN, UNKNOWN, 0, UNKNOWN);

  public BootstrapIdentity {
    clusterName = blankToUnknown(clusterName);
    clusterToken = blankToUnknown(clusterToken);
    clusterDomain =
        (clusterDomain == null || clusterDomain.isBlank()) ? "cluster.local" : clusterDomain;
    clusterEnv = blankToUnknown(clusterEnv);
    nodeName = blankToUnknown(nodeName);
    nodeKind = blankToUnknown(nodeKind);
  }

  /**
   * Default instance used by {@link
   * io.nxmatic.rk2lab.manifests.layers.common.ManifestSynthesisContext#current()} when nothing was
   * bound — production callers always override via the synth request.
   */
  public static BootstrapIdentity unknown() {
    return DEFAULT;
  }

  private static String blankToUnknown(String value) {
    return (value == null || value.isBlank()) ? UNKNOWN : value;
  }
}
