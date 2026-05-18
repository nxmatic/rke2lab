// @codebase
package io.nxmatic.rk2lab.manifests.layers.networking;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import java.util.List;

public final class NetworkingDomainRegistrar implements LayerDomainRegistrar {

  private final ManifestDomainCatalog manifestDomainCatalog =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

  @Override
  public LayerDomain domain() {
    return new LayerDomain(
        manifestDomainCatalog.networking(),
        List.of(manifestDomainCatalog.runtime()),
        List.of(
            new CiliumConfigManifestUnit(),
            new CiliumAdvancedManifestUnit(),
            new EnvoyGatewayManifestUnit(),
            new KdnsManifestUnit()));
  }
}
