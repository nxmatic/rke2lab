// @codebase
package io.nxmatic.rk2lab.manifests.layers.storage;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import org.cdk8s.Chart;

import java.util.List;

public final class OpenebsZfsManifestUnit extends AbstractManifestUnit {

    public static final String MANIFEST_UNIT_ID = "storage/openebs-zfs";

    public OpenebsZfsManifestUnit() {
        super(
                MANIFEST_UNIT_ID,
                List.of(OpenebsZfsLayer.LEGACY_PATH_PREFIX),
                List.of()
        );
    }

    @Override
    public void apply(final Chart chart) {
        new OpenebsZfsLayer(chart, "layer-storage-openebs-zfs");
    }
}
