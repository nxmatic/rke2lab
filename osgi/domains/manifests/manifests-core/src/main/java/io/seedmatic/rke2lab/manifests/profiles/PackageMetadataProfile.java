// @codebase
package io.seedmatic.rke2lab.manifests.profiles;

import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PackageMetadataProfile {

  private final String domain;
  private final String packageName;
  // When set, every resource this profile stamps carries the NODE_BOOTSTRAP lane annotation, so the
  // exploder routes it into the node-side bootstrap file instead of the committed branch tree.
  private final boolean nodeBootstrap;
  // The reconcile layer this unit's resources default to; WORKLOADS when unset. A per-resource call
  // may override it via an extraAnnotations MANIFEST_LAYER entry.
  private final ManifestLayer defaultLayer;

  public PackageMetadataProfile(final String domain, final String packageName) {
    this(domain, packageName, false);
  }

  public PackageMetadataProfile(
      final String domain, final String packageName, final boolean nodeBootstrap) {
    this(domain, packageName, nodeBootstrap, ManifestLayer.WORKLOADS);
  }

  public PackageMetadataProfile(
      final String domain,
      final String packageName,
      final boolean nodeBootstrap,
      final ManifestLayer defaultLayer) {
    this.domain = domain;
    this.packageName = packageName;
    this.nodeBootstrap = nodeBootstrap;
    this.defaultLayer = defaultLayer;
  }

  // Merge the lane + layer markers into whatever the caller passed — a single seam so no call site
  // can forget them. A per-resource MANIFEST_LAYER in extraAnnotations always wins; otherwise the
  // unit's non-default layer is stamped (workloads stays implicit — absent means workloads).
  private Map<String, String> withLane(final Map<String, String> extraAnnotations) {
    final LinkedHashMap<String, String> merged = new LinkedHashMap<>(extraAnnotations);
    if (nodeBootstrap) {
      merged.put(ManifestAnnotation.NODE_BOOTSTRAP.key(), "true");
    }
    if (!merged.containsKey(ManifestAnnotation.MANIFEST_LAYER.key())
        && defaultLayer != ManifestLayer.WORKLOADS) {
      merged.put(ManifestAnnotation.MANIFEST_LAYER.key(), defaultLayer.value());
    }
    return merged;
  }

  public Map<String, String> packageAnnotations(final String upstreamIdentifier) {
    return ManifestAnnotation.packageAnnotations(domain, packageName, withLane(Map.of()));
  }

  public Map<String, String> packageAnnotations(
      final String upstreamIdentifier, final Map<String, String> extraAnnotations) {
    return ManifestAnnotation.packageAnnotations(domain, packageName, withLane(extraAnnotations));
  }

  public Map<String, String> packageAnnotationsWithoutUpstream() {
    return ManifestAnnotation.packageAnnotations(domain, packageName, withLane(Map.of()));
  }

  public Map<String, String> templateAnnotations(final Map<String, String> extraAnnotations) {
    return ManifestAnnotation.packageAnnotations(domain, packageName, withLane(extraAnnotations));
  }
}
