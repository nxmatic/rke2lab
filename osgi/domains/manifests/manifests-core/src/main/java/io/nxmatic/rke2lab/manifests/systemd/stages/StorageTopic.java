package io.nxmatic.rke2lab.manifests.systemd.stages;

import io.nxmatic.rke2lab.manifests.SystemdSynthesisContext;
import io.nxmatic.rke2lab.pipeline.Topic;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdChart;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService.ServiceType;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService.StandardStream;
import io.nxmatic.rke2lab.systemd.contract.SystemdUnitId;
import java.util.function.Supplier;

/**
 * Storage and system stage: filesystem configuration, ZFS, DBus, and kubeconfig generation. An
 * EFFECT topic — mutates the chart, produces no output for the accumulator, so it has no sink.
 * Reads the flox-install, bootstrap-env and install services through {@code Supplier} read-faces
 * (never by holding the tools/rke2-install topics).
 *
 * <p>Package-private stage builder for synthesis pipeline. See docs/fluent-pipeline-grammar.adoc.
 */
public final class StorageTopic implements Topic.Execution {

  private final Supplier<SystemdChart> systemdChart;
  private final Supplier<SystemdSynthesisContext> context;
  private final Supplier<SystemdService> floxInstall;
  private final Supplier<SystemdService> bootstrapEnv;
  private final Supplier<SystemdService> install;

  public StorageTopic(
      Supplier<SystemdChart> systemdChart,
      Supplier<SystemdSynthesisContext> context,
      Supplier<SystemdService> floxInstall,
      Supplier<SystemdService> bootstrapEnv,
      Supplier<SystemdService> install) {
    this.systemdChart = systemdChart;
    this.context = context;
    this.floxInstall = floxInstall;
    this.bootstrapEnv = bootstrapEnv;
    this.install = install;
  }

  @Override
  public String role() {
    return "storage and system";
  }

  public StorageTopic remountShared() {
    final SystemdChart systemdChart = this.systemdChart.get();
    new SystemdService(systemdChart, "rke2lab-remount-shared")
        .description("Remount root filesystem as shared for RKE2")
        .defaultDependencies(false)
        .before("local-fs.target")
        .type(ServiceType.ONESHOT)
        .execStart("/usr/bin/mount --make-rshared /")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .wantedBy("local-fs.target");
    return this;
  }

  public StorageTopic containerdZfsMountConfig() {
    final SystemdChart systemdChart = this.systemdChart.get();
    final SystemdSynthesisContext context = this.context.get();
    new SystemdService(systemdChart, "rke2lab-containerd-zfs-mount-config")
        .description("Configure containerd for ZFS mounts")
        .after(
            "local-fs.target",
            bootstrapEnv.get().getUnitFileName(),
            floxInstall.get().getUnitFileName(),
            install.get().getUnitFileName())
        .requires(
            bootstrapEnv.get().getUnitFileName(),
            floxInstall.get().getUnitFileName(),
            install.get().getUnitFileName())
        .before("rke2-server.service", "rke2-agent.service")
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-configure-containerd-zfs-mount.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .partOf(context.bootstrapTarget().getUnitFileName())
        .wantedBy(context.bootstrapTarget().getUnitFileName());
    return this;
  }

  public StorageTopic dbusTcpSystemBus() {
    final SystemdChart systemdChart = this.systemdChart.get();
    final SystemdSynthesisContext context = this.context.get();
    new SystemdService(systemdChart, SystemdUnitId.DBUS_TCP_SYSTEM_BUS.bareName())
        .description("Expose DBus system bus over TCP for RKE2Lab")
        .after("dbus.service")
        .type(ServiceType.ONESHOT)
        .execStart(
            "/srv/host/systemd-scripts.d/" + SystemdUnitId.DBUS_TCP_SYSTEM_BUS.bareName() + ".sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .partOf(context.rke2labTarget().getUnitFileName())
        .wantedBy(context.rke2labTarget().getUnitFileName());
    return this;
  }

  public StorageTopic zfsEarlyUmount() {
    final SystemdChart systemdChart = this.systemdChart.get();
    new SystemdService(systemdChart, "zfs-early-umount")
        .description("Early unmount of ZFS filesystems before shutdown")
        .defaultDependencies(false)
        .before("umount.target")
        .conflicts("umount.target")
        .type(ServiceType.ONESHOT)
        .execStart("/usr/sbin/zfs unmount -a")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .wantedBy("umount.target");
    return this;
  }

  public StorageTopic vipKubeconfig() {
    final SystemdChart systemdChart = this.systemdChart.get();
    final SystemdSynthesisContext context = this.context.get();
    new SystemdService(systemdChart, "rke2lab-vip-kubeconfig")
        .description("Generate VIP-enabled kubeconfig for cluster access")
        .after(
            "local-fs.target",
            bootstrapEnv.get().getUnitFileName(),
            floxInstall.get().getUnitFileName(),
            "rke2-server.service")
        .requires(
            bootstrapEnv.get().getUnitFileName(),
            floxInstall.get().getUnitFileName(),
            "rke2-server.service")
        .conditionPathExists(
            "/srv/host/systemd-scripts.d/rke2lab-vip-kubeconfig.sh",
            "/etc/rancher/rke2/rke2.yaml",
            "/var/lib/rancher/rke2/.flox/env")
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-vip-kubeconfig.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .partOf(context.manifestsTarget().getUnitFileName())
        .wantedBy(context.manifestsTarget().getUnitFileName());
    return this;
  }
}
