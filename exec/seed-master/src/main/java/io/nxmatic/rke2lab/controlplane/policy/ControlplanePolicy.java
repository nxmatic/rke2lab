package io.nxmatic.rke2lab.controlplane.policy;

import io.nxmatic.rke2lab.controlplane.SeedLog;
import io.nxmatic.rke2lab.controlplane.config.Rke2labConfig;
import io.nxmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Canonical operational policy derived from Pulumi config for Stage A bootstrap. */
public record ControlplanePolicy(
    DebugPolicy debug,
    NetworkPolicy network,
    ProvisioningPolicy provisioning,
    ManifestLinkPolicy manifestLink,
    ReadinessPolicy readiness,
    PreviewPolicy preview) {

  private static final ManifestDomainCatalog MANIFEST_DOMAIN_CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

  public static Builder builder() {
    return new Builder();
  }

  public static ControlplanePolicy defaults() {
    return builder()
        .debug(new DebugPolicy(false, false, false))
        .network(new NetworkPolicy(true))
        .provisioning(new ProvisioningPolicy(true))
        .manifestLink(ManifestLinkPolicy.stageA(true, true, true, true, true))
        .build();
  }

  public static ControlplanePolicy from(Rke2labConfig config) {
    final Rke2labConfig.DebugPolicyConfig debug = config.policy().debug();
    DebugPolicy debugPolicy =
        new DebugPolicy(
            debug.mesh().orElse(false),
            debug.networking().orElse(false),
            debug.nriPluginsFlox().orElse(false));

    NetworkPolicy networkPolicy =
        new NetworkPolicy(config.provisioning().lanBinding().orElse(true));

    ProvisioningPolicy provisioningPolicy =
        new ProvisioningPolicy(config.provisioning().gitDirtyCheck().orElse(true));

    final Rke2labConfig.LinkPolicyConfig link = config.policy().link();
    boolean clusterApiEnabled = link.clusterApi().orElse(true);
    SeedLog.debug("policy", "clusterApi parsed=" + clusterApiEnabled);

    ManifestLinkPolicy manifestLinkPolicy =
        new ManifestLinkPolicy(
            ManifestDomainPolicy.builder()
                .domainCatalog(MANIFEST_DOMAIN_CATALOG)
                .stageADefaults()
                .cluster(true) // Always enabled - creates rke2lab-system namespace
                .storage(link.storage().orElse(true))
                .gitops(link.gitops().orElse(true))
                .runtime(true) // Always enabled - RKE2 config, Flox runtime, core bootstrap
                .networking(link.networking().orElse(true))
                .mesh(link.mesh().orElse(false))
                .highAvailability(link.highAvailability().orElse(true))
                .cicd(link.cicd().orElse(true))
                .clusterApi(clusterApiEnabled)
                .platform(true) // Always enabled - cert-manager, kubernetes-replicator
                .build(),
            new ManifestLinkPolicy.DebugPolicy(debugPolicy::domainDebug));

    return builder()
        .debug(debugPolicy)
        .network(networkPolicy)
        .provisioning(provisioningPolicy)
        .manifestLink(manifestLinkPolicy)
        .readiness(new ReadinessPolicy(config.policy().readinessOverride()))
        .preview(new PreviewPolicy(config.policy().previewSimulate()))
        .build();
  }

  public Map<String, String> toEnvMap() {
    Map<String, String> env = new LinkedHashMap<>();
    env.putAll(debug.toEnvMap());
    env.putAll(network.toEnvMap());
    env.putAll(provisioning.toEnvMap());
    env.putAll(manifestLink.toEnvMap());
    return env;
  }

  public Map<String, Object> toOutputMap() {
    Map<String, Object> outputs = new LinkedHashMap<>();
    outputs.putAll(debug.toOutputMap());
    outputs.putAll(network.toOutputMap());
    outputs.putAll(provisioning.toOutputMap());
    outputs.putAll(manifestLink.toOutputMap());
    outputs.putAll(readiness.toOutputMap());
    outputs.putAll(preview.toOutputMap());
    return outputs;
  }

  public record DebugPolicy(boolean mesh, boolean networking, boolean nriPluginsFlox) {
    public boolean domainDebug(String domain) {
      return switch (domain) {
        case "cilium" -> networking;
        case "multus" -> networking;
        case "istio" -> mesh;
        case "rke2-helm-managed-nri-plugins-flox" -> nriPluginsFlox;
        default -> false;
      };
    }

    public Map<String, String> toEnvMap() {
      return Map.of(
          "DEBUG_MESH", String.valueOf(mesh),
          "DEBUG_NETWORKING", String.valueOf(networking),
          "DEBUG_NRI_PLUGINS_FLOX", String.valueOf(nriPluginsFlox));
    }

    public Map<String, Object> toOutputMap() {
      return Map.of(
          "debug.mesh",
          mesh,
          "debug.networking",
          networking,
          "debug.nriPluginsFlox",
          nriPluginsFlox);
    }
  }

  public record NetworkPolicy(boolean lanBinding) {
    public Map<String, String> toEnvMap() {
      return Map.of("NETWORK_LAN_BINDING", String.valueOf(lanBinding));
    }

    public Map<String, Object> toOutputMap() {
      return Map.of("network.lanBinding", lanBinding);
    }
  }

  public record ProvisioningPolicy(boolean gitDirtyCheck) {
    public Map<String, String> toEnvMap() {
      return Map.of("PROVISIONING_GIT_DIRTY_CHECK", String.valueOf(gitDirtyCheck));
    }

    public Map<String, Object> toOutputMap() {
      return Map.of("provisioning.gitDirtyCheck", gitDirtyCheck);
    }
  }

  /**
   * Operator override of readiness-scenario severity, keyed by scenario id (e.g. {@code
   * "systemd-adapter"}), carried as the RAW config string. The host does not interpret it — it
   * hands the raw value to the OSGi-side readiness-verdict handler, which owns the severity
   * vocabulary and decides.
   */
  public record ReadinessPolicy(Map<String, String> rawOverrides) {
    public ReadinessPolicy {
      rawOverrides = Map.copyOf(rawOverrides);
    }

    public static ReadinessPolicy none() {
      return new ReadinessPolicy(Map.of());
    }

    /** The operator's raw override string for a scenario, if any — interpreted OSGi-side. */
    public Optional<String> rawOverride(String scenarioId) {
      return Optional.ofNullable(rawOverrides.get(scenarioId));
    }

    public Map<String, Object> toOutputMap() {
      final Map<String, Object> outputs = new LinkedHashMap<>();
      rawOverrides.forEach(
          (scenario, value) -> outputs.put("readiness.override." + scenario, value));
      return outputs;
    }
  }

  /**
   * Operator control of {@code pulumi preview} fault simulation, keyed by scenario id, carried as
   * the RAW config string. Preview-only by construction. The host does not interpret it; the
   * simulated probe path (a later increment) maps it OSGi-side.
   */
  public record PreviewPolicy(Map<String, String> rawSimulations) {
    public PreviewPolicy {
      rawSimulations = Map.copyOf(rawSimulations);
    }

    public static PreviewPolicy none() {
      return new PreviewPolicy(Map.of());
    }

    /** The fake-incident symptom string ordered for a scenario, if any — interpreted OSGi-side. */
    public Optional<String> rawSimulate(String scenarioId) {
      return Optional.ofNullable(rawSimulations.get(scenarioId));
    }

    public Map<String, Object> toOutputMap() {
      final Map<String, Object> outputs = new LinkedHashMap<>();
      rawSimulations.forEach(
          (scenario, value) -> outputs.put("preview.simulate." + scenario, value));
      return outputs;
    }
  }

  public static final class Builder {
    private @MonotonicNonNull DebugPolicy debug;
    private @MonotonicNonNull NetworkPolicy network;
    private @MonotonicNonNull ProvisioningPolicy provisioning;
    private @MonotonicNonNull ManifestLinkPolicy manifestLink;
    private ReadinessPolicy readiness = ReadinessPolicy.none();
    private PreviewPolicy preview = PreviewPolicy.none();

    private Builder() {}

    public Builder debug(DebugPolicy debug) {
      this.debug = debug;
      return this;
    }

    public Builder network(NetworkPolicy network) {
      this.network = network;
      return this;
    }

    public Builder provisioning(ProvisioningPolicy provisioning) {
      this.provisioning = provisioning;
      return this;
    }

    public Builder manifestLink(ManifestLinkPolicy manifestLink) {
      this.manifestLink = manifestLink;
      return this;
    }

    public Builder readiness(ReadinessPolicy readiness) {
      this.readiness = readiness;
      return this;
    }

    public Builder preview(PreviewPolicy preview) {
      this.preview = preview;
      return this;
    }

    public ControlplanePolicy build() {
      return new ControlplanePolicy(
          Objects.requireNonNull(debug, "debug"),
          Objects.requireNonNull(network, "network"),
          Objects.requireNonNull(provisioning, "provisioning"),
          Objects.requireNonNull(manifestLink, "manifestLink"),
          readiness,
          preview);
    }
  }
}
