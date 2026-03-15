// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import org.cdk8s.Chart;

import java.util.HashSet;
import java.util.Set;

public final class LayerDependencyApplier {

    private final LayerRegistry layerRegistry;
    private final LayerVisitor layerVisitor;
    private final Set<String> appliedLayerIds = new HashSet<>();
    private final Set<String> visitingLayerIds = new HashSet<>();

    public LayerDependencyApplier(final LayerRegistry layerRegistry, final LayerVisitor layerVisitor) {
        this.layerRegistry = layerRegistry;
        this.layerVisitor = layerVisitor;
    }

    public void applyLayerWithDependencies(final String layerId, final Chart chart) {
        if (appliedLayerIds.contains(layerId)) {
            return;
        }

        ModeledLayer layer = layerRegistry.requireById(layerId);

        if (!visitingLayerIds.add(layerId)) {
            throw new IllegalStateException("Cyclic layer dependency detected at: " + layerId);
        }

        for (String dependencyLayerId : layer.dependsOnLayerIds()) {
            applyLayerWithDependencies(
                    dependencyLayerId,
                    chart
            );
        }

        layerVisitor.visit(layer, chart);
        appliedLayerIds.add(layerId);
        visitingLayerIds.remove(layerId);
    }
}
