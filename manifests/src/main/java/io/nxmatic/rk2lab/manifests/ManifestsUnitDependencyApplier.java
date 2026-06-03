// @codebase
package io.nxmatic.rk2lab.manifests;

import io.nxmatic.rk2lab.manifests.registry.ManifestAssemblyRegistry;
import java.util.HashSet;
import java.util.Set;
import org.cdk8s.Chart;

public final class ManifestsUnitDependencyApplier {

  private final LayerDomainRegistry layerDomainRegistry;
  private final ManifestsUnitRegistry manifestUnitRegistry;
  private final ManifestsUnitVisitor manifestUnitVisitor;
  private final Chart chart;
  private final ManifestAssemblyRegistry assemblyRegistry;
  private final Set<String> appliedManifestsUnitIds = new HashSet<>();
  private final Set<String> visitingManifestsUnitIds = new HashSet<>();

  public ManifestsUnitDependencyApplier(
      final LayerDomainRegistry layerDomainRegistry,
      final ManifestsUnitRegistry manifestUnitRegistry,
      final ManifestsUnitVisitor manifestUnitVisitor,
      final Chart chart,
      final ManifestAssemblyRegistry assemblyRegistry) {
    this.layerDomainRegistry = layerDomainRegistry;
    this.manifestUnitRegistry = manifestUnitRegistry;
    this.manifestUnitVisitor = manifestUnitVisitor;
    this.chart = chart;
    this.assemblyRegistry = assemblyRegistry;
  }

  public void applyManifestsUnitWithDependencies(final String manifestUnitId) {
    if (appliedManifestsUnitIds.contains(manifestUnitId)) {
      return;
    }

    ManifestsUnit manifestUnit = manifestUnitRegistry.requireById(manifestUnitId);

    if (!visitingManifestsUnitIds.add(manifestUnitId)) {
      throw new IllegalStateException(
          "Cyclic manifest unit dependency detected at: " + manifestUnitId);
    }

    for (String dependencyManifestsUnitId : manifestUnit.dependsOnManifestsUnitIds()) {
      applyManifestsUnitWithDependencies(dependencyManifestsUnitId);
    }

    final String domainId = layerDomainRegistry.requireDomainIdForManifestsUnit(manifestUnitId);
    final ManifestsUnitContext context =
        new ManifestsUnitContext(
            chart,
            domainId,
            manifestUnitId,
            assemblyRegistry.domainRegistry(domainId).manifestUnitRegistry(manifestUnitId));
    manifestUnitVisitor.visit(manifestUnit, context);
    appliedManifestsUnitIds.add(manifestUnitId);
    visitingManifestsUnitIds.remove(manifestUnitId);
  }
}
