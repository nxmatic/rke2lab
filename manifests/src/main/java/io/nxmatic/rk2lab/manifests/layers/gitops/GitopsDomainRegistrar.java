// @codebase
package io.nxmatic.rk2lab.manifests.layers.gitops;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.api.ManifestDomainPolicy;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnit;
import io.nxmatic.rk2lab.manifests.systemd.SystemdUnitSynthesizer;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import java.util.ArrayList;
import java.util.List;

public final class GitopsDomainRegistrar implements LayerDomainRegistrar {

  private static final ManifestDomainCatalog CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

  @Override
  public LayerDomain domain() {
    return domain(ManifestDomainPolicy.builder().build());
  }

  @Override
  public LayerDomain domain(ManifestDomainPolicy policy) {
    final List<ManifestUnit> units = new ArrayList<>();
    units.add(new FluxOperatorManifestUnit());
    units.add(new FluxInstanceManifestUnit());
    units.add(new SopsAgeSecretManifestUnit());
    units.add(new FluxRootManifestUnit());

    if (policy.isEnabled(CATALOG.porch())) {
      units.add(new PorchResourcesManifestUnit());
    }

    return new LayerDomain(CATALOG.gitops(), List.of(CATALOG.replication()), units) {
      @Override
      public void synthesizeSystemdUnits(
          SystemdChart systemdChart,
          io.nxmatic.rk2lab.manifests.layers.common.SystemdSynthesisContext context) {
        super.synthesizeSystemdUnits(systemdChart, context);
        var synthesizer =
            new SystemdUnitSynthesizer(systemdChart, context.domainCatalog().gitops(), context);
        synthesizer.manifestInstaller();
        synthesizer.secretsInstaller();
      }
    };
  }
}
