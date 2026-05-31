// @codebase
package io.nxmatic.rk2lab.manifests.systemd;

import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.ServiceType;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.StandardStream;

/**
 * Domain-scoped systemd unit synthesizer.
 *
 * <p>Each domain registrar creates an instance with its systemdChart and domainId, then calls
 * synthesis methods without repeating parameters.
 *
 * <p>Example:
 *
 * <pre>{@code
 * var synthesizer = new SystemdUnitSynthesizer(systemdChart, CATALOG.gitops());
 * synthesizer.manifestInstaller();
 * synthesizer.secretsInstaller();
 * }</pre>
 */
public final class SystemdUnitSynthesizer {

  private final SystemdChart systemdChart;
  private final String domainId;
  private final io.nxmatic.rk2lab.manifests.layers.common.SystemdSynthesisContext context;

  public SystemdUnitSynthesizer(
      SystemdChart systemdChart,
      String domainId,
      io.nxmatic.rk2lab.manifests.layers.common.SystemdSynthesisContext context) {
    this.systemdChart = systemdChart;
    this.domainId = domainId;
    this.context = context;
  }

  /**
   * Synthesizes a manifest installer service for this domain.
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
   * @return the created SystemdService for further customization
   */
  public SystemdService manifestInstaller() {
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
        .wantedBy(context.rke2labTarget().getUnitFileName());
  }

  /**
   * Synthesizes a secrets installer service for this domain.
   *
   * <p>Secrets installer services apply secrets from the host share after RKE2 server starts:
   *
   * <ul>
   *   <li>Type=oneshot with RemainAfterExit=true
   *   <li>After/Requires rke2-server.service
   *   <li>ExecStart=/srv/host/systemd-scripts.d/rke2lab-layer-secrets-apply.sh {domainId}
   *   <li>WantedBy=rke2lab.target
   * </ul>
   *
   * @return the created SystemdService for further customization
   */
  public SystemdService secretsInstaller() {
    return new SystemdService(systemdChart, domainId + "-secrets")
        .description("Apply RKE2Lab " + domainId + " secrets")
        .requiresMountsFor("/srv/host/systemd-units.d", "/srv/host")
        .after("local-fs.target", "rke2-server.service")
        .requires("rke2-server.service")
        .conditionPathExists("/srv/host/systemd-scripts.d/rke2lab-layer-secrets-apply.sh")
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-layer-secrets-apply.sh " + domainId)
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .wantedBy(context.rke2labTarget().getUnitFileName());
  }
}
