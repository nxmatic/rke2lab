// @codebase
package io.nxmatic.rk2lab.manifests.layers.replication;

import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import java.util.List;

public final class ReplicationDomainRegistrar implements LayerDomainRegistrar {

  @Override
  public LayerDomain domain() {
    return new LayerDomain(
        "replication",
        List.of(
            new io.nxmatic.rk2lab.manifests.layers.replication
                .ReplicationReplicatorManifestUnit()));
  }
}
