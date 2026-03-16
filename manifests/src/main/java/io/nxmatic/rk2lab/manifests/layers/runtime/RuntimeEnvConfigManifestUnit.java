// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import org.cdk8s.Chart;

import java.util.List;

public final class RuntimeEnvConfigManifestUnit extends AbstractManifestUnit {

    public static final String MANIFEST_UNIT_ID = "runtime/env-config";

    public RuntimeEnvConfigManifestUnit() {
        super(MANIFEST_UNIT_ID, List.of(RuntimeEnvConfigLayer.LEGACY_PATH_PREFIX), List.of());
    }

    @Override
    public void apply(final Chart chart) {
        new RuntimeEnvConfigLayer(chart, "layer-runtime-env-config");
    }
}
