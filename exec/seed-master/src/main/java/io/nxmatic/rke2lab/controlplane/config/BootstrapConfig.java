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
  private static final String DEFAULT_IMAGE_ALIAS = "control-node-base";
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
   * Derive the Stage A bootstrap config from the root DTO. The worktree root is NOT here: it is the
   * worktree soil's harvest (its {@code Worktree} component self-locates it), fetched from the
   * cellar by whoever needs it — storing it in config would only collide across worktrees.
   * Mandatory config values ({@code incus.configDir}, {@code image.sharedFolder}) are already
   * validated at load, so they arrive non-null; optional values get their default here.
   */
  public static BootstrapConfig from(Rke2labConfig config) {
    final String clusterName = config.cluster().name().orElse(DEFAULT_CLUSTER_NAME);
    final String nodeName = config.node().name().orElse(DEFAULT_NODE_NAME);
    // The rke2lab host naming convention is derived from the cluster name — the infra is identical
    // across clusters, only the prefix differs: the NixOS host is <cluster>-nixos. It is on the
    // tailnet, so its bare name resolves via MagicDNS (the incus remote URL + image builder host
    // ride it bare); only the incus remote LABEL (defaultRemote) is a pure label. Deriving here
    // keeps the cluster name a single source in config.
    final String nixosHost = clusterName + "-nixos";

    // Cluster-scoped kubeconfig: one file per cluster at .local.d/<cluster>/kubeconfig.yaml.
    final Path kubeconfigRef =
        config
            .kubeconfig()
            .ref()
            .orElseGet(() -> Path.of(".local.d", clusterName, "kubeconfig.yaml").normalize());

    return new BootstrapConfig(
        clusterName,
        nodeName,
        config.incus().project().orElse(DEFAULT_INCUS_PROJECT),
        config.incus().defaultRemote().orElseGet(() -> nixosHost),
        config
            .incus()
            .remoteAddress()
            .orElseGet(() -> URI.create("https://" + nixosHost + ":8443")),
        config.incus().configDir(),
        config.image().alias().orElse(DEFAULT_IMAGE_ALIAS),
        config.image().builderHost().orElseGet(() -> nixosHost),
        config.image().sharedFolder(),
        config.profile().name().orElse(DEFAULT_PROFILE_NAME),
        config.network().lanBridgeParent().orElse(DEFAULT_LAN_BRIDGE_PARENT),
        config.network().vmnetNetworkName().orElse(DEFAULT_VMNET_NETWORK_NAME),
        config.network().tailnet().orElse(DEFAULT_TAILNET),
        config.api().endpoint().orElse(DEFAULT_API_ENDPOINT),
        kubeconfigRef,
        config.network().nfsAutomount().orElse(DEFAULT_NFS_AUTOMOUNT),
        // The systemd adapter runs INSIDE the master container, whose dbus-over-TCP endpoint the
        // host probes over mDNS (avahi on the LAN). The container is NOT on the tailnet, so a bare
        // <cluster>-<node> does not resolve from the host (getaddrinfo fails before the port); the
        // .local FQDN avahi advertises does. Default to it so the probe reaches a running adapter.
        config.systemd().dbusHost().orElseGet(() -> clusterName + "-" + nodeName + ".local"),
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
    return "nix";
  }

  public String netPrefix() {
    return "/net/" + clusterName + ".local";
  }
}
