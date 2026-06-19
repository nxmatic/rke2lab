package io.nxmatic.rke2lab.manifests.domain;

import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.nxmatic.rke2lab.manifests.bridge.ManifestDomainCatalog;
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
            new FluxOperatorManifestsUnit(),
            new FluxInstanceManifestsUnit(),
            new FluxRootManifestsUnit(),
            new SopsAgeSecretManifestsUnit()));
  }
}
