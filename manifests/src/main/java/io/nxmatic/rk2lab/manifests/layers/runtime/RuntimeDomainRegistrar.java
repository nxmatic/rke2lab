// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime;

import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.runtime.cloudinit.CloudConfigManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.runtime.daemonset.RuntimeDaemonsetScriptPolicyManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.runtime.env.RKE2LabEnvConfigManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.runtime.flox.FloxContainerdShimManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.runtime.rke2.RKE2ConfigManifestUnit;
import java.util.List;

public final class RuntimeDomainRegistrar implements LayerDomainRegistrar {

  @Override
  public LayerDomain domain() {
    return new LayerDomain(
        "runtime",
        List.of("cluster", "storage", "replication"),
        List.of(
            new RKE2LabEnvConfigManifestUnit(),
            new RKE2ConfigManifestUnit(),
            new CloudConfigManifestUnit(),
            new RuntimeDaemonsetScriptPolicyManifestUnit(),
            new FloxContainerdShimManifestUnit()));
  }
}
