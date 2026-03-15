// @codebase
package io.nxmatic.rk2lab.manifests.layers.storage;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractModeledLayer;
import org.cdk8s.Chart;

import java.util.List;

public final class OpenebsZfsModeledLayer extends AbstractModeledLayer {

    public static final String LAYER_ID = "storage/openebs-zfs";

    public OpenebsZfsModeledLayer() {
        super(
                LAYER_ID,
                List.of(OpenebsZfsLayer.LEGACY_PATH_PREFIX),
                List.of()
        );
    }

    @Override
    public void apply(final Chart chart) {
        new OpenebsZfsLayer(chart, "layer-storage-openebs-zfs");
    }
}
