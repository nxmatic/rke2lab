// @codebase
package io.nxmatic.rk2lab.manifests.layers.ha;

import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;

import java.util.List;

public final class HaDomainRegistrar implements LayerDomainRegistrar {

    @Override
    public LayerDomain domain() {
        return new LayerDomain(
                "ha",
                List.of("networking"),
                List.of(
                    new KubeVipManifestUnit()
                )
        );
    }
}
