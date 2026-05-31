// @codebase
package io.nxmatic.rk2lab.manifests.layers.mesh;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import io.nxmatic.rk2lab.manifests.systemd.SystemdUnitSynthesizer;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import java.util.List;

public final class MeshDomainRegistrar implements LayerDomainRegistrar {

  private final ManifestDomainCatalog manifestDomainCatalog =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

  @Override
  public LayerDomain domain() {
    return new LayerDomain(
        manifestDomainCatalog.mesh(),
        List.of(manifestDomainCatalog.networking(), manifestDomainCatalog.replication()),
        List.of(
            new MeshSystemNamespaceManifestUnit(),
            new HeadscaleManifestUnit(),
            new TailscaleManifestUnit(),
            new HeadplaneManifestUnit())) {
      @Override
      public void synthesizeSystemdUnits(SystemdChart systemdChart) {
        super.synthesizeSystemdUnits(systemdChart);
        var synthesizer = new SystemdUnitSynthesizer(systemdChart, manifestDomainCatalog.mesh());
        synthesizer.manifestInstaller();
        synthesizer.secretsInstaller();
      }
    };
  }
}
