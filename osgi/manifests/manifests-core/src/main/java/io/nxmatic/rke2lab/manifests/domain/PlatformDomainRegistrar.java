package io.nxmatic.rke2lab.manifests.domain;

import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.nxmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.units.platform.CertManagerManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.platform.ReplicatorManifestsUnit;
import java.util.List;

public final class PlatformDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.PLATFORM,
        List.of(),
        List.of(new CertManagerManifestsUnit(), new ReplicatorManifestsUnit()));
  }
}
