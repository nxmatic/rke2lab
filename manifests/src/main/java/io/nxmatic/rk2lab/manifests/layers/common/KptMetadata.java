// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import java.util.LinkedHashMap;
import java.util.Map;

public final class KptMetadata {

    public Map<String, String> packageAnnotations(
            final String layer,
            final String packageName,
            final String upstreamIdentifier
    ) {
        return packageAnnotations(layer, packageName, upstreamIdentifier, Map.of());
    }

    public Map<String, String> packageAnnotations(
            final String layer,
            final String packageName,
            final String upstreamIdentifier,
            final Map<String, String> extraAnnotations
    ) {
        LinkedHashMap<String, String> annotations = new LinkedHashMap<>();
        annotations.put("internal.kpt.dev/upstream-identifier", upstreamIdentifier);
        annotations.put("kpt.dev/package-layer", layer);
        annotations.put("kpt.dev/package-name", packageName);
        annotations.putAll(extraAnnotations);
        return Map.copyOf(annotations);
    }
}
