// @codebase
package io.nxmatic.rk2lab.manifests.layers.storage;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainIds;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import java.util.List;

public final class StorageDomainRegistrar implements LayerDomainRegistrar {

  @Override
  public LayerDomain domain() {
    return new LayerDomain(ManifestDomainIds.STORAGE, List.of(new OpenebsZfsManifestUnit()));
  }
}
