// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime;

import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;

import java.util.List;

public final class RuntimeDomainRegistrar implements LayerDomainRegistrar {

    @Override
    public LayerDomain domain() {
        return new LayerDomain(
                "runtime",
                List.of("storage", "replication"),
            List.of(
                new RKE2LabEnvConfigManifestUnit(),
                new RKE2ConfigManifestUnit(),
                new CloudConfigManifestUnit(),
                new RuntimeDaemonsetManifestUnit(),
                new FloxContainerdShimManifestUnit()
            )
        );
    }
}
