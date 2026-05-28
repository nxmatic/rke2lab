package io.nxmatic.rk2lab.manifests.layers.clusterapi;

import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import java.util.List;

public final class ClusterApiDomainRegistrar implements LayerDomainRegistrar {

  @Override
  public LayerDomain domain() {
    return new LayerDomain(
        "cluster-api",
        List.of(),
        List.of(
            new ClusterApiOperatorManifestUnit(),
            new IncusIdentitySecretManifestUnit()));
  }
}
