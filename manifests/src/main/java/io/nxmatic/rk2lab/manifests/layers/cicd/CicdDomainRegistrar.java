// @codebase
package io.nxmatic.rk2lab.manifests.layers.cicd;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import io.nxmatic.rk2lab.manifests.systemd.SystemdUnitSynthesizer;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.ServiceType;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.StandardStream;
import java.util.List;

public final class CicdDomainRegistrar implements LayerDomainRegistrar {

  private static final ManifestDomainCatalog CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().build();

  @Override
  public LayerDomain domain() {
    return new LayerDomain(
        CATALOG.cicd(),
        List.of(CATALOG.gitops(), CATALOG.platform()),
        List.of(new TektonPipelinesManifestUnit(), new TektonDashboardManifestUnit())) {
      @Override
      public void synthesizeSystemdUnits(
          SystemdChart systemdChart,
          io.nxmatic.rk2lab.manifests.layers.common.SystemdSynthesisContext context) {
        super.synthesizeSystemdUnits(systemdChart, context);
        // Note: manifest directory is "cicd/tekton-pipelines", but secrets use domain ID "cicd"
        // Create custom manifest installer with proper path
        new SystemdService(systemdChart, "rke2lab-tekton-pipelines-manifests")
            .description("Install RKE2Lab tekton-pipelines manifests from host share (post-server)")
            .requiresMountsFor("/srv/host/systemd-units.d", "/srv/host")
            .after("local-fs.target", "rke2-server.service")
            .requires("rke2-server.service")
            .conditionPathExists(
                "/srv/host/systemd-scripts.d/rke2lab-manifests-install.sh",
                "/srv/host/rke2-manifests.d/cicd/tekton-pipelines")
            .type(ServiceType.ONESHOT)
            .execStart(
                "/srv/host/systemd-scripts.d/rke2lab-manifests-install.sh cicd/tekton-pipelines")
            .remainAfterExit(true)
            .standardOutput(StandardStream.JOURNAL)
            .standardError(StandardStream.JOURNAL)
            .wantedBy(context.manifestsTarget().getUnitFileName());
        new SystemdUnitSynthesizer(systemdChart, context.domainCatalog().cicd(), context)
            .secretsInstaller();
      }
    };
  }
}
