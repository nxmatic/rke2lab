// @codebase
package io.nxmatic.rk2lab.manifests.systemd;

import static io.nxmatic.rk2lab.manifests.systemd.SystemdUnitCatalog.*;

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
 * <p>Called once from the synthesis orchestrator after domain synthesis completes.
 */
public final class BootstrapInfrastructureSynthesizer {

  private final SystemdChart systemdChart;

  public BootstrapInfrastructureSynthesizer(SystemdChart systemdChart) {
    this.systemdChart = systemdChart;
  }

  /** Synthesizes all bootstrap and infrastructure units. */
  public void synthesizeAll() {
    // Bootstrap & installation
    bootstrapEnv();
    install();
    systemdLink();

    // Nix & Flox
    nixInstall();
    floxInstall();
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
  }

  private void bootstrapEnv() {
    new SystemdService(systemdChart, "rke2lab-bootstrap-env")
        .description("RKE2Lab bootstrap environment (Nix + Flox + nocloud)")
        .documentation("https://github.com/nxmatic/rke2lab")
        .after(
            "network-online.target",
            "systemd-networkd.service",
            "local-fs.target",
            SystemdUnitCatalog.NETWORK_TARGET,
            SystemdUnitCatalog.TOOLS_TARGET,
            SystemdUnitCatalog.FLOX_INSTALL)
        .wants(
            "network-online.target", "systemd-networkd.service", SystemdUnitCatalog.NETWORK_TARGET)
        .requires(
            SystemdUnitCatalog.NETWORK_TARGET,
            SystemdUnitCatalog.TOOLS_TARGET,
            SystemdUnitCatalog.FLOX_INSTALL)
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
        .wantedBy(SystemdUnitCatalog.RKE2LAB_TARGET);
  }

  private void install() {
    new SystemdService(systemdChart, "rke2lab-install")
        .description("Run RKE2Lab Installation Script")
        .after(
            "network-online.target",
            "systemd-networkd.service",
            "local-fs.target",
            SystemdUnitCatalog.NETWORK_TARGET,
            SystemdUnitCatalog.TOOLS_TARGET,
            SystemdUnitCatalog.BOOTSTRAP_ENV)
        .wants(
            "network-online.target", "systemd-networkd.service", SystemdUnitCatalog.NETWORK_TARGET)
        .requires(
            SystemdUnitCatalog.NETWORK_TARGET,
            SystemdUnitCatalog.TOOLS_TARGET,
            SystemdUnitCatalog.BOOTSTRAP_ENV)
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
        .wantedBy(SystemdUnitCatalog.RKE2LAB_TARGET);
  }

  private void systemdLink() {
    new SystemdService(systemdChart, "rke2lab-systemd-link")
        .description("Link RKE2Lab systemd service files from host share")
        .documentation("https://github.com/nxmatic/rke2lab")
        .requiresMountsFor("/srv/host/systemd-units.d", "/srv/host")
        .after("local-fs.target", SystemdUnitCatalog.BOOTSTRAP_ENV)
        .requires(SystemdUnitCatalog.BOOTSTRAP_ENV)
        .before("rke2-server.service", "rke2-agent.service")
        .conditionPathExists(
            "/srv/host/systemd-scripts.d/rke2lab-systemd-link.sh", "/srv/host/systemd-units.d")
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-systemd-link.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .wantedBy(SystemdUnitCatalog.RKE2LAB_TARGET);
  }

  private void nixInstall() {
    new SystemdService(systemdChart, "rke2lab-nix-install")
        .description("Install Nix Package Manager for RKE2 Lab")
        .after(SystemdUnitCatalog.NETWORK_TARGET)
        .requires(SystemdUnitCatalog.NETWORK_TARGET)
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-nix-install.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .wantedBy(SystemdUnitCatalog.TOOLS_TARGET);
  }

  private void floxInstall() {
    new SystemdService(systemdChart, "rke2lab-flox-install")
        .description("Install Flox Package Manager for RKE2 Lab")
        .after(SystemdUnitCatalog.NIX_INSTALL)
        .requires(SystemdUnitCatalog.NIX_INSTALL)
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-flox-install.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .wantedBy(SystemdUnitCatalog.TOOLS_TARGET);
  }

  private void cachixWatchStore() {
    new SystemdService(systemdChart, "rke2lab-cachix-watch-store")
        .description("Watch Nix store and push to Cachix")
        .after(SystemdUnitCatalog.NIX_INSTALL)
        .requires(SystemdUnitCatalog.NIX_INSTALL)
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-cachix-watch-store.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .wantedBy(SystemdUnitCatalog.TOOLS_TARGET);
  }

  private void networkConfig() {
    new SystemdService(systemdChart, "rke2lab-network-config")
        .description("RKE2Lab Network Configuration Service")
        .after("systemd-networkd.service", "cloud-init.service")
        .wants("systemd-networkd.service")
        .before(SystemdUnitCatalog.INSTALL)
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-network-config.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .wantedBy(SystemdUnitCatalog.NETWORK_TARGET);
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
        .wantedBy(SystemdUnitCatalog.NETWORK_TARGET);
  }

  private void networkDebug() {
    new SystemdService(systemdChart, "rke2lab-network-debug")
        .description("RKE2Lab network diagnostics")
        .after(SystemdUnitCatalog.NETWORK_TARGET)
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-network-debug.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .wantedBy(SystemdUnitCatalog.NETWORK_TARGET);
  }

  private void routeCleanup() {
    new SystemdService(systemdChart, "rke2lab-route-cleanup")
        .description("Clean up conflicting routes for RKE2Lab")
        .after("network-online.target")
        .before(SystemdUnitCatalog.NETWORK_TARGET)
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-route-cleanup.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .wantedBy(SystemdUnitCatalog.NETWORK_TARGET);
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
        .after("local-fs.target")
        .before("rke2-server.service", "rke2-agent.service")
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-containerd-zfs-mount-config.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .wantedBy(SystemdUnitCatalog.RKE2LAB_TARGET);
  }

  private void dbusTcpSystemBus() {
    new SystemdService(systemdChart, "rke2lab-dbus-tcp-system-bus")
        .description("Expose DBus system bus over TCP for RKE2Lab")
        .after("dbus.service")
        .requires("dbus.service")
        .type(ServiceType.ONESHOT)
        .execStart("/srv/host/systemd-scripts.d/rke2lab-dbus-tcp-system-bus.sh")
        .remainAfterExit(true)
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL)
        .wantedBy(SystemdUnitCatalog.RKE2LAB_TARGET);
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
}
