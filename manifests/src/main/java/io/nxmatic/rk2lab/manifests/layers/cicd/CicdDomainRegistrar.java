// @codebase
package io.nxmatic.rk2lab.manifests.layers.cicd;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import java.util.List;

public final class CicdDomainRegistrar implements LayerDomainRegistrar {

  private static final ManifestDomainCatalog CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().build();

  @Override
  public LayerDomain domain() {
    return new LayerDomain(
        CATALOG.cicd(),
        List.of(CATALOG.gitops()),
        List.of(new TektonPipelinesManifestUnit(), new TektonDashboardManifestUnit()));
  }
}
