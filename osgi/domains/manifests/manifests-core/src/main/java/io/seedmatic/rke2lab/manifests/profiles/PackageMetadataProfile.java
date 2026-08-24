// @codebase
package io.seedmatic.rke2lab.manifests.profiles;

import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotations;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PackageMetadataProfile {

  private final String layerName;
  private final String packageName;
  // When set, every resource this profile stamps carries the NODE_BOOTSTRAP lane annotation, so the
  // exploder routes it into the node-side bootstrap file instead of the committed branch tree.
  private final boolean nodeBootstrap;
  // The reconcile layer this unit's resources default to (crds/operators/workloads); workloads when
  // unset. A per-resource call may override it via an extraAnnotations MANIFEST_LAYER entry.
  private final String defaultLayer;
  private final ManifestAnnotations manifestAnnotations;

  public PackageMetadataProfile(final String layerName, final String packageName) {
    this(layerName, packageName, false);
  }

  public PackageMetadataProfile(
      final String layerName, final String packageName, final boolean nodeBootstrap) {
    this(layerName, packageName, nodeBootstrap, ManifestAnnotations.LAYER_WORKLOADS);
  }

  public PackageMetadataProfile(
      final String layerName,
      final String packageName,
      final boolean nodeBootstrap,
      final String defaultLayer) {
    this.layerName = layerName;
    this.packageName = packageName;
    this.nodeBootstrap = nodeBootstrap;
    this.defaultLayer = defaultLayer;
    this.manifestAnnotations = new ManifestAnnotations();
  }

  // Merge the lane + layer markers into whatever the caller passed — a single seam so no call site
  // can forget them. A per-resource MANIFEST_LAYER in extraAnnotations always wins; otherwise the
  // unit's non-default layer is stamped (workloads stays implicit — absent means workloads).
  private Map<String, String> withLane(final Map<String, String> extraAnnotations) {
    final LinkedHashMap<String, String> merged = new LinkedHashMap<>(extraAnnotations);
    if (nodeBootstrap) {
      merged.put(ManifestAnnotations.NODE_BOOTSTRAP, "true");
    }
    if (!merged.containsKey(ManifestAnnotations.MANIFEST_LAYER)
        && !ManifestAnnotations.LAYER_WORKLOADS.equals(defaultLayer)) {
      merged.put(ManifestAnnotations.MANIFEST_LAYER, defaultLayer);
    }
    return merged;
  }

  public Map<String, String> packageAnnotations(final String upstreamIdentifier) {
    return manifestAnnotations.packageAnnotations(layerName, packageName, withLane(Map.of()));
  }

  public Map<String, String> packageAnnotations(
      final String upstreamIdentifier, final Map<String, String> extraAnnotations) {
    return manifestAnnotations.packageAnnotations(
        layerName, packageName, withLane(extraAnnotations));
  }

  public Map<String, String> packageAnnotationsWithoutUpstream() {
    return manifestAnnotations.packageAnnotations(layerName, packageName, withLane(Map.of()));
  }

  public Map<String, String> templateAnnotations(final Map<String, String> extraAnnotations) {
    return manifestAnnotations.packageAnnotations(
        layerName, packageName, withLane(extraAnnotations));
  }
}
