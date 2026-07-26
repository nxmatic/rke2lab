package io.nxmatic.rke2lab.controlplane.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.osgi.service.log.LogLevel;

/**
 * Runtime configuration for provider-native Stage A bootstrap, derived from {@link Rke2labConfig}.
 */
public record BootstrapConfig(
    Path worktreeDir,
    String clusterName,
    String nodeName,
    String incusProject,
    String incusDefaultRemote,
    URI incusRemoteAddress,
    Path incusConfigDir,
    String imageAlias,
    String imageBuilderHost,
    Path imageSharedFolder,
    String profileName,
    String lanBridgeParent,
    String vmnetNetworkName,
    String tailnet,
    URI apiEndpoint,
    Path kubeconfigRef,
    boolean nfsAutomount,
    String systemdAdapterDbusHost,
    int systemdAdapterDbusPort,
    int hostAssetRotationRetentionCount,
    Duration readinessTimeout,
    Optional<LogLevel> logLevel) {

  // Defaults applied here, at the single derivation site — formerly the Builder field initializers
  // and the env/JGit/user.home detection in the deleted Defaults class.
  private static final String DEFAULT_CLUSTER_NAME = "bioskop";
  private static final String DEFAULT_NODE_NAME = "master";
  private static final String DEFAULT_INCUS_PROJECT = "rke2lab";
  private static final String DEFAULT_IMAGE_ALIAS = "control-node";
  private static final String DEFAULT_PROFILE_NAME = "rke2lab";
  private static final String DEFAULT_LAN_BRIDGE_PARENT = "lan-br";
  private static final String DEFAULT_VMNET_NETWORK_NAME = "vmnet-br";
  // The tailscale tailnet DNS suffix. Resolvable host/automount addresses use the MagicDNS FQDN
  // <host>.<tailnet> so they route over the tailscale overlay (stable across the physical LAN),
  // rather than the LAN mDNS <host>.local.
  private static final String DEFAULT_TAILNET = "mammoth-skate.ts.net";
  private static final URI DEFAULT_API_ENDPOINT = URI.create("https://10.66.106.10:6443");
  private static final boolean DEFAULT_NFS_AUTOMOUNT = true;
  private static final int DEFAULT_SYSTEMD_ADAPTER_DBUS_PORT = 12434;
  private static final int DEFAULT_HOST_ASSET_ROTATION_RETENTION_COUNT = 3;
  private static final Duration DEFAULT_READINESS_TIMEOUT = Duration.ofMinutes(10);

  /**
   * Derive the Stage A bootstrap config from the root DTO plus the runtime-derived {@code
   * worktreeRoot}. The worktree is NOT config: the root knows its own worktree (jgit walks up to
   * the {@code .git} from the process directory, § {@code Worktree}), so storing it in config would
   * only collide across worktrees. Mandatory config values ({@code incus.configDir}, {@code
   * image.sharedFolder}) are already validated at load, so they arrive non-null; optional values
   * get their default here.
   */
  public static BootstrapConfig from(Rke2labConfig config, Path worktreeRoot) {
    final String clusterName = config.cluster().name().orElse(DEFAULT_CLUSTER_NAME);
    final String nodeName = config.node().name().orElse(DEFAULT_NODE_NAME);
    // The rke2lab host naming convention is derived from the cluster name — the infra is identical
    // across clusters, only the prefix differs: the NixOS host is <cluster>-nixos. Its resolvable
    // addresses (the incus remote URL, the image builder host) carry the .local FQDN so they
    // resolve
    // over mDNS; the incus remote LABEL (defaultRemote) and the dbus host stay bare. Deriving here
    // keeps the cluster name a single source in config.
    final String nixosHost = clusterName + "-nixos";

    // Cluster-scoped kubeconfig: one file per cluster at .local.d/<cluster>/kubeconfig.yaml.
    final Path kubeconfigRef =
        config
            .kubeconfig()
            .ref()
            .orElseGet(() -> Path.of(".local.d", clusterName, "kubeconfig.yaml").normalize());

    return new BootstrapConfig(
        worktreeRoot,
        clusterName,
        nodeName,
        config.incus().project().orElse(DEFAULT_INCUS_PROJECT),
        config.incus().defaultRemote().orElseGet(() -> nixosHost),
        config
            .incus()
            .remoteAddress()
            .orElseGet(() -> URI.create("https://" + nixosHost + ".local:8443")),
        config.incus().configDir(),
        config.image().alias().orElse(DEFAULT_IMAGE_ALIAS),
        config.image().builderHost().orElseGet(() -> nixosHost + ".local"),
        config.image().sharedFolder(),
        config.profile().name().orElse(DEFAULT_PROFILE_NAME),
        config.network().lanBridgeParent().orElse(DEFAULT_LAN_BRIDGE_PARENT),
        config.network().vmnetNetworkName().orElse(DEFAULT_VMNET_NETWORK_NAME),
        config.network().tailnet().orElse(DEFAULT_TAILNET),
        config.api().endpoint().orElse(DEFAULT_API_ENDPOINT),
        kubeconfigRef,
        config.network().nfsAutomount().orElse(DEFAULT_NFS_AUTOMOUNT),
        config.systemd().dbusHost().orElseGet(() -> clusterName + "-" + nodeName),
        config.systemd().dbusPort().orElse(DEFAULT_SYSTEMD_ADAPTER_DBUS_PORT),
        config
            .hostAsset()
            .rotationRetentionCount()
            .orElse(DEFAULT_HOST_ASSET_ROTATION_RETENTION_COUNT),
        config.readiness().timeout().orElse(DEFAULT_READINESS_TIMEOUT),
        // No default: absent ⇒ no override — the boot keeps the Felix default and the generated pax
        // logback keeps its ${seed.log.level} default. A present value drives BOTH planes:
        // felix.log.level (Plane A) + the pax logback root via the seed.log.level property (Plane
        // B).
        config.logging().level());
  }

  public String imageBuilderBinary() {
    return "distrobuilder";
  }

  /**
   * The worktree root the provisioner writes under, DARWIN-local (absolute, normalised). The NFS
   * automount view the remote NIXOS host mounts from is now computed OSGi-side by the incus scion
   * ({@code BootstrapPaths.asAutomountView}); the host only hands it the flat scalars it needs.
   */
  public Path localWorktreePath() {
    return worktreeDir.toAbsolutePath().normalize();
  }

  public String netPrefix() {
    // The autofs -hosts root the NIXOS host mounts the Mac's NFS exports under: /net/<host>. The
    // host is the tailscale MagicDNS FQDN <cluster>.<tailnet> so the automount routes over the
    // tailscale overlay (stable, reachable) rather than the physical LAN's mDNS <cluster>.local.
    return "/net/" + clusterName + "." + tailnet;
  }
}
