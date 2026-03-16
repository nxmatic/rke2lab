// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import org.cdk8s.Chart;

import java.util.HashSet;
import java.util.Set;

public final class ManifestUnitDependencyApplier {

    private final ManifestUnitRegistry manifestUnitRegistry;
    private final ManifestUnitVisitor manifestUnitVisitor;
    private final Set<String> appliedManifestUnitIds = new HashSet<>();
    private final Set<String> visitingManifestUnitIds = new HashSet<>();

    public ManifestUnitDependencyApplier(
            final ManifestUnitRegistry manifestUnitRegistry,
            final ManifestUnitVisitor manifestUnitVisitor
    ) {
        this.manifestUnitRegistry = manifestUnitRegistry;
        this.manifestUnitVisitor = manifestUnitVisitor;
    }

    public void applyManifestUnitWithDependencies(final String manifestUnitId, final Chart chart) {
        if (appliedManifestUnitIds.contains(manifestUnitId)) {
            return;
        }

        ManifestUnit manifestUnit = manifestUnitRegistry.requireById(manifestUnitId);

        if (!visitingManifestUnitIds.add(manifestUnitId)) {
            throw new IllegalStateException("Cyclic manifest unit dependency detected at: " + manifestUnitId);
        }

        for (String dependencyManifestUnitId : manifestUnit.dependsOnManifestUnitIds()) {
            applyManifestUnitWithDependencies(
                    dependencyManifestUnitId,
                    chart
            );
        }

        manifestUnitVisitor.visit(manifestUnit, chart);
        appliedManifestUnitIds.add(manifestUnitId);
        visitingManifestUnitIds.remove(manifestUnitId);
    }
}
