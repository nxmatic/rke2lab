// @codebase
package io.nxmatic.rk2lab.manifests.layers.networking;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.systemd.SystemdUnitCatalog;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.ServiceType;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.StandardStream;
import java.util.List;
import org.cdk8s.Chart;

public final class CiliumConfigManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "networking/cilium-config";

  public CiliumConfigManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new CiliumConfigLayer(chart, "layer-networking-cilium-config");
  }

  @Override
  public void synthesizeSystemdUnits(SystemdChart systemdChart) {
    // Special case: cilium-config runs BEFORE rke2-server (not after like other manifests)
    new SystemdService(systemdChart, "cilium-config-manifests")
        .description("Install RKE2Lab Cilium config manifests before server start")
        .requiresMountsFor("/srv/host/systemd-units.d", "/srv/host")
        .after("local-fs.target", SystemdUnitCatalog.INSTALL)
        .requires(SystemdUnitCatalog.INSTALL)
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
        .wantedBy(SystemdUnitCatalog.RKE2LAB_TARGET);
  }
}
