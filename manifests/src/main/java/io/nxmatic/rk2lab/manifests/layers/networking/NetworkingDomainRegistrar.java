// @codebase
package io.nxmatic.rk2lab.manifests.layers.networking;

import io.nxmatic.rk2lab.manifests.layers.common.IncludeBackedModeledLayer;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;

import java.util.List;

public final class NetworkingDomainRegistrar implements LayerDomainRegistrar {

    @Override
    public LayerDomain domain() {
        return new LayerDomain(
                "networking",
                List.of("runtime"),
                List.of(
                        new IncludeBackedModeledLayer(
                                "networking/cilium-config",
                                "networking/cilium-config/",
                                List.of()
                        ),
                        new IncludeBackedModeledLayer(
                                "networking/cilium-advanced",
                                "networking/cilium-advanced/",
                                List.of("networking/cilium-config")
                        ),
                        new IncludeBackedModeledLayer(
                                "networking/envoy-gateway",
                                "networking/envoy-gateway/",
                                List.of("networking/cilium-advanced")
                        ),
                        new IncludeBackedModeledLayer(
                                "networking/kdns",
                                "networking/kdns/",
                                List.of("networking/cilium-config")
                        )
                )
        );
    }
}
