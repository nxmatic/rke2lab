// @codebase
package io.nxmatic.rk2lab.manifests.layers.mesh;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainIds;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import java.util.List;

public final class MeshDomainRegistrar implements LayerDomainRegistrar {

  @Override
  public LayerDomain domain() {
    return new LayerDomain(
        ManifestDomainIds.MESH,
        List.of(ManifestDomainIds.NETWORKING, ManifestDomainIds.REPLICATION),
        List.of(
            new MeshSystemNamespaceManifestUnit(),
            new HeadscaleManifestUnit(),
            new TailscaleManifestUnit(),
            new HeadplaneManifestUnit()));
  }
}
