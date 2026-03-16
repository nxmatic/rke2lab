// @codebase
package io.nxmatic.rk2lab.manifests.layers.cicd;

import io.nxmatic.rk2lab.manifests.layers.common.LegacyPackageIncludeConstruct;
import software.constructs.Construct;

public final class TektonPipelinesLayer extends LegacyPackageIncludeConstruct {

    public static final String LEGACY_PATH_PREFIX = "cicd/tekton-pipelines/";

    public TektonPipelinesLayer(final Construct scope, final String id) {
        super(scope, id, LEGACY_PATH_PREFIX);
    }
}
