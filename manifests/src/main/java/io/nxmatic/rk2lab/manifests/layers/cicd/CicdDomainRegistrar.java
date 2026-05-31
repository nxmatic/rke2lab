// @codebase
package io.nxmatic.rk2lab.manifests.layers.cicd;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import io.nxmatic.rk2lab.manifests.systemd.SystemdUnitSynthesizer;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import java.util.List;

public final class CicdDomainRegistrar implements LayerDomainRegistrar {

  private static final ManifestDomainCatalog CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().build();

  @Override
  public LayerDomain domain() {
    return new LayerDomain(
        CATALOG.cicd(),
        List.of(CATALOG.gitops()),
        List.of(new TektonPipelinesManifestUnit(), new TektonDashboardManifestUnit())) {
      @Override
      public void synthesizeSystemdUnits(SystemdChart systemdChart) {
        super.synthesizeSystemdUnits(systemdChart);
        // Note: This synthesizes tekton-pipelines-manifests.service
        SystemdUnitSynthesizer.synthesizeManifestInstaller(systemdChart, "tekton-pipelines");
      }
    };
  }
}
