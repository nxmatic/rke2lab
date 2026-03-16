// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import org.cdk8s.Chart;

import java.util.List;

public final class FloxContainerBuildAssetsManifestUnit extends AbstractManifestUnit {

    public static final String MANIFEST_UNIT_ID = "runtime/flox-container-build-assets";

    public FloxContainerBuildAssetsManifestUnit() {
        super(MANIFEST_UNIT_ID, List.of(FloxContainerBuildAssetsLayer.LEGACY_PATH_PREFIX), List.of());
    }

    @Override
    public void apply(final Chart chart) {
        new FloxContainerBuildAssetsLayer(chart, "layer-runtime-flox-container-build-assets");
    }
}
