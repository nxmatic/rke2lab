// @codebase
package io.nxmatic.rk2lab.manifests.layers.networking;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import org.cdk8s.Chart;

import java.util.List;

public final class EnvoyGatewayManifestUnit extends AbstractManifestUnit {

    public static final String MANIFEST_UNIT_ID = "networking/envoy-gateway";
    public static final String LEGACY_PATH_PREFIX = "networking/envoy-gateway/";

    public EnvoyGatewayManifestUnit() {
        super(MANIFEST_UNIT_ID, List.of(LEGACY_PATH_PREFIX), List.of(CiliumAdvancedManifestUnit.MANIFEST_UNIT_ID));
    }

    @Override
    public void apply(final Chart chart) {
        new EnvoyGatewayLayer(chart, "layer-networking-envoy-gateway");
    }

}
