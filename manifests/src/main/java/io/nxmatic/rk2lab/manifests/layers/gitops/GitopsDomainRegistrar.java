// @codebase
package io.nxmatic.rk2lab.manifests.layers.gitops;

import io.nxmatic.rk2lab.manifests.layers.common.IncludeBackedModeledLayer;
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
                        new FluxOperatorModeledLayer(),
                    new FluxInstanceModeledLayer(),
                    new IncludeBackedModeledLayer(
                        "gitops/porch",
                        "gitops/porch/",
                        List.of(FluxOperatorModeledLayer.LAYER_ID)
                    ),
                    new IncludeBackedModeledLayer(
                        "gitops/porch-resources",
                        "gitops/porch-resources/",
                        List.of("gitops/porch")
                    )
                )
        );
    }
}
