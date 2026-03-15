// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class LayerRegistry {

    private final Map<String, ModeledLayer> layersById;
    private final Map<String, ModeledLayer> layersByLegacyPrefix;

    public LayerRegistry(final List<ModeledLayer> layers) {
        LinkedHashMap<String, ModeledLayer> byId = new LinkedHashMap<>();
        LinkedHashMap<String, ModeledLayer> byLegacyPrefix = new LinkedHashMap<>();

        for (ModeledLayer layer : layers) {
            if (byId.put(layer.layerId(), layer) != null) {
                throw new IllegalStateException("Duplicate modeled layer id: " + layer.layerId());
            }
            for (String prefix : layer.legacyPathPrefixes()) {
                if (byLegacyPrefix.put(prefix, layer) != null) {
                    throw new IllegalStateException("Duplicate legacy path prefix mapping: " + prefix);
                }
            }
        }

        this.layersById = Map.copyOf(byId);
        this.layersByLegacyPrefix = Map.copyOf(byLegacyPrefix);
    }

    public Optional<ModeledLayer> findByLegacyPath(final String relativePath) {
        for (Map.Entry<String, ModeledLayer> entry : layersByLegacyPrefix.entrySet()) {
            if (relativePath.startsWith(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    public ModeledLayer requireById(final String layerId) {
        ModeledLayer layer = layersById.get(layerId);
        if (layer == null) {
            throw new IllegalStateException("Layer dependency references unknown layer: " + layerId);
        }
        return layer;
    }
}
