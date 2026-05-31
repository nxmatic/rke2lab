package io.nxmatic.rk2lab.manifests.systemd;

/**
 * Catalog of systemd unit names (single source of truth for unit identifiers).
 *
 * <p>Prevents identifier mismatches in unit dependencies by providing typed accessor methods
 * instead of hardcoded strings. Every unit filename and dependency reference should use this
 * catalog.
 *
 * <p><b>Pattern</b>: Same as {@code ManifestDomainCatalog} and {@code
 * BootstrapPaths.HostPathCatalog} - define identifiers once, reference everywhere via methods.
 *
 * <p><b>Usage in unit files</b>: Unit files themselves cannot use Java code, but the catalog serves
 * as the authoritative registry during code review. When adding or referencing a unit dependency,
 * verify the name matches this catalog.
 *
 * <p><b>Root cause this solves</b>: The {@code rke2lab-cluster-api-image-state-apply.service} unit
 * referenced {@code After=rke2lab-clusterapi-manifests.service} (no dash) but the actual filename
 * is {@code rke2lab-cluster-api-manifests.service} (with dash). This mismatch caused systemd to
 * fail with "Unit not found".
 *
 * @see io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog
 * @see io.nxmatic.rk2lab.controlplane.incus.IncusResourceBootstrap.BootstrapPaths.HostPathCatalog
 */
public final class SystemdUnitCatalog {

  // Bootstrap & Installation
  public static final String BOOTSTRAP_ENV = "rke2lab-bootstrap-env.service";
  public static final String INSTALL = "rke2lab-install.service";
  public static final String SYSTEMD_LINK = "rke2lab-systemd-link.service";

  // Nix & Flox
  public static final String NIX_INSTALL = "rke2lab-nix-install.service";
  public static final String FLOX_INSTALL = "rke2lab-flox-install.service";
  public static final String CACHIX_WATCH_STORE = "rke2lab-cachix-watch-store.service";

  // Networking
  public static final String NETWORK_CONFIG = "rke2lab-network-config.service";
  public static final String NETWORK_WAIT = "rke2lab-network-wait.service";
  public static final String NETWORK_DEBUG = "rke2lab-network-debug.service";
  public static final String ROUTE_CLEANUP = "rke2lab-route-cleanup.service";
  public static final String REMOUNT_SHARED = "rke2lab-remount-shared.service";

  // Storage
  public static final String CONTAINERD_ZFS_MOUNT_CONFIG =
      "rke2lab-containerd-zfs-mount-config.service";

  // DBus
  public static final String DBUS_TCP_SYSTEM_BUS = "rke2lab-dbus-tcp-system-bus.service";

  // Manifest Installation (domain-specific)
  public static final String CILIUM_CONFIG_MANIFESTS = "rke2lab-cilium-config-manifests.service";
  public static final String CLUSTER_MANIFESTS = "rke2lab-cluster-manifests.service";
  public static final String CLUSTER_API_MANIFESTS = "rke2lab-cluster-api-manifests.service";
  public static final String CLUSTER_API_IMAGE_STATE_APPLY =
      "rke2lab-cluster-api-image-state-apply.service";
  public static final String GITOPS_MANIFESTS = "rke2lab-gitops-manifests.service";
  public static final String MESH_MANIFESTS = "rke2lab-mesh-manifests.service";
  public static final String NETWORKING_MANIFESTS = "rke2lab-networking-manifests.service";
  public static final String REPLICATION_MANIFESTS = "rke2lab-replication-manifests.service";
  public static final String RUNTIME_MANIFESTS = "rke2lab-runtime-manifests.service";
  public static final String STORAGE_MANIFESTS = "rke2lab-storage-manifests.service";
  public static final String TEKTON_PIPELINES_MANIFESTS =
      "rke2lab-tekton-pipelines-manifests.service";

  // Secrets
  public static final String CICD_SECRETS = "rke2lab-cicd-secrets.service";
  public static final String GITOPS_SECRETS = "rke2lab-gitops-secrets.service";
  public static final String MESH_SECRETS = "rke2lab-mesh-secrets.service";
  public static final String RUNTIME_SECRETS = "rke2lab-runtime-secrets.service";

  // Targets
  public static final String TOOLS_TARGET = "rke2lab-tools.target";

  // Timers
  public static final String CACHIX_WATCH_STORE_TIMER = "rke2lab-cachix-watch-store.timer";

  private SystemdUnitCatalog() {
    // Utility class
  }

  /**
   * Validates that a unit name matches a catalog constant.
   *
   * <p>Useful in tests or during unit file generation to catch mismatches early.
   *
   * @param unitName the unit name to validate
   * @return true if the name exists in this catalog
   */
  public static boolean isKnownUnit(String unitName) {
    return unitName.equals(BOOTSTRAP_ENV)
        || unitName.equals(INSTALL)
        || unitName.equals(SYSTEMD_LINK)
        || unitName.equals(NIX_INSTALL)
        || unitName.equals(FLOX_INSTALL)
        || unitName.equals(CACHIX_WATCH_STORE)
        || unitName.equals(NETWORK_CONFIG)
        || unitName.equals(NETWORK_WAIT)
        || unitName.equals(NETWORK_DEBUG)
        || unitName.equals(ROUTE_CLEANUP)
        || unitName.equals(REMOUNT_SHARED)
        || unitName.equals(CONTAINERD_ZFS_MOUNT_CONFIG)
        || unitName.equals(DBUS_TCP_SYSTEM_BUS)
        || unitName.equals(CILIUM_CONFIG_MANIFESTS)
        || unitName.equals(CLUSTER_MANIFESTS)
        || unitName.equals(CLUSTER_API_MANIFESTS)
        || unitName.equals(CLUSTER_API_IMAGE_STATE_APPLY)
        || unitName.equals(GITOPS_MANIFESTS)
        || unitName.equals(MESH_MANIFESTS)
        || unitName.equals(NETWORKING_MANIFESTS)
        || unitName.equals(REPLICATION_MANIFESTS)
        || unitName.equals(RUNTIME_MANIFESTS)
        || unitName.equals(STORAGE_MANIFESTS)
        || unitName.equals(TEKTON_PIPELINES_MANIFESTS)
        || unitName.equals(CICD_SECRETS)
        || unitName.equals(GITOPS_SECRETS)
        || unitName.equals(MESH_SECRETS)
        || unitName.equals(RUNTIME_SECRETS)
        || unitName.equals(TOOLS_TARGET)
        || unitName.equals(CACHIX_WATCH_STORE_TIMER);
  }
}
