// @codebase
package io.nxmatic.rk2lab.manifests.systemd;

import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.ServiceType;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdService.StandardStream;

/**
 * Synthesizes bootstrap and infrastructure systemd units.
 *
 * <p>These units are cross-cutting infrastructure that don't belong to any specific domain:
 * bootstrap environment, Nix/Flox installation, networking setup, storage configuration, etc.
 *
 * <p>Uses construct references for dependencies instead of string constants.
 */
public final class BootstrapInfrastructureSynthesizer {

  private final SystemdChart systemdChart;
  private final io.nxmatic.rk2lab.manifests.layers.common.SystemdSynthesisContext context;

  // Store construct references for dependency resolution
  private SystemdService nixInstallService;
  private SystemdService floxInstallService;
  private SystemdService bootstrapEnvService;
  private SystemdService installService;

  public BootstrapInfrastructureSynthesizer(
      SystemdChart systemdChart,
      io.nxmatic.rk2lab.manifests.layers.common.SystemdSynthesisContext context) {
    this.systemdChart = systemdChart;
    this.context = context;
  }

  /** Synthesizes all bootstrap and infrastructure units. */
  public void synthesizeAll() {
    // Nix & Flox (must be before bootstrap-env which depends on flox-install)
    nixInstall();
    floxInstall();

    // Bootstrap & installation
    bootstrapEnv();
    configInstall();
    install();
    systemdLink();

    // Cachix (after install, needs RKE2 Flox env)
    cachixWatchStore();

    // Networking infrastructure
    networkConfig();
    networkWait();
    networkDebug();
    routeCleanup();

    // Storage & system
    remountShared();
    containerdZfsMountConfig();
    dbusTcpSystemBus();
    zfsEarlyUmount();
    vipKubeconfig();

    // Note: rke2lab.target dependencies are set in DefaultManifestSynthesisService
    // after all targets and services are created
  }

  private void bootstrapEnv() {
    bootstrapEnvService =
        new SystemdService(systemdChart, "rke2lab-bootstrap-env")
            .description("RKE2Lab bootstrap environment (Nix + Flox + nocloud)")
            .documentation("https://github.com/nxmatic/rke2lab")
            .after(
                "network-online.target",
                "systemd-networkd.service",
                "local-fs.target",
                context.networkTarget().getUnitFileName(),
                context.toolsTarget().getUnitFileName(),
                floxInstallService.getUnitFileName())
            .wants(
                "network-online.target",
                "systemd-networkd.service",
                context.networkTarget().getUnitFileName())
            .requires(
                context.networkTarget().getUnitFileName(),
                context.toolsTarget().getUnitFileName(),
                floxInstallService.getUnitFileName())
            .requiresMountsFor(
                "/srv/host/systemd-scripts.d",
                "/srv/host/rke2lab-environment.d",
                "/srv/host/rke2lab-worktree.d")
            .conditionPathExists(
                "/srv/host/systemd-scripts.d/rke2lab-bootstrap-env.sh",
                "/srv/host/systemd-scripts.d/rke2lab-env-load.sh")
            .type(ServiceType.ONESHOT)
            .execStart("/srv/host/systemd-scripts.d/rke2lab-bootstrap-env.sh")
            .remainAfterExit(true)
            .standardOutput(StandardStream.JOURNAL)
            .standardError(StandardStream.JOURNAL)
            .partOf(context.bootstrapTarget().getUnitFileName())
            .wantedBy(context.bootstrapTarget().getUnitFileName());
  }

  private void install() {
    installService =
        new SystemdService(systemdChart, "rke2lab-install")
            .description("Run RKE2Lab Installation Script")
            .after(
                "network-online.target",
                "systemd-networkd.service",
                "local-fs.target",
                context.networkTarget().getUnitFileName(),
                context.toolsTarget().getUnitFileName(),
                bootstrapEnvService.getUnitFileName())
            .wants(
                "network-online.target",
                "systemd-networkd.service",
                context.networkTarget().getUnitFileName())
            .requires(
                context.networkTarget().getUnitFileName(),
                context.toolsTarget().getUnitFileName(),
                bootstrapEnvService.getUnitFileName())
            .requiresMountsFor("/srv/host/systemd-scripts.d")
            .conditionPathExists(
                "/srv/host/systemd-scripts.d/rke2lab-install.sh",
                "!/etc/systemd/system/rke2-server.service",
                "!/etc/systemd/system/rke2-agent.service")
            .type(ServiceType.ONESHOT)
            .execStartPre("/srv/host/systemd-scripts.d/rke2lab-install-pre.sh")
            .execStart("/srv/host/systemd-scripts.d/rke2lab-install.sh")
            .execStartPost("/srv/host/systemd-scripts.d/rke2lab-install-post.sh")
            .remainAfterExit(true)
            .standardOutput(StandardStream.JOURNAL)
            .standardError(StandardStream.JOURNAL)
            .partOf(context.bootstrapTarget().getUnitFileName())
            .wantedBy(context.bootstrapTarget().getUnitFileName());
  }

