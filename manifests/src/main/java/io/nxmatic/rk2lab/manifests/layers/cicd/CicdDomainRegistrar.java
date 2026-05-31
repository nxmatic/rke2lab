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
      public void synthesizeSystemdUnits(
          SystemdChart systemdChart,
          io.nxmatic.rk2lab.manifests.layers.common.SystemdSynthesisContext context) {
        super.synthesizeSystemdUnits(systemdChart, context);
        // Note: manifest directory is "tekton-pipelines", but secrets use domain ID "cicd"
        new SystemdUnitSynthesizer(systemdChart, "tekton-pipelines", context).manifestInstaller();
        new SystemdUnitSynthesizer(systemdChart, context.domainCatalog().cicd(), context)
            .secretsInstaller();
      }
    };
  }
}
