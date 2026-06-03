package io.nxmatic.rke2lab.manifests.domain;

import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.nxmatic.rke2lab.manifests.ManifestsUnit;
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
            ManifestsUnit.lazy(
                MeshSystemNamespaceManifestsUnit.MANIFEST_UNIT_ID,
                List.of(),
                MeshSystemNamespaceManifestsUnit::new),
            ManifestsUnit.lazy(
                HeadscaleManifestsUnit.MANIFEST_UNIT_ID, List.of(), HeadscaleManifestsUnit::new),
            ManifestsUnit.lazy(
                HeadplaneManifestsUnit.MANIFEST_UNIT_ID, List.of(), HeadplaneManifestsUnit::new),
            ManifestsUnit.lazy(
                TailscaleManifestsUnit.MANIFEST_UNIT_ID, List.of(), TailscaleManifestsUnit::new)));
  }
}
