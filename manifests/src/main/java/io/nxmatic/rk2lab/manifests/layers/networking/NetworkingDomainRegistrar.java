// @codebase
package io.nxmatic.rk2lab.manifests.layers.networking;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import io.nxmatic.rk2lab.manifests.systemd.SystemdUnitSynthesizer;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import java.util.List;

public final class NetworkingDomainRegistrar implements LayerDomainRegistrar {

  private final ManifestDomainCatalog manifestDomainCatalog =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

  @Override
  public LayerDomain domain() {
    return new LayerDomain(
        manifestDomainCatalog.networking(),
        List.of(manifestDomainCatalog.runtime()),
        List.of(
            new CiliumConfigManifestUnit(),
            new CiliumAdvancedManifestUnit(),
            new EnvoyGatewayManifestUnit(),
            new KdnsManifestUnit())) {
      @Override
      public void synthesizeSystemdUnits(
          SystemdChart systemdChart,
          io.nxmatic.rk2lab.manifests.layers.common.SystemdSynthesisContext context) {
        super.synthesizeSystemdUnits(systemdChart, context);
        var synthesizer =
            new SystemdUnitSynthesizer(systemdChart, context.domainCatalog().networking(), context);
        synthesizer.manifestInstaller();

        // Cilium readiness service - waits for Cilium to be fully operational
        new io.nxmatic.rke2lab.cdk8s.systemd.SystemdService(systemdChart, "rke2lab-cilium-ready")
            .description("Wait for Cilium networking to be fully operational")
            .after("rke2-server.service", "rke2lab-networking-manifests.service")
            .requires("rke2-server.service")
            .requiresMountsFor("/srv/host/systemd-scripts.d")
            .conditionPathExists("/srv/host/systemd-scripts.d/rke2lab-cilium-ready.sh")
            .type(io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.ServiceType.ONESHOT)
            .execStart("/srv/host/systemd-scripts.d/rke2lab-cilium-ready.sh")
            .remainAfterExit(true)
            .standardOutput(io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.StandardStream.JOURNAL)
            .standardError(io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.StandardStream.JOURNAL)
            .partOf(context.manifestsTarget().getUnitFileName())
            .wantedBy(context.manifestsTarget().getUnitFileName());

        // Cilium operator scaling service - scales cilium-operator replicas based on control-plane
        // node count
        new io.nxmatic.rke2lab.cdk8s.systemd.SystemdService(
                systemdChart, "rke2lab-cilium-operator-scaling")
            .description("Scale Cilium operator replicas based on control-plane node count")
            .after("rke2-server.service", "rke2lab-cilium-ready.service")
            .requires("rke2-server.service", "rke2lab-cilium-ready.service")
            .requiresMountsFor("/srv/host/systemd-scripts.d")
            .conditionPathExists("/srv/host/systemd-scripts.d/rke2lab-cilium-operator-scaling.sh")
            .type(io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.ServiceType.ONESHOT)
            .execStart("/srv/host/systemd-scripts.d/rke2lab-cilium-operator-scaling.sh")
            .remainAfterExit(true)
            .standardOutput(io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.StandardStream.JOURNAL)
            .standardError(io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.StandardStream.JOURNAL)
            .partOf(context.manifestsTarget().getUnitFileName())
            .wantedBy(context.manifestsTarget().getUnitFileName());
      }
    };
  }
}
