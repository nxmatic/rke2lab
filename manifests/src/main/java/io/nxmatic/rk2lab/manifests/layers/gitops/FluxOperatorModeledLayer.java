// @codebase
package io.nxmatic.rk2lab.manifests.layers.gitops;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractModeledLayer;
import org.cdk8s.Chart;

import java.util.List;

public final class FluxOperatorModeledLayer extends AbstractModeledLayer {

    public static final String LAYER_ID = "gitops/flux-operator";

    public FluxOperatorModeledLayer() {
        super(
                LAYER_ID,
                List.of(FluxOperatorLayer.LEGACY_PATH_PREFIX),
                List.of()
        );
    }

    @Override
    public void apply(final Chart chart) {
        new FluxOperatorLayer(chart, "layer-gitops-flux-operator");
    }
}
