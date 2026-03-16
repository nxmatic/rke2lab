// @codebase
package io.nxmatic.rk2lab.manifests.layers.gitops;

import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;

import java.util.List;

public final class GitopsDomainRegistrar implements LayerDomainRegistrar {

    @Override
    public LayerDomain domain() {
        return new LayerDomain(
                "gitops",
                List.of("replication"),
                List.of(
                        new FluxInstanceManifestUnit(),
                        new PorchResourcesManifestUnit()
                )
        );
    }
}
