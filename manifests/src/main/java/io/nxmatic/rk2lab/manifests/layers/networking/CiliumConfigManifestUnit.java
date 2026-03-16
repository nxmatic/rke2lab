// @codebase
package io.nxmatic.rk2lab.manifests.layers.networking;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import org.cdk8s.Chart;

import java.util.List;

public final class CiliumConfigManifestUnit extends AbstractManifestUnit {

    public static final String MANIFEST_UNIT_ID = "networking/cilium-config";
    public static final String LEGACY_PATH_PREFIX = "networking/cilium-config/";

    public CiliumConfigManifestUnit() {
        super(MANIFEST_UNIT_ID, List.of(LEGACY_PATH_PREFIX), List.of());
    }

    @Override
    public void apply(final Chart chart) {
        new CiliumConfigLayer(chart, "layer-networking-cilium-config");
    }

}
