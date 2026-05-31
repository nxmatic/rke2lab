// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.runtime.cloudinit.CloudConfigManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.runtime.daemonset.RuntimeDaemonsetScriptPolicyManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.runtime.env.RKE2LabEnvConfigManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.runtime.flox.FloxRuntimeManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.runtime.libexec.RuntimeSystemdLibexecPlaceholderManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.runtime.rke2.RKE2ConfigManifestUnit;
import io.nxmatic.rk2lab.manifests.systemd.SystemdUnitSynthesizer;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import java.util.List;

public final class RuntimeDomainRegistrar implements LayerDomainRegistrar {

  private static final ManifestDomainCatalog CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().build();

  @Override
  public LayerDomain domain() {
    return new LayerDomain(
        CATALOG.runtime(),
        List.of(CATALOG.cluster(), CATALOG.storage(), CATALOG.replication()),
        List.of(
            new RKE2LabEnvConfigManifestUnit(),
            new RuntimeSystemdLibexecPlaceholderManifestUnit(),
            new RKE2ConfigManifestUnit(),
            new CloudConfigManifestUnit(),
            new RuntimeDaemonsetScriptPolicyManifestUnit(),
            new FloxRuntimeManifestUnit())) {
      @Override
      public void synthesizeSystemdUnits(
          SystemdChart systemdChart,
          io.nxmatic.rk2lab.manifests.layers.common.SystemdSynthesisContext context) {
        super.synthesizeSystemdUnits(systemdChart, context);
        var synthesizer =
            new SystemdUnitSynthesizer(systemdChart, context.domainCatalog().runtime(), context);
        synthesizer.manifestInstaller();
        synthesizer.secretsInstaller();
      }
    };
  }
}
