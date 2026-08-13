package io.seedmatic.rke2lab.manifests.domain;

import io.seedmatic.rke2lab.manifests.ManifestsDomain;
import io.seedmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.units.gitops.FluxInstanceManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.gitops.FluxOperatorManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.gitops.FluxRootManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.gitops.GithubAppSecretManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.gitops.SopsAgeSecretManifestsUnit;
import java.util.List;
import org.osgi.service.component.annotations.Component;

@Component(service = ManifestsDomainRegistrar.class)
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
            new SopsAgeSecretManifestsUnit(),
            new GithubAppSecretManifestsUnit()));
  }
}
