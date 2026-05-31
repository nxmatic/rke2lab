package io.nxmatic.rk2lab.manifests.layers.clusterapi;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import io.nxmatic.rk2lab.manifests.systemd.SystemdUnitSynthesizer;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import java.util.List;

public final class ClusterApiDomainRegistrar implements LayerDomainRegistrar {

  private static final ManifestDomainCatalog CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().build();

  @Override
  public LayerDomain domain() {
    return new LayerDomain(
        CATALOG.clusterApi(),
        List.of(),
        List.of(new ClusterApiOperatorManifestUnit(), new IncusIdentitySecretManifestUnit())) {
      @Override
      public void synthesizeSystemdUnits(SystemdChart systemdChart) {
        super.synthesizeSystemdUnits(systemdChart);
        SystemdUnitSynthesizer.synthesizeManifestInstaller(systemdChart, CATALOG.clusterApi());
      }
    };
  }
}
