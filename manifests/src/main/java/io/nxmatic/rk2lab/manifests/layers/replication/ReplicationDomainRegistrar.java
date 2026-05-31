// @codebase
package io.nxmatic.rk2lab.manifests.layers.replication;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import io.nxmatic.rk2lab.manifests.systemd.SystemdUnitSynthesizer;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import java.util.List;

public final class ReplicationDomainRegistrar implements LayerDomainRegistrar {

  private final ManifestDomainCatalog manifestDomainCatalog =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

  @Override
  public LayerDomain domain() {
    return new LayerDomain(
        manifestDomainCatalog.replication(),
        List.of(
            new io.nxmatic.rk2lab.manifests.layers.replication
                .ReplicationReplicatorManifestUnit())) {
      @Override
      public void synthesizeSystemdUnits(SystemdChart systemdChart) {
        super.synthesizeSystemdUnits(systemdChart);
        var synthesizer =
            new SystemdUnitSynthesizer(systemdChart, manifestDomainCatalog.replication());
        synthesizer.manifestInstaller();
      }
    };
  }
}
