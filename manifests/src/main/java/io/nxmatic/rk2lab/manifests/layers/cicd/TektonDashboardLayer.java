// @codebase
package io.nxmatic.rk2lab.manifests.layers.cicd;

import io.nxmatic.rk2lab.manifests.layers.common.LegacyPackageIncludeConstruct;
import software.constructs.Construct;

public final class TektonDashboardLayer extends LegacyPackageIncludeConstruct {

    public static final String LEGACY_PATH_PREFIX = "cicd/tekton-dashboard/";

    public TektonDashboardLayer(final Construct scope, final String id) {
        super(scope, id, LEGACY_PATH_PREFIX);
    }
}
