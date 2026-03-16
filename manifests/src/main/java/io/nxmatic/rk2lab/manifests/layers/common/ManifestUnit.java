// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import org.cdk8s.Chart;

import java.util.List;

public interface ManifestUnit {

    String manifestUnitId();

    List<String> legacyPathPrefixes();

    List<String> dependsOnManifestUnitIds();

    void apply(Chart chart);
}
