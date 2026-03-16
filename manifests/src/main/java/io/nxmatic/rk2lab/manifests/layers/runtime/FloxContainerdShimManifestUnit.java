// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.replication.ReplicationReplicatorManifestUnit;
import org.cdk8s.Chart;

import java.util.List;

public final class FloxContainerdShimManifestUnit extends AbstractManifestUnit {

    public static final String MANIFEST_UNIT_ID = "runtime/flox-containerd-shim";

    public FloxContainerdShimManifestUnit() {
        super(
                MANIFEST_UNIT_ID,
                List.of(FloxContainerdShimLayer.LEGACY_PATH_PREFIX),
            List.of(ReplicationReplicatorManifestUnit.MANIFEST_UNIT_ID)
        );
    }

    @Override
    public void apply(final Chart chart) {
        new FloxContainerdShimLayer(chart, "layer-runtime-flox-containerd-shim");
    }
}
