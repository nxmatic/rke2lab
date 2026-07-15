package io.nxmatic.rke2lab.controlplane.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

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
    URI imageDistrobuilderConfig,
    Path imageSharedFolder,
    String profileName,
    String lanBridgeParent,
    String vmnetNetworkName,
    URI apiEndpoint,
    Path kubeconfigRef,
    boolean nfsAutomount,
    String systemdAdapterDbusHost,
    int systemdAdapterDbusPort,
    int hostAssetRotationRetentionCount,
    Duration readinessTimeout) {

  // Defaults applied here, at the single derivation site — formerly the Builder field initializers
  // and the env/JGit/user.home detection in the deleted Defaults class.
  private static final String DEFAULT_CLUSTER_NAME = "bioskop";
  private static final String DEFAULT_NODE_NAME = "master";
  private static final String DEFAULT_INCUS_PROJECT = "rke2lab";
  private static final String DEFAULT_INCUS_DEFAULT_REMOTE = "bioskop-nixos";
  private static final URI DEFAULT_INCUS_REMOTE_ADDRESS =
      URI.create("https://bioskop-nixos.local:8443");
  private static final String DEFAULT_IMAGE_ALIAS = "control-node";
  private static final String DEFAULT_IMAGE_BUILDER_HOST = "bioskop-nixos.local";
  private static final URI DEFAULT_IMAGE_DISTROBUILDER_CONFIG =
      URI.create(
          "classpath:/META-INF/io.nxmatic/rke2lab/controlplane/incus/incus-distrobuilder.yaml");
  private static final String DEFAULT_PROFILE_NAME = "rke2lab";
  private static final String DEFAULT_LAN_BRIDGE_PARENT = "lan-br";
  private static final String DEFAULT_VMNET_NETWORK_NAME = "vmnet-br";
  private static final URI DEFAULT_API_ENDPOINT = URI.create("https://10.66.106.10:6443");
  private static final boolean DEFAULT_NFS_AUTOMOUNT = true;
  private static final String DEFAULT_SYSTEMD_ADAPTER_DBUS_HOST = "bioskop-master";
  private static final int DEFAULT_SYSTEMD_ADAPTER_DBUS_PORT = 12434;
  private static final int DEFAULT_HOST_ASSET_ROTATION_RETENTION_COUNT = 3;
  private static final Duration DEFAULT_READINESS_TIMEOUT = Duration.ofMinutes(10);

  /**
   * Derive the Stage A bootstrap config from the root DTO. Mandatory values ({@code worktree.dir},
   * {@code incus.configDir}, {@code image.sharedFolder}) are already validated at load, so they
   * arrive non-null. Optional values get their default here.
   *
   * <p>Future: if {@code worktree.dir} is not accessible (e.g. a Nix {@code nix run} with no
   * worktree), a {@code worktree.source = github} clone fallback is planned — see the config
   * restructuring spec. Not implemented yet.
   */
  public static BootstrapConfig from(Rke2labConfig config) {
    final String clusterName = config.cluster().name().orElse(DEFAULT_CLUSTER_NAME);

    // Cluster-scoped kubeconfig: one file per cluster at .local.d/<cluster>/kubeconfig.yaml.
    final Path kubeconfigRef =
        config
            .kubeconfig()
            .ref()
            .orElseGet(() -> Path.of(".local.d", clusterName, "kubeconfig.yaml").normalize());

    return new BootstrapConfig(
        config.worktree().dir(),
        clusterName,
        config.node().name().orElse(DEFAULT_NODE_NAME),
        config.incus().project().orElse(DEFAULT_INCUS_PROJECT),
        config.incus().defaultRemote().orElse(DEFAULT_INCUS_DEFAULT_REMOTE),
        config.incus().remoteAddress().orElse(DEFAULT_INCUS_REMOTE_ADDRESS),
        config.incus().configDir(),
        config.image().alias().orElse(DEFAULT_IMAGE_ALIAS),
        config.image().builderHost().orElse(DEFAULT_IMAGE_BUILDER_HOST),
        config.image().distrobuilderConfig().orElse(DEFAULT_IMAGE_DISTROBUILDER_CONFIG),
        config.image().sharedFolder(),
        config.profile().name().orElse(DEFAULT_PROFILE_NAME),
        config.network().lanBridgeParent().orElse(DEFAULT_LAN_BRIDGE_PARENT),
        config.network().vmnetNetworkName().orElse(DEFAULT_VMNET_NETWORK_NAME),
        config.api().endpoint().orElse(DEFAULT_API_ENDPOINT),
        kubeconfigRef,
        config.network().nfsAutomount().orElse(DEFAULT_NFS_AUTOMOUNT),
        config.systemd().dbusHost().orElse(DEFAULT_SYSTEMD_ADAPTER_DBUS_HOST),
        config.systemd().dbusPort().orElse(DEFAULT_SYSTEMD_ADAPTER_DBUS_PORT),
        config
            .hostAsset()
            .rotationRetentionCount()
            .orElse(DEFAULT_HOST_ASSET_ROTATION_RETENTION_COUNT),
        config.readiness().timeout().orElse(DEFAULT_READINESS_TIMEOUT));
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
    return "/net/" + clusterName + ".local";
  }
}
