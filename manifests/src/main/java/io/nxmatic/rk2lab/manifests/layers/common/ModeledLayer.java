// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import org.cdk8s.Chart;

import java.util.List;

public interface ModeledLayer {

    String layerId();

    List<String> legacyPathPrefixes();

    List<String> dependsOnLayerIds();

    void apply(Chart chart);
}
