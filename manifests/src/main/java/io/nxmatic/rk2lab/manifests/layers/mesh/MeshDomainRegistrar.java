// @codebase
package io.nxmatic.rk2lab.manifests.layers.mesh;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import java.util.List;

public final class MeshDomainRegistrar implements LayerDomainRegistrar {

  private final ManifestDomainCatalog manifestDomainCatalog =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

  @Override
  public LayerDomain domain() {
    return new LayerDomain(
        manifestDomainCatalog.mesh(),
        List.of(manifestDomainCatalog.networking(), manifestDomainCatalog.replication()),
        List.of(
            new MeshSystemNamespaceManifestUnit(),
            new HeadscaleManifestUnit(),
            new TailscaleManifestUnit(),
            new HeadplaneManifestUnit()));
  }
}
