// @codebase
package io.nxmatic.rke2lab.manifests.profiles;

import io.nxmatic.rke2lab.manifests.contract.ManifestAnnotations;
import java.util.Map;

public final class PackageMetadataProfile {

  private final String layerName;
  private final String packageName;
  private final ManifestAnnotations manifestAnnotations;

  public PackageMetadataProfile(final String layerName, final String packageName) {
    this.layerName = layerName;
    this.packageName = packageName;
    this.manifestAnnotations = new ManifestAnnotations();
  }

  public Map<String, String> packageAnnotations(final String upstreamIdentifier) {
    return manifestAnnotations.packageAnnotations(layerName, packageName);
  }

  public Map<String, String> packageAnnotations(
      final String upstreamIdentifier, final Map<String, String> extraAnnotations) {
    return manifestAnnotations.packageAnnotations(layerName, packageName, extraAnnotations);
  }

  public Map<String, String> packageAnnotationsWithoutUpstream() {
    return manifestAnnotations.packageAnnotations(layerName, packageName);
  }

  public Map<String, String> templateAnnotations(final Map<String, String> extraAnnotations) {
    return manifestAnnotations.packageAnnotations(layerName, packageName, extraAnnotations);
  }
}
