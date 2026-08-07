// @codebase
package io.nxmatic.rke2lab.manifests;

import java.util.List;

public interface ManifestsUnit {

  String manifestUnitId();

  List<String> dependsOnManifestsUnitIds();

  /**
   * The output directory segment (relative to the domain) where this unit's manifests are exploded
   * — i.e. the {@code package} of its {@link
   * io.nxmatic.rke2lab.manifests.profiles.PackageMetadataProfile}. Defaults to the last segment of
   * {@link #manifestUnitId()} (the common case where id and package coincide); override only when
   * they diverge (e.g. id {@code cluster-api/operator} but package {@code cluster-api-operator}).
   */
  default String outputDir() {
    final String id = manifestUnitId();
    final int slash = id.lastIndexOf('/');
    return slash < 0 ? id : id.substring(slash + 1);
  }

  /**
   * Synthesizes this unit's Kubernetes manifests into the given context.
   *
   * <p>Implementations must extend {@link AbstractManifestsUnit} which provides the template method
   * pattern: creates a scope Construct, calls {@link
   * AbstractManifestsUnit#doSynthesize(software.constructs.Construct, ManifestsUnitContext)},
   * introspects emitted ApiObjects, and emits a group marker ConfigMap.
   */
  void apply(ManifestsUnitContext context);
}
