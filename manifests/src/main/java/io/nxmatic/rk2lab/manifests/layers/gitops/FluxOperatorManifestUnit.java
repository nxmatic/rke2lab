// @codebase
package io.nxmatic.rk2lab.manifests.layers.gitops;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import org.cdk8s.Chart;

import java.util.List;

public final class FluxOperatorManifestUnit extends AbstractManifestUnit {

    public static final String MANIFEST_UNIT_ID = "gitops/flux-operator";

    public FluxOperatorManifestUnit() {
        super(
                MANIFEST_UNIT_ID,
                List.of(FluxOperatorLayer.LEGACY_PATH_PREFIX),
                List.of()
        );
    }

    @Override
    public void apply(final Chart chart) {
        new FluxOperatorLayer(chart, "layer-gitops-flux-operator");
    }
}
