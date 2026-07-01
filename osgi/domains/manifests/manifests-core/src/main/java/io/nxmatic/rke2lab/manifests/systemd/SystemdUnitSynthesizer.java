// @codebase
package io.nxmatic.rke2lab.manifests.systemd;

import io.nxmatic.rke2lab.manifests.InstallPhase;
import io.nxmatic.rke2lab.manifests.SystemdSynthesisContext;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdChart;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdUnit;
import java.util.List;

/**
 * Domain-scoped systemd unit synthesizer.
 *
 * <p>One installer service is emitted per (domain, phase): the phase determines the systemd
 * ordering (before/after rke2-server, after a readiness gate), derived centrally here rather than
 * hand-written per unit. See docs/rke2-install-phases.adoc.
 */
public final class SystemdUnitSynthesizer {

  private static final String UNIT_PREFIX = "rke2lab-";
  private static final String SCRIPTS_DIR = "/srv/host/systemd-scripts.d";
  private static final String INSTALL_SCRIPT = SCRIPTS_DIR + "/rke2lab-manifests-install.sh";

  private final SystemdChart systemdChart;
  private final String domainId;
  private final SystemdSynthesisContext context;

  public SystemdUnitSynthesizer(
      SystemdChart systemdChart, String domainId, SystemdSynthesisContext context) {
    this.systemdChart = systemdChart;
    this.domainId = domainId;
    this.context = context;
  }

  private String unitId(String suffix) {
    return UNIT_PREFIX + domainId + "-" + suffix;
  }

  /**
   * Synthesizes the installer service for one phase of this domain. It links the given manifest
   * sub-paths (one {@code ExecStart} each) via the shared install script; ordering is derived from
   * the phase. When the phase has a readiness gate, the gate service is provisioned
   * deterministically (idempotent — shared across domains that target the same phase).
   *
   * @param phase the lifecycle phase these manifests attach to
   * @param manifestSubpaths the unit ids (e.g. {@code "networking/cilium-config"}) to link
   * @return the created installer SystemdService
   */
  public SystemdService phaseInstaller(InstallPhase phase, List<String> manifestSubpaths) {
    final SystemdService installer =
        SystemdService.oneshotInstaller(systemdChart, installerUnitId(phase))
            .description(
                "Install RKE2Lab " + domainId + " manifests (" + phase.name() + ") from host share")
            .conditionPathExists(INSTALL_SCRIPT);
    for (String subpath : manifestSubpaths) {
      installer.execStart(INSTALL_SCRIPT + " " + subpath);
    }
    applyPhaseOrdering(installer, phase);
    return installer;
  }

  /** Phase-specific unit name: POST_SERVER keeps the clean name, others get an infix. */
  private String installerUnitId(InstallPhase phase) {
    final String infix =
        switch (phase) {
          case PRE_SERVER -> "pre-";
          case POST_SERVER -> "";
          case POST_CNI_READY -> "cni-";
          case POST_OPERATOR_READY -> "operator-";
        };
    return unitId(infix + "manifests");
  }

  private void applyPhaseOrdering(SystemdService installer, InstallPhase phase) {
    final String target = context.targetFor(phase).getUnitFileName();

    if (phase.isPreServer()) {
      // Runs before rke2-server, gated on the install service that lays down the host share.
      final SystemdUnit install = requireUnit("rke2lab-install");
      installer
          .after("local-fs.target", install.getUnitFileName())
          .requires(install.getUnitFileName())
          .before("rke2-server.service")
          .partOf(target)
          .wantedBy(target);
      return;
    }

    // All post-server phases run after the API server; gated phases also wait on their gate.
    installer.after("local-fs.target", "rke2-server.service").requires("rke2-server.service");
    phase.readyGate().ifPresent(gate -> installer.after(ensureReadyGate(gate).getUnitFileName()));
    installer.partOf(target).wantedBy(target);
  }

  /**
   * Deterministically provisions the readiness-gate service for a gated phase. The shell logic
   * lives in a script we ship; we own its systemd wrapper too, so it must exist in the chart.
   * Idempotent: several domains targeting the same phase share one gate service.
   */
  private SystemdUnit ensureReadyGate(String gateUnitFile) {
    final String gateUnitId = gateUnitFile.replace(".service", "");
    return systemdChart.findUnit(gateUnitId).orElseGet(() -> synthesizeReadyGate(gateUnitId));
  }

  private SystemdUnit synthesizeReadyGate(String gateUnitId) {
    return SystemdService.oneshotInstaller(systemdChart, gateUnitId)
        .description("RKE2Lab readiness gate: " + gateUnitId)
        .after("local-fs.target", "rke2-server.service")
        .requires("rke2-server.service")
        .conditionPathExists(SCRIPTS_DIR + "/" + gateUnitId + ".sh")
        .execStart(SCRIPTS_DIR + "/" + gateUnitId + ".sh")
        .partOf(context.manifestsTarget().getUnitFileName())
        .wantedBy(context.manifestsTarget().getUnitFileName());
  }

  private SystemdUnit requireUnit(String unitId) {
    return systemdChart
        .findUnit(unitId)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    unitId
                        + " not found in systemd chart — ensure BootstrapInfrastructureSynthesizer"
                        + " runs first"));
  }

  /** Synthesizes the secrets installer service for this domain (post-server). */
  public SystemdService secretsInstaller() {
    return SystemdService.oneshotInstaller(systemdChart, unitId("secrets"))
        .description("Apply RKE2Lab " + domainId + " secrets")
        .after("local-fs.target", "rke2-server.service")
        .requires("rke2-server.service")
        .conditionPathExists(SCRIPTS_DIR + "/rke2lab-layer-secrets-apply.sh")
        .execStart(SCRIPTS_DIR + "/rke2lab-layer-secrets-apply.sh " + domainId)
        .partOf(context.secretsTarget().getUnitFileName())
        .wantedBy(context.secretsTarget().getUnitFileName());
  }
}
