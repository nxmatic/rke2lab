// @codebase
package io.nxmatic.rk2lab.manifests.layers.gitops;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import org.cdk8s.Chart;

import java.util.List;

public final class PorchResourcesManifestUnit extends AbstractManifestUnit {

    public static final String MANIFEST_UNIT_ID = "gitops/porch-resources";
    public static final String LEGACY_PATH_PREFIX = "gitops/porch-resources/";

    public PorchResourcesManifestUnit() {
        super(MANIFEST_UNIT_ID, List.of(LEGACY_PATH_PREFIX), List.of(FluxInstanceManifestUnit.MANIFEST_UNIT_ID));
    }

    @Override
    public void apply(final Chart chart) {
        new PorchResourcesLayer(chart, "layer-gitops-porch-resources");
    }

}
