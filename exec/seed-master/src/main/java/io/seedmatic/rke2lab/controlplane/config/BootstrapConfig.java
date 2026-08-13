package io.seedmatic.rke2lab.controlplane.config;

import io.seedmatic.rke2lab.incus.ingress.BootstrapPaths;
import io.seedmatic.rke2lab.seed.broker.port.ReadinessDeadlineOverride;
import io.seedmatic.rke2lab.seed.broker.port.ReadinessOverrides;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
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
    Path incusConfigFolder,
    String imageAlias,
    String imageBuilderHost,
    String profileName,
    String lanBridgeParent,
    String vmnetNetworkName,
    String tailnet,
    URI apiEndpoint,
    Path kubeconfigRef,
    boolean automount,
    String systemdAdapterDbusHost,
    int systemdAdapterDbusPort,
    int hostAssetRotationRetentionCount,
    ReadinessOverrides readinessOverrides,
    Optional<LogLevel> logLevel) {

  // Defaults applied here, at the single derivation site — formerly the Builder field initializers
  // and the env/JGit/user.home detection in the deleted Defaults class.
  private static final String DEFAULT_CLUSTER_NAME = "bioskop";
  private static final String DEFAULT_NODE_NAME = "master";
  private static final String DEFAULT_INCUS_PROJECT = "rke2lab";
  // The one seed image's incus alias — the host adopts the built image by it. Single source here;
  // the build script (build-node-base-image.sh) hardcodes the SAME literal on the import side.
  private static final String IMAGE_ALIAS = "node-base";
  private static final String DEFAULT_PROFILE_NAME = "rke2lab";
  private static final String DEFAULT_LAN_BRIDGE_PARENT = "lan-br";
  private static final String DEFAULT_VMNET_NETWORK_NAME = "vmnet-br";
  // The tailscale tailnet DNS suffix. Resolvable host/automount addresses use the MagicDNS FQDN
  // <host>.<tailnet> so they route over the tailscale overlay (stable across the physical LAN),
  // rather than the LAN mDNS <host>.local.
  private static final String DEFAULT_TAILNET = "mammoth-skate.ts.net";
  private static final URI DEFAULT_API_ENDPOINT = URI.create("https://10.66.106.10:6443");
  private static final boolean DEFAULT_AUTOMOUNT = true;
  private static final int DEFAULT_SYSTEMD_ADAPTER_DBUS_PORT = 12434;
  private static final int DEFAULT_HOST_ASSET_ROTATION_RETENTION_COUNT = 3;

  /**
   * Derive the Stage A bootstrap config from the root DTO. The worktree root is NOT here: it is the
   * worktree soil's harvest (its {@code Worktree} component self-locates it), fetched from the
   * cellar by whoever needs it — storing it in config would only collide across worktrees. The
   * mandatory config value ({@code incus.configDir}) is already validated at load, so it arrives
   * non-null; optional values get their default here.
   */
  public static BootstrapConfig from(Rke2labConfig config) {
    final String clusterName = config.cluster().name().orElse(DEFAULT_CLUSTER_NAME);
    final String nodeName = config.node().name().orElse(DEFAULT_NODE_NAME);
    // The rke2lab host naming convention is derived from the cluster name — the infra is identical
    // across clusters, only the prefix differs: the NixOS host is <cluster>-nixos. Its RESOLVABLE
    // address rides the LAN mDNS <host>.local, NOT the tailnet MagicDNS bare name: the incus daemon
    // binds dual-stack [::]:8443 which the .local name reaches, and this is the SAME channel the
    // operator's own ~/.config/incus remote already uses. The bare tailnet name is avoided here —
    // it resolves to a tailnet IP that currently times out from the seed host. Only the incus
    // remote
    // LABEL (defaultRemote) stays the bare name (a pure label, never resolved). Deriving here keeps
    // the cluster name a single source in config.
    final String nixosHost = clusterName + "-nixos";
    final String nixosMdnsHost = nixosHost + ".local";

    // Flat kubeconfig at .local.d/kubeconfig.yaml — one single-node management cluster.
    final Path kubeconfigRef =
        config
            .kubeconfig()
            .ref()
            .orElseGet(() -> Path.of(BootstrapPaths.STATE_DIR, "kubeconfig.yaml").normalize());

    return new BootstrapConfig(
        clusterName,
        nodeName,
        config.incus().project().orElse(DEFAULT_INCUS_PROJECT),
        config.incus().defaultRemote().orElseGet(() -> nixosHost),
        config
            .incus()
            .remoteAddress()
            .orElseGet(() -> URI.create("https://" + nixosMdnsHost + ":8443")),
        config.incus().configDir(),
        IMAGE_ALIAS,
        config.image().builderHost().orElseGet(() -> nixosMdnsHost),
        config.profile().name().orElse(DEFAULT_PROFILE_NAME),
        config.network().lanBridgeParent().orElse(DEFAULT_LAN_BRIDGE_PARENT),
        config.network().vmnetNetworkName().orElse(DEFAULT_VMNET_NETWORK_NAME),
        config.network().tailnet().orElse(DEFAULT_TAILNET),
        config.api().endpoint().orElse(DEFAULT_API_ENDPOINT),
        kubeconfigRef,
        config.network().automount().orElse(DEFAULT_AUTOMOUNT),
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
        readinessOverridesFrom(config.readiness()),
        // No default: absent ⇒ no override — the boot keeps the Felix default and the generated pax
        // logback keeps its ${seed.log.level} default. A present value drives BOTH planes:
        // felix.log.level (Plane A) + the pax logback root via the seed.log.level property (Plane
        // B).
        config.logging().level());
  }

  /**
   * Project the operator's {@code rke2lab:readiness:} config into the neutral {@link
   * ReadinessOverrides} seam the host publishes for the readiness scions — the global default plus
   * the per-checkpoint map, each an {@link ReadinessDeadlineOverride} of two optional durations.
   * Absent everywhere ⇒ {@link ReadinessOverrides#NONE}, so every deadline stays the scenario's
   * {@code @ReadinessDeadlines} annotation default (formerly the dead {@code
   * DEFAULT_READINESS_TIMEOUT} this replaces — the deadline now lives in code, tuned here, not
   * defaulted here).
   */
  private static ReadinessOverrides readinessOverridesFrom(
      Rke2labConfig.ReadinessConfig readiness) {
    final ReadinessDeadlineOverride global =
        new ReadinessDeadlineOverride(readiness.connectTimeout(), readiness.timeout());
    final LinkedHashMap<String, ReadinessDeadlineOverride> perCheckpoint = new LinkedHashMap<>();
    readiness
        .checkpoints()
        .forEach(
            (slug, deadlines) ->
                perCheckpoint.put(
                    slug,
                    new ReadinessDeadlineOverride(
                        deadlines.connectTimeout(), deadlines.timeout())));
    return new ReadinessOverrides(global, Map.copyOf(perCheckpoint));
  }

  public String imageBuilderBinary() {
    return "nix";
  }

  public String netPrefix() {
    return "/net/" + clusterName + ".local";
  }
}
