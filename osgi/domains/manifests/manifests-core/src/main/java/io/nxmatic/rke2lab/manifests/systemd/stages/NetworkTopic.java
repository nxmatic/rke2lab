package io.nxmatic.rke2lab.manifests.systemd.stages;

import io.nxmatic.rke2lab.manifests.SystemdSynthesisContext;
import io.nxmatic.rke2lab.pipeline.Topic;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdChart;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService.ServiceType;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService.StandardStream;
import java.util.function.Supplier;

/**
 * Network infrastructure stage: network configuration, waiting, debugging, and route cleanup. An
 * EFFECT topic — it mutates the chart, produces no output for the accumulator, so it has no sink.
 * Reads the rke2-install service through a {@code Supplier} read-face.
 *
 * <p>Package-private stage builder for synthesis pipeline. See docs/fluent-pipeline-grammar.adoc.
 */
public final class NetworkTopic implements Topic.Execution {

  private final SystemdChart systemdChart;
  private final SystemdSynthesisContext context;
  private final Supplier<SystemdService> install;

  public NetworkTopic(
      SystemdChart systemdChart,
      SystemdSynthesisContext context,
      Supplier<SystemdService> install) {
    this.systemdChart = systemdChart;
    this.context = context;
    this.install = install;
  }

  @Override
  public String role() {
    return "network infrastructure";
  }

  public NetworkTopic routeCleanup() {
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

  public NetworkTopic networkConfig() {
    new SystemdService(systemdChart, "rke2lab-network-config")
        .description("RKE2Lab Network Configuration Service")
        .after("systemd-networkd.service", "cloud-init.service")
        .wants("systemd-networkd.service")
        .before(install.get().getUnitFileName())
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-network-config.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .partOf(context.networkTarget().getUnitFileName())
        .wantedBy(context.networkTarget().getUnitFileName());
    return this;
  }

  public NetworkTopic networkWait() {
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

  public NetworkTopic networkDebug() {
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
