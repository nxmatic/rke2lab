package io.nxmatic.rke2lab.manifests.domain;

import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.nxmatic.rke2lab.manifests.port.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.units.runtime.cloudinit.CloudConfigManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.runtime.daemonset.RuntimeDaemonsetScriptPolicyManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.runtime.env.RKE2LabEnvConfigManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.runtime.flox.FloxRuntimeManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.runtime.libexec.RuntimeSystemdLibexecPlaceholderManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.runtime.rke2.RuntimeRke2ConfigManifestsUnit;
import java.util.List;

public final class RuntimeDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.RUNTIME,
        List.of(ManifestDomainCatalog.CLUSTER, ManifestDomainCatalog.PLATFORM),
        List.of(
            new RKE2LabEnvConfigManifestsUnit(),
            new RuntimeRke2ConfigManifestsUnit(),
            new CloudConfigManifestsUnit(),
            new FloxRuntimeManifestsUnit(),
            new RuntimeSystemdLibexecPlaceholderManifestsUnit(),
            new RuntimeDaemonsetScriptPolicyManifestsUnit()));
  }
}
