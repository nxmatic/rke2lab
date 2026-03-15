// @codebase
package io.nxmatic.rk2lab.manifests.layers.gitops;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractModeledLayer;
import org.cdk8s.Chart;

import java.util.List;

public final class FluxInstanceModeledLayer extends AbstractModeledLayer {

    public static final String LAYER_ID = "gitops/flux-instance";

    public FluxInstanceModeledLayer() {
        super(
                LAYER_ID,
                List.of(FluxInstanceLayer.LEGACY_PATH_PREFIX),
                List.of(FluxOperatorModeledLayer.LAYER_ID)
        );
    }

    @Override
    public void apply(final Chart chart) {
        new FluxInstanceLayer(chart, "layer-gitops-flux-instance");
    }
}
