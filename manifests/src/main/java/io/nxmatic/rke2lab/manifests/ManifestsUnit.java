// @codebase
package io.nxmatic.rke2lab.manifests;

import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import java.util.List;

public interface ManifestsUnit {

  String manifestUnitId();

  List<String> dependsOnManifestsUnitIds();

  /**
   * Where this unit attaches to RKE2's lifecycle. The systemd synthesis derives ordering from this
   * (see {@link InstallPhase} and docs/rke2-install-phases.adoc). Defaults to {@link
   * InstallPhase#POST_SERVER}, the common case (RKE2 watches server/manifests after the API is up).
   */
  default InstallPhase installPhase() {
    return InstallPhase.POST_SERVER;
  }

  /**
   * The output directory segment (relative to the domain) where this unit's manifests are exploded
   * — i.e. the {@code package} of its {@link
   * io.nxmatic.rke2lab.manifests.profiles.PackageMetadataProfile}. The systemd installer links
   * {@code <domain>/<outputDir>}. Defaults to the last segment of {@link #manifestUnitId()} (the
   * common case where id and package coincide); override only when they diverge (e.g. id {@code
   * cluster-api/operator} but package {@code cluster-api-operator}).
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

  /**
   * Synthesizes systemd units for this manifest unit.
   *
   * <p>Default implementation does nothing. Override to emit systemd installer services, targets,
   * or other units that support the K8s manifests synthesized by {@link #apply(Chart)}.
   *
   * <p><b>Design rationale</b>: Each ManifestsUnit decides whether it needs systemd support. Domain
   * manifest units (cluster-api, gitops) emit installer services. Infrastructure units (network,
   * tools) emit targets and support services.
   *
   * @param systemdChart the systemd chart to populate with units
   * @param context systemd synthesis context (contains references to common targets)
   */
  default void synthesizeSystemdUnits(SystemdChart systemdChart, SystemdSynthesisContext context) {
    // Default: no systemd units
  }
}
