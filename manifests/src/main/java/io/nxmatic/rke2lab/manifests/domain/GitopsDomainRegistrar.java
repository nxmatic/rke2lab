package io.nxmatic.rke2lab.manifests.domain;

import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.nxmatic.rke2lab.manifests.ManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.gitops.FluxInstanceManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.gitops.FluxOperatorManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.gitops.FluxRootManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.gitops.SopsAgeSecretManifestsUnit;
import java.util.List;

public final class GitopsDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.GITOPS,
        List.of(ManifestDomainCatalog.PLATFORM),
        List.of(
            ManifestsUnit.lazy(
                FluxOperatorManifestsUnit.MANIFEST_UNIT_ID,
                List.of(),
                FluxOperatorManifestsUnit::new),
            ManifestsUnit.lazy(
                FluxInstanceManifestsUnit.MANIFEST_UNIT_ID,
                List.of(),
                FluxInstanceManifestsUnit::new),
            ManifestsUnit.lazy(
                FluxRootManifestsUnit.MANIFEST_UNIT_ID, List.of(), FluxRootManifestsUnit::new),
            ManifestsUnit.lazy(
                SopsAgeSecretManifestsUnit.MANIFEST_UNIT_ID,
                List.of(),
                SopsAgeSecretManifestsUnit::new)));
  }
}
