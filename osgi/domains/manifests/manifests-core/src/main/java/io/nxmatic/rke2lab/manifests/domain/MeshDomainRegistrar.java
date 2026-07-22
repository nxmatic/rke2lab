package io.nxmatic.rke2lab.manifests.domain;

import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.nxmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.units.mesh.HeadplaneManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.mesh.HeadscaleManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.mesh.MeshSystemNamespaceManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.mesh.TailscaleManifestsUnit;
import java.util.List;
import org.osgi.service.component.annotations.Component;

@Component(service = ManifestsDomainRegistrar.class)
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
