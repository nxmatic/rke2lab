// @codebase
package io.nxmatic.rk2lab.manifests.layers.gitops;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainPolicy;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnit;
import java.util.ArrayList;
import java.util.List;

public final class GitopsDomainRegistrar implements LayerDomainRegistrar {

  @Override
  public LayerDomain domain() {
    return domain(ManifestDomainPolicy.builder().build());
  }

  @Override
  public LayerDomain domain(ManifestDomainPolicy policy) {
    final List<ManifestUnit> units = new ArrayList<>();
    units.add(new FluxInstanceManifestUnit());
    units.add(new SopsAgeSecretManifestUnit());
    units.add(new FluxRootManifestUnit());

    if (policy.isEnabled("porch")) {
      units.add(new PorchResourcesManifestUnit());
    }

    return new LayerDomain("gitops", List.of("replication"), units);
  }
}
