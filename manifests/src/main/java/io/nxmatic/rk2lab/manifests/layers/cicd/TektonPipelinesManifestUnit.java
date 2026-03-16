// @codebase
package io.nxmatic.rk2lab.manifests.layers.cicd;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import org.cdk8s.Chart;

import java.util.List;

public final class TektonPipelinesManifestUnit extends AbstractManifestUnit {

    public static final String MANIFEST_UNIT_ID = "cicd/tekton-pipelines";
    public static final String LEGACY_PATH_PREFIX = "cicd/tekton-pipelines/";

    public TektonPipelinesManifestUnit() {
        super(MANIFEST_UNIT_ID, List.of(LEGACY_PATH_PREFIX), List.of());
    }

    @Override
    public void apply(final Chart chart) {
        new TektonPipelinesLayer(chart, "layer-cicd-tekton-pipelines");
    }

}
