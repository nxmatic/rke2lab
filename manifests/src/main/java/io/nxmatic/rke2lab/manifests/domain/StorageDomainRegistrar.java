package io.nxmatic.rke2lab.manifests.domain;

import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.nxmatic.rke2lab.manifests.units.storage.OpenebsZfsManifestsUnit;
import java.util.List;

public final class StorageDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.STORAGE, List.of(), List.of(new OpenebsZfsManifestsUnit()));
  }
}
