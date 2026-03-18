// @codebase
package io.nxmatic.rk2lab.manifests.layers.mesh;

import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import java.util.List;

public final class MeshDomainRegistrar implements LayerDomainRegistrar {

  @Override
  public LayerDomain domain() {
    return new LayerDomain(
        "mesh",
        List.of("networking", "replication"),
        List.of(
            new HeadscaleManifestUnit(), new TailscaleManifestUnit(), new HeadplaneManifestUnit()));
  }
}
