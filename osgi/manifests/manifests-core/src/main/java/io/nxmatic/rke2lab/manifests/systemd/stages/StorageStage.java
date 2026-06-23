package io.nxmatic.rke2lab.manifests.systemd.stages;

import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.ServiceType;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.StandardStream;
import io.nxmatic.rke2lab.manifests.SystemdSynthesisContext;
import io.nxmatic.rke2lab.systemd.port.SystemdUnitId;

/**
 * Storage and system stage: filesystem configuration, ZFS, DBus, and kubeconfig generation.
 *
 * <p>Package-private stage builder for synthesis pipeline. See docs/fluent-pipeline-grammar.adoc.
 */
public final class StorageStage {

  private final SystemdChart systemdChart;
  private final SystemdSynthesisContext context;
  private final ToolsStage toolsStage;
  private final BootstrapStage bootstrapStage;

  public StorageStage(
      SystemdChart systemdChart,
      SystemdSynthesisContext context,
      ToolsStage toolsStage,
      BootstrapStage bootstrapStage) {
    this.systemdChart = systemdChart;
    this.context = context;
    this.toolsStage = toolsStage;
    this.bootstrapStage = bootstrapStage;
  }

  public StorageStage remountShared() {
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

  public StorageStage containerdZfsMountConfig() {
    new SystemdService(systemdChart, "rke2lab-containerd-zfs-mount-config")
        .description("Configure containerd for ZFS mounts")
        .after(
            "local-fs.target",
            bootstrapStage.getBootstrapEnvService().getUnitFileName(),
            toolsStage.getFloxInstallService().getUnitFileName(),
            bootstrapStage.getInstallService().getUnitFileName())
        .requires(
            bootstrapStage.getBootstrapEnvService().getUnitFileName(),
            toolsStage.getFloxInstallService().getUnitFileName(),
            bootstrapStage.getInstallService().getUnitFileName())
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

  public StorageStage dbusTcpSystemBus() {
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

  public StorageStage zfsEarlyUmount() {
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

  public StorageStage vipKubeconfig() {
    new SystemdService(systemdChart, "rke2lab-vip-kubeconfig")
        .description("Generate VIP-enabled kubeconfig for cluster access")
        .after(
            "local-fs.target",
            bootstrapStage.getBootstrapEnvService().getUnitFileName(),
            toolsStage.getFloxInstallService().getUnitFileName(),
            "rke2-server.service")
        .requires(
            bootstrapStage.getBootstrapEnvService().getUnitFileName(),
            toolsStage.getFloxInstallService().getUnitFileName(),
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
