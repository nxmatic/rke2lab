// @codebase
package io.nxmatic.rk2lab.manifests.layers.cicd;

import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import java.util.List;

public final class CicdDomainRegistrar implements LayerDomainRegistrar {

  @Override
  public LayerDomain domain() {
    return new LayerDomain(
        "cicd",
        List.of("gitops"),
        List.of(new TektonPipelinesManifestUnit(), new TektonDashboardManifestUnit()));
  }
}