  private void systemdLink() {
    new SystemdService(systemdChart, "rke2lab-systemd-link")
        .description("Link RKE2Lab systemd service files from host share")
        .documentation("https://github.com/nxmatic/rke2lab")
        .requiresMountsFor("/srv/host/systemd-units.d", "/srv/host")
        .after("local-fs.target", bootstrapEnvService.getUnitFileName())
        .requires(bootstrapEnvService.getUnitFileName())
        .before("rke2-server.service", "rke2-agent.service")
        .conditionPathExists(
            "/srv/host/systemd-scripts.d/rke2lab-systemd-link.sh", "/srv/host/systemd-units.d")
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-systemd-link.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .partOf(context.bootstrapTarget().getUnitFileName())
        .wantedBy(context.bootstrapTarget().getUnitFileName());
  }

  private void nixInstall() {
    nixInstallService =
        new SystemdService(systemdChart, "rke2lab-nix-install")
            .description("Install Nix Package Manager for RKE2 Lab")
            .after(context.networkTarget().getUnitFileName())
            .requires(context.networkTarget().getUnitFileName())
            .type(ServiceType.ONESHOT)
            .execStart("/srv/host/systemd-scripts.d/rke2lab-nix-install.sh")
            .remainAfterExit(true)
            .standardOutput(StandardStream.JOURNAL)
            .standardError(StandardStream.JOURNAL)
            .partOf(context.toolsTarget().getUnitFileName())
            .wantedBy(context.toolsTarget().getUnitFileName());
  }

  private void floxInstall() {
    floxInstallService =
        new SystemdService(systemdChart, "rke2lab-flox-install")
            .description("Install Flox Package Manager for RKE2 Lab")
            .after(nixInstallService.getUnitFileName())
            .requires(nixInstallService.getUnitFileName())
            .type(ServiceType.ONESHOT)
            .execStart("/srv/host/systemd-scripts.d/rke2lab-flox-install.sh")
            .remainAfterExit(true)
            .standardOutput(StandardStream.JOURNAL)
            .standardError(StandardStream.JOURNAL)
            .partOf(context.toolsTarget().getUnitFileName())
            .wantedBy(context.toolsTarget().getUnitFileName());
  }

  private void cachixWatchStore() {
    new SystemdService(systemdChart, "rke2lab-cachix-watch-store")
        .description("Watch Nix store and push to Cachix")
        .after(nixInstallService.getUnitFileName(), installService.getUnitFileName())
        .requires(nixInstallService.getUnitFileName(), installService.getUnitFileName())
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-cachix-watch-store.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .partOf(context.toolsTarget().getUnitFileName())
        .wantedBy(context.toolsTarget().getUnitFileName());
  }

  private void networkConfig() {
    new SystemdService(systemdChart, "rke2lab-network-config")
        .description("RKE2Lab Network Configuration Service")
        .after("systemd-networkd.service", "cloud-init.service")
        .wants("systemd-networkd.service")
        .before(installService.getUnitFileName())
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-network-config.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .partOf(context.networkTarget().getUnitFileName())
        .wantedBy(context.networkTarget().getUnitFileName());
  }

  private void networkWait() {
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
  }

  private void networkDebug() {
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
  }

  private void routeCleanup() {
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
  }

  private void remountShared() {
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
  }

  private void containerdZfsMountConfig() {
    new SystemdService(systemdChart, "rke2lab-containerd-zfs-mount-config")
        .description("Configure containerd for ZFS mounts")
        .after(
            "local-fs.target",
            bootstrapEnvService.getUnitFileName(),
            floxInstallService.getUnitFileName(),
            installService.getUnitFileName())
        .requires(
            bootstrapEnvService.getUnitFileName(),
            floxInstallService.getUnitFileName(),
            installService.getUnitFileName())
        .before("rke2-server.service", "rke2-agent.service")
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-configure-containerd-zfs-mount.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .partOf(context.bootstrapTarget().getUnitFileName())
        .wantedBy(context.bootstrapTarget().getUnitFileName());
  }

  private void dbusTcpSystemBus() {
    new SystemdService(systemdChart, "rke2lab-dbus-tcp-system-bus")
        .description("Expose DBus system bus over TCP for RKE2Lab")
        .after("dbus.service")
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-dbus-tcp-system-bus.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .partOf(context.rke2labTarget().getUnitFileName())
        .wantedBy(context.rke2labTarget().getUnitFileName());
  }

  private void zfsEarlyUmount() {
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
  }

  private void vipKubeconfig() {
    new SystemdService(systemdChart, "rke2lab-vip-kubeconfig")
        .description("Generate VIP-enabled kubeconfig for cluster access")
        .after(
            "local-fs.target",
            bootstrapEnvService.getUnitFileName(),
            floxInstallService.getUnitFileName(),
            "rke2-server.service")
        .requires(
            bootstrapEnvService.getUnitFileName(),
            floxInstallService.getUnitFileName(),
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
        .partOf(context.rke2labTarget().getUnitFileName())
        .wantedBy(context.rke2labTarget().getUnitFileName());
  }

  private void configInstall() {
    new SystemdService(systemdChart, "rke2lab-config-install")
        .description("Install RKE2 config fragments before server start")
        .after(
            "local-fs.target",
            bootstrapEnvService.getUnitFileName(),
            floxInstallService.getUnitFileName())
        .requires(bootstrapEnvService.getUnitFileName(), floxInstallService.getUnitFileName())
        .before("rke2-server.service", "rke2-agent.service")
        .conditionPathExists("/srv/host/systemd-scripts.d/rke2lab-config-install.sh")
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-config-install.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .partOf(context.bootstrapTarget().getUnitFileName())
        .wantedBy(context.bootstrapTarget().getUnitFileName());
  }
}
