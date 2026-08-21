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
  private final ManifestAnnotations manifestAnnotations;

  public PackageMetadataProfile(final String layerName, final String packageName) {
    this(layerName, packageName, false);
  }

  public PackageMetadataProfile(
      final String layerName, final String packageName, final boolean nodeBootstrap) {
    this.layerName = layerName;
    this.packageName = packageName;
    this.nodeBootstrap = nodeBootstrap;
    this.manifestAnnotations = new ManifestAnnotations();
  }

  // Merge the lane marker into whatever the caller passed — a single seam so no call site can
  // forget
  // it and no resource of a bootstrap unit escapes the lane.
  private Map<String, String> withLane(final Map<String, String> extraAnnotations) {
    if (!nodeBootstrap) {
      return extraAnnotations;
    }
    final LinkedHashMap<String, String> merged = new LinkedHashMap<>(extraAnnotations);
    merged.put(ManifestAnnotations.NODE_BOOTSTRAP, "true");
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
