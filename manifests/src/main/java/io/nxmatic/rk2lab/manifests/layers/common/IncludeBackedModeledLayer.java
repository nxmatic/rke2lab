// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import org.cdk8s.Chart;

import java.util.List;

public final class IncludeBackedModeledLayer extends AbstractModeledLayer {

    public IncludeBackedModeledLayer(
            final String layerId,
            final String legacyPathPrefix,
            final List<String> dependsOnLayerIds
    ) {
        super(layerId, List.of(legacyPathPrefix), dependsOnLayerIds);
    }

    @Override
    public void apply(final Chart chart) {
        String constructId = "layer-" + layerId()
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        new LegacyPackageIncludeLayer(chart, constructId, legacyPathPrefixes().getFirst());
    }
}
