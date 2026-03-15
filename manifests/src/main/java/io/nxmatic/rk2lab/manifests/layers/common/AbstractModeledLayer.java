// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import java.util.List;

public abstract class AbstractModeledLayer implements ModeledLayer {

    private final String layerId;
    private final List<String> legacyPathPrefixes;
    private final List<String> dependsOnLayerIds;

    protected AbstractModeledLayer(
            final String layerId,
            final List<String> legacyPathPrefixes,
            final List<String> dependsOnLayerIds
    ) {
        this.layerId = layerId;
        this.legacyPathPrefixes = List.copyOf(legacyPathPrefixes);
        this.dependsOnLayerIds = List.copyOf(dependsOnLayerIds);
    }

    @Override
    public final String layerId() {
        return layerId;
    }

    @Override
    public final List<String> legacyPathPrefixes() {
        return legacyPathPrefixes;
    }

    @Override
    public final List<String> dependsOnLayerIds() {
        return dependsOnLayerIds;
    }
}
