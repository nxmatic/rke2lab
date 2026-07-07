package io.nxmatic.rke2lab.controlplane.config;

import io.nxmatic.rke2lab.config.port.BootstrapConfig;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Derives the flat {@link BootstrapConfig} (a config-port seam record) from the Pulumi-backed
 * {@link Rke2labConfig}. This derivation stays host-side: it reads {@code Rke2labConfig}, which
 * touches {@code com.pulumi}, so it cannot cross into a bundle. The host builds the flat config
 * here and passes it into the embedded framework via {@code HostFacts}; the OSGi world reads it
 * back typed across the type=seam boundary.
 */
public final class BootstrapConfigFactory {

  // Defaults applied here, at the single derivation site — formerly on the record itself.
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

  private BootstrapConfigFactory() {}

  /**
   * Derive the Stage A bootstrap config from the root DTO. Mandatory values ({@code worktree.dir},
   * {@code incus.configDir}, {@code image.sharedFolder}) are already validated at load, so they
   * arrive non-null. Optional values get their default here.
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
}
