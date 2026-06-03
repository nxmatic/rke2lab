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
   * Where this unit attaches to RKE2's lifecycle. The systemd synthesis derives ordering from this
   * (see {@link InstallPhase} and docs/rke2-install-phases.adoc). Defaults to {@link
   * InstallPhase#POST_SERVER}, the common case (RKE2 watches server/manifests after the API is up).
   */
  default InstallPhase installPhase() {
    return InstallPhase.POST_SERVER;
  }

  /**
   * Creates a lazy ManifestsUnit that defers Construct instantiation until apply() is called.
   *
   * <p>Used by domain registrars to avoid creating Construct instances with null scope. The factory
   * receives (Construct scope, String id) and returns a fully-constructed ManifestsUnit.
   *
   * <p>The {@link InstallPhase} is passed here (not declared on the unit class) because the systemd
   * synthesis reads it from this wrapper <em>before</em> the real unit is constructed — the factory
   * only runs during {@link #apply(Chart)}.
   *
   * <p><b>Usage:</b>
   *
   * <pre>{@code
   * ManifestsUnit.lazy(
   *   ClusterRuntimeNamespaceManifestsUnit.MANIFEST_UNIT_ID,
   *   List.of(),
   *   InstallPhase.POST_SERVER,
   *   ClusterRuntimeNamespaceManifestsUnit::new
   * )
   * }</pre>
   *
   * @param manifestUnitId the manifest unit identifier
   * @param dependsOnManifestsUnitIds list of unit IDs this unit depends on
   * @param installPhase the RKE2 lifecycle phase this unit attaches to
   * @param factory factory function (scope, id) -> ManifestsUnit instance
   * @return lazy ManifestsUnit that creates the real instance in apply()
   */
  static ManifestsUnit lazy(
      String manifestUnitId,
      List<String> dependsOnManifestsUnitIds,
      BiFunction<Construct, String, ? extends ManifestsUnit> factory) {
    return lazy(manifestUnitId, dependsOnManifestsUnitIds, InstallPhase.POST_SERVER, factory);
  }

  /**
   * Like {@link #lazy(String, List, BiFunction)} but with an explicit {@link InstallPhase} — use
   * this overload only when the unit is not the common {@link InstallPhase#POST_SERVER} case.
   */
  static ManifestsUnit lazy(
      String manifestUnitId,
      List<String> dependsOnManifestsUnitIds,
      InstallPhase installPhase,
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
      public InstallPhase installPhase() {
        return installPhase;
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
