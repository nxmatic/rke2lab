// @codebase
package io.nxmatic.rk2lab.manifests.layers.replication;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractModeledLayer;
import org.cdk8s.Chart;

import java.util.List;

public final class ReplicationReplicatorModeledLayer extends AbstractModeledLayer {

    public static final String LAYER_ID = "replication/replicator";

    public ReplicationReplicatorModeledLayer() {
        super(
                LAYER_ID,
                List.of(ReplicatorLayer.LEGACY_PATH_PREFIX),
                List.of()
        );
    }

    @Override
    public void apply(final Chart chart) {
        new ReplicatorLayer(chart, "layer-replication-replicator");
    }
}
