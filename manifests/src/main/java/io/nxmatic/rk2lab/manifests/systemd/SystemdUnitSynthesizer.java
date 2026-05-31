// @codebase
package io.nxmatic.rk2lab.manifests.systemd;

import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.ServiceType;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.StandardStream;

/**
 * Utility methods for synthesizing common systemd unit patterns in rke2lab.
 *
 * <p>Provides factory methods for domain manifest installer services and other recurring unit
 * patterns to ensure consistency across domains.
 */
public final class SystemdUnitSynthesizer {

  private SystemdUnitSynthesizer() {
    // Utility class
  }

  /**
   * Synthesizes a manifest installer service for a domain.
   *
   * <p>Manifest installer services follow a common pattern:
   *
   * <ul>
   *   <li>Type=oneshot with RemainAfterExit=true
   *   <li>After/Requires rke2-server.service
   *   <li>ExecStart=/srv/host/systemd-scripts.d/rke2lab-manifests-install.sh {domainId}
   *   <li>WantedBy=rke2lab.target
   * </ul>
   *
   * <p>The domain ID (e.g., "replication") is passed to the install script, which symlinks
   * manifests from /srv/host/rke2-manifests.d/{domainId}/ to
   * /var/lib/rancher/rke2/server/manifests/. RKE2 watches that directory and auto-applies.
   *
   * @param systemdChart the systemd chart to add the unit to
   * @param domainId the domain ID (e.g., "replication", "cluster-api")
   * @return the created SystemdService for further customization
   */
  public static SystemdService synthesizeManifestInstaller(
      SystemdChart systemdChart, String domainId) {
    final String unitFileName = "rke2lab-" + domainId + "-manifests.service";
    if (!SystemdUnitCatalog.isKnownUnit(unitFileName)) {
      throw new IllegalStateException(
          "Unit file name not in SystemdUnitCatalog: "
              + unitFileName
              + " (domain="
              + domainId
              + ")");
    }

    return new SystemdService(systemdChart, domainId + "-manifests")
        .description("Install RKE2Lab " + domainId + " manifests from host share (post-server)")
        .requiresMountsFor("/srv/host/systemd-units.d", "/srv/host")
        .after("local-fs.target", "rke2-server.service")
        .requires("rke2-server.service")
        .conditionPathExists(
            "/srv/host/systemd-scripts.d/rke2lab-manifests-install.sh",
            "/srv/host/rke2-manifests.d/" + domainId)
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-manifests-install.sh " + domainId)
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .wantedBy(SystemdUnitCatalog.RKE2LAB_TARGET);
  }
}
