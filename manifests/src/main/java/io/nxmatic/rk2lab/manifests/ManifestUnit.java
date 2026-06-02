// @codebase
package io.nxmatic.rk2lab.manifests;

import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import java.util.List;
import org.cdk8s.Chart;

public interface ManifestUnit {

  String manifestUnitId();

  List<String> dependsOnManifestUnitIds();

  default void apply(ManifestUnitContext context) {
    apply(context.chart());
  }

  default void apply(Chart chart) {
    throw new UnsupportedOperationException(
        "ManifestUnit must override apply(Chart) or apply(ManifestUnitContext): "
            + manifestUnitId());
  }

  /**
   * Synthesizes systemd units for this manifest unit.
   *
   * <p>Default implementation does nothing. Override to emit systemd installer services, targets,
   * or other units that support the K8s manifests synthesized by {@link #apply(Chart)}.
   *
   * <p><b>Design rationale</b>: Each ManifestUnit decides whether it needs systemd support. Domain
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
