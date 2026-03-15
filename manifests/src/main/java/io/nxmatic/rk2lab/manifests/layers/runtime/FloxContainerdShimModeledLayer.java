// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractModeledLayer;
import io.nxmatic.rk2lab.manifests.layers.replication.ReplicationReplicatorModeledLayer;
import org.cdk8s.Chart;

import java.util.List;

public final class FloxContainerdShimModeledLayer extends AbstractModeledLayer {

    public static final String LAYER_ID = "runtime/flox-containerd-shim";

    public FloxContainerdShimModeledLayer() {
        super(
                LAYER_ID,
                List.of(FloxContainerdShimLayer.LEGACY_PATH_PREFIX),
            List.of(ReplicationReplicatorModeledLayer.LAYER_ID)
        );
    }

    @Override
    public void apply(final Chart chart) {
        new FloxContainerdShimLayer(chart, "layer-runtime-flox-containerd-shim");
    }
}
