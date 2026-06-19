package io.nxmatic.rke2lab.manifests.domain;

import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.nxmatic.rke2lab.manifests.port.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.units.mesh.HeadplaneManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.mesh.HeadscaleManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.mesh.MeshSystemNamespaceManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.mesh.TailscaleManifestsUnit;
import java.util.List;

public final class MeshDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.MESH,
        List.of(),
        List.of(
            new MeshSystemNamespaceManifestsUnit(),
            new HeadscaleManifestsUnit(),
            new HeadplaneManifestsUnit(),
            new TailscaleManifestsUnit()));
  }
}
