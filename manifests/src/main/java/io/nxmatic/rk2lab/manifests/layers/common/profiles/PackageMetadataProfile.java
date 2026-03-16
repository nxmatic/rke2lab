// @codebase
package io.nxmatic.rk2lab.manifests.layers.common.profiles;

import io.nxmatic.rk2lab.manifests.layers.common.KptMetadata;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PackageMetadataProfile {

    private final String layerName;
    private final String packageName;
    private final KptMetadata kptMetadata;

    public PackageMetadataProfile(final String layerName, final String packageName) {
        this.layerName = layerName;
        this.packageName = packageName;
        this.kptMetadata = new KptMetadata();
    }

    public Map<String, String> packageAnnotations(final String upstreamIdentifier) {
        return kptMetadata.packageAnnotations(layerName, packageName, upstreamIdentifier);
    }

    public Map<String, String> packageAnnotations(
            final String upstreamIdentifier,
            final Map<String, String> extraAnnotations
    ) {
        return kptMetadata.packageAnnotations(layerName, packageName, upstreamIdentifier, extraAnnotations);
    }

    public Map<String, String> packageAnnotationsWithoutUpstream() {
        LinkedHashMap<String, String> annotations = new LinkedHashMap<>();
        annotations.put("kpt.dev/package-layer", layerName);
        annotations.put("kpt.dev/package-name", packageName);
        return Map.copyOf(annotations);
    }

    public Map<String, String> templateAnnotations(final Map<String, String> extraAnnotations) {
        LinkedHashMap<String, String> annotations = new LinkedHashMap<>(packageAnnotationsWithoutUpstream());
        annotations.putAll(extraAnnotations);
        return Map.copyOf(annotations);
    }
}
