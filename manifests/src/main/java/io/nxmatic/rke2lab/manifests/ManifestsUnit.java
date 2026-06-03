// @codebase
package io.nxmatic.rke2lab.manifests;

import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import java.util.List;
import java.util.function.BiFunction;
import org.cdk8s.Chart;
import software.constructs.Construct;

public interface ManifestsUnit {

  String manifestUnitId();

  List<String> dependsOnManifestsUnitIds();

  /**
   * Creates a lazy ManifestsUnit that defers Construct instantiation until apply() is called.
   *
   * <p>Used by domain registrars to avoid creating Construct instances with null scope. The factory
   * receives (Construct scope, String id) and returns a fully-constructed ManifestsUnit.
   *
   * <p><b>Usage:</b>
   *
   * <pre>{@code
   * ManifestsUnit.lazy(
   *   ClusterRuntimeNamespaceManifestsUnit.MANIFEST_UNIT_ID,
   *   List.of(),
   *   ClusterRuntimeNamespaceManifestsUnit::new
   * )
   * }</pre>
   *
   * @param manifestUnitId the manifest unit identifier
   * @param dependsOnManifestsUnitIds list of unit IDs this unit depends on
   * @param factory factory function (scope, id) -> ManifestsUnit instance
   * @return lazy ManifestsUnit that creates the real instance in apply()
   */
  static ManifestsUnit lazy(
      String manifestUnitId,
      List<String> dependsOnManifestsUnitIds,
      BiFunction<Construct, String, ? extends ManifestsUnit> factory) {
    return new ManifestsUnit() {
      @Override
      public String manifestUnitId() {
        return manifestUnitId;
      }

      @Override
      public List<String> dependsOnManifestsUnitIds() {
        return List.copyOf(dependsOnManifestsUnitIds);
      }

      @Override
      public void apply(Chart chart) {
        factory.apply(chart, manifestUnitId.replace("/", "-"));
      }
    };
  }

  default void apply(ManifestsUnitContext context) {
    apply(context.chart());
  }

  default void apply(Chart chart) {
    throw new UnsupportedOperationException(
        "ManifestsUnit must override apply(Chart) or apply(ManifestsUnitContext): "
            + manifestUnitId());
  }

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
