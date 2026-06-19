package io.nxmatic.rke2lab.manifests.domain;

import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.nxmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.units.cicd.TektonDashboardManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.cicd.TektonPipelinesManifestsUnit;
import java.util.List;

public final class CicdDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.CICD,
        List.of(),
        List.of(new TektonPipelinesManifestsUnit(), new TektonDashboardManifestsUnit()));
  }
}
