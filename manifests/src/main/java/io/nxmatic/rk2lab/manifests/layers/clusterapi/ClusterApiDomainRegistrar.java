package io.nxmatic.rk2lab.manifests.layers.clusterapi;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import io.nxmatic.rk2lab.manifests.systemd.SystemdUnitSynthesizer;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.ServiceType;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.StandardStream;
import java.util.List;

public final class ClusterApiDomainRegistrar implements LayerDomainRegistrar {

  private static final ManifestDomainCatalog CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().build();

  @Override
  public LayerDomain domain() {
    return new LayerDomain(
        CATALOG.clusterApi(),
        List.of(CATALOG.certManager()),
        List.of(new ClusterApiOperatorManifestUnit(), new IncusIdentitySecretManifestUnit())) {
      @Override
      public void synthesizeSystemdUnits(
          SystemdChart systemdChart,
          io.nxmatic.rk2lab.manifests.layers.common.SystemdSynthesisContext context) {
        super.synthesizeSystemdUnits(systemdChart, context);
        var synthesizer =
            new SystemdUnitSynthesizer(systemdChart, context.domainCatalog().clusterApi(), context);
        var clusterApiManifests = synthesizer.manifestInstaller();

        // Staged image-state manifest installer (runs after cluster-api-manifests)
        new SystemdService(systemdChart, "rke2lab-cluster-api-image-state-apply")
            .description("Install Cluster API staged image-state manifest (via RKE2 auto-deploy)")
            .documentation(
                "file:///srv/host/docs/staged-post-cluster-resources.adoc",
                "file:///srv/host/docs/manifest-apply-flow.adoc")
            .requiresMountsFor("/srv/host/systemd-units.d", "/srv/host")
            .after("local-fs.target", "rke2-server.service", clusterApiManifests.getUnitFileName())
            .requires("rke2-server.service", clusterApiManifests.getUnitFileName())
            .conditionPathExists(
                "/srv/host/systemd-scripts.d/rke2lab-manifests-install.sh",
                "/srv/host/rke2-manifests.d/cluster-api/staged")
            .type(ServiceType.ONESHOT)
            .execStart(
                "/srv/host/systemd-scripts.d/rke2lab-manifests-install.sh cluster-api/staged")
            .remainAfterExit(true)
            .standardOutput(StandardStream.JOURNAL)
            .standardError(StandardStream.JOURNAL)
            .wantedBy(context.manifestsTarget().getUnitFileName());
      }
    };
  }
}
