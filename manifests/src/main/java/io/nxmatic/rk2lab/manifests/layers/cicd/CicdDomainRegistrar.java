// @codebase
package io.nxmatic.rk2lab.manifests.layers.cicd;

import io.nxmatic.rk2lab.manifests.layers.common.IncludeBackedModeledLayer;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;

import java.util.List;

public final class CicdDomainRegistrar implements LayerDomainRegistrar {

    @Override
    public LayerDomain domain() {
        return new LayerDomain(
                "cicd",
                List.of("gitops"),
                List.of(
                        new IncludeBackedModeledLayer(
                                "cicd/tekton-pipelines",
                                "cicd/tekton-pipelines/",
                                List.of()
                        ),
                        new IncludeBackedModeledLayer(
                                "cicd/tekton-dashboard",
                                "cicd/tekton-dashboard/",
                                List.of("cicd/tekton-pipelines")
                        )
                )
        );
    }
}
