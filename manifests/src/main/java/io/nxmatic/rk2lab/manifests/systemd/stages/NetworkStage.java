package io.nxmatic.rk2lab.manifests.systemd.stages;

import io.nxmatic.rk2lab.manifests.layers.common.SystemdSynthesisContext;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.ServiceType;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.StandardStream;

/**
 * Network infrastructure stage: network configuration, waiting, debugging, and route cleanup.
 *
 * <p>Package-private stage builder for synthesis pipeline. See docs/fluent-pipeline-grammar.adoc.
 */
public final class NetworkStage {

  private final SystemdChart systemdChart;
  private final SystemdSynthesisContext context;
  private final BootstrapStage bootstrapStage;

  public NetworkStage(
      SystemdChart systemdChart, SystemdSynthesisContext context, BootstrapStage bootstrapStage) {
    this.systemdChart = systemdChart;
    this.context = context;
    this.bootstrapStage = bootstrapStage;
  }

  public NetworkStage routeCleanup() {
    new SystemdService(systemdChart, "rke2lab-route-cleanup")
        .description("Clean up conflicting routes for RKE2Lab")
        .after("network-online.target")
        .before(context.networkTarget().getUnitFileName())
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-route-cleanup.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .partOf(context.networkTarget().getUnitFileName())
        .wantedBy(context.networkTarget().getUnitFileName());
    return this;
  }

  public NetworkStage networkConfig() {
    new SystemdService(systemdChart, "rke2lab-network-config")
        .description("RKE2Lab Network Configuration Service")
        .after("systemd-networkd.service", "cloud-init.service")
        .wants("systemd-networkd.service")
        .before(bootstrapStage.getInstallService().getUnitFileName())
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-network-config.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .partOf(context.networkTarget().getUnitFileName())
        .wantedBy(context.networkTarget().getUnitFileName());
    return this;
  }

  public NetworkStage networkWait() {
    new SystemdService(systemdChart, "rke2lab-network-wait")
        .description("Wait for RKE2Lab network readiness")
        .after("network-online.target")
        .wants("network-online.target")
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-network-wait.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .partOf(context.networkTarget().getUnitFileName())
        .wantedBy(context.networkTarget().getUnitFileName());
    return this;
  }

  public NetworkStage networkDebug() {
    new SystemdService(systemdChart, "rke2lab-network-debug")
        .description("RKE2Lab network diagnostics")
        .after(context.networkTarget().getUnitFileName())
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-network-debug.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .partOf(context.networkTarget().getUnitFileName())
        .wantedBy(context.networkTarget().getUnitFileName());
    return this;
  }
}
