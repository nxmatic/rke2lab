// @codebase
package io.nxmatic.rk2lab.manifests.layers.networking;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.ServiceType;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.StandardStream;
import java.util.List;
import org.cdk8s.Chart;

public final class CiliumConfigManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.NETWORKING + "/cilium-config";

  public CiliumConfigManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new CiliumConfigLayer(chart, "layer-networking-cilium-config");
  }

  @Override
  public void synthesizeSystemdUnits(
      SystemdChart systemdChart,
      io.nxmatic.rk2lab.manifests.layers.common.SystemdSynthesisContext context) {
    // Special case: cilium-config runs BEFORE rke2-server (not after like other manifests)
    // Need to lookup the install service since it's created by BootstrapInfrastructureSynthesizer
    var installService = systemdChart.findUnit("rke2lab-install");
    if (installService == null) {
      throw new IllegalStateException(
          "rke2lab-install service not found in systemd chart - ensure BootstrapInfrastructureSynthesizer runs first");
    }

    new SystemdService(systemdChart, "rke2lab-cilium-config-manifests")
        .description("Install RKE2Lab Cilium config manifests before server start")
        .requiresMountsFor("/srv/host/systemd-units.d", "/srv/host")
        .after("local-fs.target", installService.getUnitFileName())
        .requires(installService.getUnitFileName())
        .before("rke2-server.service")
        .conditionPathExists(
            "/srv/host/systemd-scripts.d/rke2lab-manifests-install.sh",
            "/srv/host/rke2-manifests.d")
        .type(ServiceType.ONESHOT)
        .execStart(
            "/srv/host/systemd-scripts.d/rke2lab-manifests-install.sh networking/cilium-config")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .wantedBy(context.rke2labTarget().getUnitFileName());
  }
}
