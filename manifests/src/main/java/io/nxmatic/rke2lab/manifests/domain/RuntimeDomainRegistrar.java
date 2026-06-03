package io.nxmatic.rke2lab.manifests.domain;

import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.nxmatic.rke2lab.manifests.ManifestsUnit;
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
        List.of(),
        List.of(
            ManifestsUnit.lazy(
                RKE2LabEnvConfigManifestsUnit.MANIFEST_UNIT_ID,
                List.of(),
                RKE2LabEnvConfigManifestsUnit::new),
            ManifestsUnit.lazy(
                RuntimeRke2ConfigManifestsUnit.MANIFEST_UNIT_ID,
                List.of(),
                RuntimeRke2ConfigManifestsUnit::new),
            ManifestsUnit.lazy(
                CloudConfigManifestsUnit.MANIFEST_UNIT_ID,
                List.of(),
                CloudConfigManifestsUnit::new),
            ManifestsUnit.lazy(
                FloxRuntimeManifestsUnit.MANIFEST_UNIT_ID,
                List.of(),
                FloxRuntimeManifestsUnit::new),
            ManifestsUnit.lazy(
                RuntimeSystemdLibexecPlaceholderManifestsUnit.MANIFEST_UNIT_ID,
                List.of(),
                RuntimeSystemdLibexecPlaceholderManifestsUnit::new),
            ManifestsUnit.lazy(
                RuntimeDaemonsetScriptPolicyManifestsUnit.MANIFEST_UNIT_ID,
                List.of(),
                RuntimeDaemonsetScriptPolicyManifestsUnit::new)));
  }
}
