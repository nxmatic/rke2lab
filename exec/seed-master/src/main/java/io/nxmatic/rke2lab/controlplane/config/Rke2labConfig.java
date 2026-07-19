package io.nxmatic.rke2lab.controlplane.config;

import com.pulumi.Config;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.osgi.service.log.LogLevel;

/**
 * Root configuration DTO and single source of truth. Infra fragments are contributed by each {@link
 * InfraDomain} (via {@link InfraConfigRegistry}); cross-cutting identity is loaded directly.
 * Mandatory fields are plain types validated at load; optional fields are {@code Optional} with NO
 * defaults — defaults are applied in the derivation layer ({@code BootstrapConfig.from} / {@code
 * ControlplanePolicy.from} / {@code BootstrapOptions.from}).
 */
public record Rke2labConfig(
    InfraConfigRegistry infra,
    ClusterConfig cluster,
    NodeConfig node,
    ProfileConfig profile,
    ApiConfig api,
    KubeconfigConfig kubeconfig,
    PolicyConfig policy,
    ProvisioningPolicyConfig provisioning,
    ReadinessConfig readiness,
    EntryGateConfig entryGate,
    BboxConfig bbox,
    LoggingConfig logging) {

  public static Rke2labConfig from(Config config) {
    return from(ConfigLoader.of(config));
  }

  /**
   * Parse the {@code logging:level} knob into the OSGi {@link LogLevel} — the SAME enum the
   * framework boot and the {@code @FrameworkLog} test annotation speak, so the operator's one knob
   * has one vocabulary. Case-insensitive over the OSGi names (AUDIT/ERROR/WARN/INFO/DEBUG/TRACE);
   * an unknown value fails loudly with the accepted set rather than defaulting silently.
   */
  private static LogLevel parseLogLevel(String raw) {
    try {
      return LogLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          "logging:level '"
              + raw
              + "' is not an org.osgi.service.log.LogLevel (AUDIT, ERROR, WARN, INFO, DEBUG, TRACE)",
          ex);
    }
  }

  /** Offline path: empty config, no mandatory validation. Derivation applies all defaults. */
  public static Rke2labConfig defaults() {
    return build(ConfigLoader.of(section -> Optional.empty()), false);
  }

  /**
   * Build from any loader (live wraps Pulumi config; the entry gate and offline tests pass an
   * in-memory loader). Validates mandatory keys — throws {@link MissingRequiredConfiguration}
   * naming every absent one.
   */
  public static Rke2labConfig from(ConfigLoader loader) {
    return build(loader, true);
  }

  private static Rke2labConfig build(ConfigLoader loader, boolean validate) {
    final InfraConfigRegistry infra = InfraConfigRegistry.from(loader);

    final Rke2labConfig dto =
        new Rke2labConfig(
            infra,
            new ClusterConfig(loader.optional("cluster", "name")),
            new NodeConfig(loader.optional("node", "name")),
            new ProfileConfig(loader.optional("profile", "name")),
            new ApiConfig(loader.optionalUri("api", "endpoint")),
            new KubeconfigConfig(loader.optionalPath("kubeconfig", "ref")),
            PolicyConfig.from(loader),
            new ProvisioningPolicyConfig(
                loader.optionalBoolean("policy.network.lan.binding", "enabled"),
                loader.optionalBoolean("policy.gitDirtyCheck", "enabled")),
            new ReadinessConfig(
                loader.optionalBoolean("readiness", "enabled"),
                loader.optionalDuration("readiness", "timeout")),
            new EntryGateConfig(loader.optionalBoolean("entryGate.cleanWorktree", "required")),
            new BboxConfig(loader.optionalBoolean("bbox.reconcile", "failOnError")),
            new LoggingConfig(
                loader.optional("logging", "level").map(Rke2labConfig::parseLogLevel)));

    if (validate) {
      loader.diagnoseIfIncomplete();
    }
    return dto;
  }

  public IncusConfig incus() {
    return infra.fragment(InfraDomainCatalog.INCUS, IncusConfig.class);
  }

  public ImageConfig image() {
    return infra.fragment(InfraDomainCatalog.IMAGE, ImageConfig.class);
  }

  public NetworkConfig network() {
    return infra.fragment(InfraDomainCatalog.NETWORK, NetworkConfig.class);
  }

  public WorktreeConfig worktree() {
    return infra.fragment(InfraDomainCatalog.WORKTREE, WorktreeConfig.class);
  }

  public SystemdAdapterConfig systemd() {
    return infra.fragment(InfraDomainCatalog.SYSTEMD, SystemdAdapterConfig.class);
  }

  public HostAssetConfig hostAsset() {
    return infra.fragment(InfraDomainCatalog.HOST, HostAssetConfig.class);
  }

  // --- Infra fragments (sealed marker) ---

  public record IncusConfig(
      Optional<String> project,
      Optional<String> defaultRemote,
      Optional<URI> remoteAddress,
      Path configDir)
      implements InfraConfigFragment {}

  public record ImageConfig(Optional<String> alias, Optional<String> builderHost, Path sharedFolder)
      implements InfraConfigFragment {}

  public record NetworkConfig(
      Optional<String> lanBridgeParent,
      Optional<String> vmnetNetworkName,
      Optional<Boolean> nfsAutomount)
      implements InfraConfigFragment {}

  public record WorktreeConfig(Path dir) implements InfraConfigFragment {}

  public record SystemdAdapterConfig(Optional<String> dbusHost, Optional<Integer> dbusPort)
      implements InfraConfigFragment {}

  public record HostAssetConfig(Optional<Integer> rotationRetentionCount)
      implements InfraConfigFragment {}

  // --- Cross-cutting identity (no marker) ---

  public record ClusterConfig(Optional<String> name) {}

  public record NodeConfig(Optional<String> name) {}

  public record ProfileConfig(Optional<String> name) {}

  public record ApiConfig(Optional<URI> endpoint) {}

  public record KubeconfigConfig(Optional<Path> ref) {}

  public record PolicyConfig(
      LinkPolicyConfig link,
      DebugPolicyConfig debug,
      Map<String, String> readinessOverride,
      Map<String, String> previewSimulate) {
    static PolicyConfig from(ConfigLoader loader) {
      return new PolicyConfig(
          new LinkPolicyConfig(
              loader.optionalBoolean("policy.link", "gitops"),
              loader.optionalBoolean("policy.link", "networking"),
              loader.optionalBoolean("policy.link", "clusterApi"),
              loader.optionalBoolean("policy.link", "storage"),
              loader.optionalBoolean("policy.link", "mesh"),
              loader.optionalBoolean("policy.link", "highAvailability"),
              loader.optionalBoolean("policy.link", "cicd")),
          new DebugPolicyConfig(
              loader.optionalBoolean("policy.debug.mesh", "enabled"),
              loader.optionalBoolean("policy.debug.networking", "enabled"),
              loader.optionalBoolean("policy.debug.nriPlugins.flox", "enabled")),
          loader.stringMap("policy.readiness", "override"),
          loader.stringMap("policy.preview", "simulate"));
    }
  }

  public record LinkPolicyConfig(
      Optional<Boolean> gitops,
      Optional<Boolean> networking,
      Optional<Boolean> clusterApi,
      Optional<Boolean> storage,
      Optional<Boolean> mesh,
      Optional<Boolean> highAvailability,
      Optional<Boolean> cicd) {}

  public record DebugPolicyConfig(
      Optional<Boolean> mesh, Optional<Boolean> networking, Optional<Boolean> nriPluginsFlox) {}

  public record ProvisioningPolicyConfig(
      Optional<Boolean> lanBinding, Optional<Boolean> gitDirtyCheck) {}

  public record ReadinessConfig(Optional<Boolean> enabled, Optional<Duration> timeout) {}

  public record EntryGateConfig(Optional<Boolean> cleanWorktreeRequired) {}

  public record BboxConfig(Optional<Boolean> failOnError) {}

  /**
   * The framework log-level knob ({@code logging:level}). Empty ⇒ no override: the boot keeps the
   * Felix default and the host logback keeps its {@code logback.xml} root level.
   */
  public record LoggingConfig(Optional<LogLevel> level) {}
}
