// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import io.nxmatic.rk2lab.manifests.layers.common.registry.ManifestAssemblyRegistry;
import java.util.HashSet;
import java.util.Set;
import org.cdk8s.Chart;

public final class ManifestUnitDependencyApplier {

  private final LayerDomainRegistry layerDomainRegistry;
  private final ManifestUnitRegistry manifestUnitRegistry;
  private final ManifestUnitVisitor manifestUnitVisitor;
  private final Chart chart;
  private final ManifestAssemblyRegistry assemblyRegistry;
  private final Set<String> appliedManifestUnitIds = new HashSet<>();
  private final Set<String> visitingManifestUnitIds = new HashSet<>();

  public ManifestUnitDependencyApplier(
      final LayerDomainRegistry layerDomainRegistry,
      final ManifestUnitRegistry manifestUnitRegistry,
      final ManifestUnitVisitor manifestUnitVisitor,
      final Chart chart,
      final ManifestAssemblyRegistry assemblyRegistry) {
    this.layerDomainRegistry = layerDomainRegistry;
    this.manifestUnitRegistry = manifestUnitRegistry;
    this.manifestUnitVisitor = manifestUnitVisitor;
    this.chart = chart;
    this.assemblyRegistry = assemblyRegistry;
  }

  public void applyManifestUnitWithDependencies(final String manifestUnitId) {
    if (appliedManifestUnitIds.contains(manifestUnitId)) {
      return;
    }

    ManifestUnit manifestUnit = manifestUnitRegistry.requireById(manifestUnitId);

    if (!visitingManifestUnitIds.add(manifestUnitId)) {
      throw new IllegalStateException(
          "Cyclic manifest unit dependency detected at: " + manifestUnitId);
    }

    for (String dependencyManifestUnitId : manifestUnit.dependsOnManifestUnitIds()) {
      applyManifestUnitWithDependencies(dependencyManifestUnitId);
    }

    final String domainId = layerDomainRegistry.requireDomainIdForManifestUnit(manifestUnitId);
    final ManifestUnitContext context =
        new ManifestUnitContext(
            chart,
            domainId,
            manifestUnitId,
            assemblyRegistry.domainRegistry(domainId).manifestUnitRegistry(manifestUnitId));
    manifestUnitVisitor.visit(manifestUnit, context);
    appliedManifestUnitIds.add(manifestUnitId);
    visitingManifestUnitIds.remove(manifestUnitId);
  }
}
