// @codebase
package io.nxmatic.rk2lab.manifests.layers.cluster;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import java.util.List;

public final class ClusterDomainRegistrar implements LayerDomainRegistrar {

  private static final ManifestDomainCatalog CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().build();

  @Override
  public LayerDomain domain() {
    return new LayerDomain(CATALOG.cluster(), List.of(new ClusterRuntimeNamespaceManifestUnit()));
  }
}
