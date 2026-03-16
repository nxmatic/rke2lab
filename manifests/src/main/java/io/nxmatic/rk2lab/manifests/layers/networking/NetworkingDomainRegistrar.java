// @codebase
package io.nxmatic.rk2lab.manifests.layers.networking;

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
                    new CiliumConfigManifestUnit(),
                    new CiliumAdvancedManifestUnit(),
                    new EnvoyGatewayManifestUnit(),
                    new KdnsManifestUnit()
                )
        );
    }
}
