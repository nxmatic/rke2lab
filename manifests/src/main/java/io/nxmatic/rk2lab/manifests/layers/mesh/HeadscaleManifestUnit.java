// @codebase
package io.nxmatic.rk2lab.manifests.layers.mesh;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import org.cdk8s.Chart;

import java.util.List;

public final class HeadscaleManifestUnit extends AbstractManifestUnit {

    public static final String MANIFEST_UNIT_ID = "mesh/headscale";
    public static final String LEGACY_PATH_PREFIX = "mesh/headscale/";

    public HeadscaleManifestUnit() {
        super(MANIFEST_UNIT_ID, List.of(LEGACY_PATH_PREFIX), List.of());
    }

    @Override
    public void apply(final Chart chart) {
        new HeadscaleLayer(chart, "layer-mesh-headscale");
    }

}
