// @codebase
package io.nxmatic.rk2lab.manifests.layers.cluster;

import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import java.util.List;

public final class ClusterDomainRegistrar implements LayerDomainRegistrar {

  @Override
  public LayerDomain domain() {
    return new LayerDomain("cluster", List.of(new ClusterRuntimeNamespaceManifestUnit()));
  }
}
