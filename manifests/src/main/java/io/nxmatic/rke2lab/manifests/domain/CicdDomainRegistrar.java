package io.nxmatic.rke2lab.manifests.domain;

import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.nxmatic.rke2lab.manifests.ManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.cicd.TektonDashboardManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.cicd.TektonPipelinesManifestsUnit;
import java.util.List;

public final class CicdDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.CICD,
        List.of(),
        List.of(
            ManifestsUnit.lazy(
                TektonPipelinesManifestsUnit.MANIFEST_UNIT_ID,
                List.of(),
                TektonPipelinesManifestsUnit::new),
            ManifestsUnit.lazy(
                TektonDashboardManifestsUnit.MANIFEST_UNIT_ID,
                List.of(),
                TektonDashboardManifestsUnit::new)));
  }
}
