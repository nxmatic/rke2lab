// @codebase
package io.nxmatic.rk2lab.manifests.layers.gitops;

import io.nxmatic.rk2lab.manifests.layers.common.LegacyPackageIncludeConstruct;
import software.constructs.Construct;

public final class PorchLayer extends LegacyPackageIncludeConstruct {

    public static final String LEGACY_PATH_PREFIX = "gitops/porch/";

    public PorchLayer(final Construct scope, final String id) {
        super(scope, id, LEGACY_PATH_PREFIX);
    }
}
