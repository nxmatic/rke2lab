// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import org.cdk8s.Chart;

import java.util.List;

public final class RuntimeCloudConfigManifestUnit extends AbstractManifestUnit {

    public static final String MANIFEST_UNIT_ID = "runtime/cloud-config";

    public RuntimeCloudConfigManifestUnit() {
        super(MANIFEST_UNIT_ID, List.of(RuntimeCloudConfigLayer.LEGACY_PATH_PREFIX), List.of());
    }

    @Override
    public void apply(final Chart chart) {
        new RuntimeCloudConfigLayer(chart, "layer-runtime-cloud-config");
    }
}
