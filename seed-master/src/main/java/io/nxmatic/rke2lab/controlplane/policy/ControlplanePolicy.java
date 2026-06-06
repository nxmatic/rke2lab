package io.nxmatic.rke2lab.controlplane.policy;

import io.nxmatic.rke2lab.controlplane.SeedLog;
import io.nxmatic.rke2lab.controlplane.bdd.Severity;
import io.nxmatic.rke2lab.controlplane.config.Rke2labConfig;
import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.ManifestDomainPolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Canonical operational policy derived from Pulumi config for Stage A bootstrap. */
public record ControlplanePolicy(
    DebugPolicy debug,
    NetworkPolicy network,
    ProvisioningPolicy provisioning,
    ManifestLinkPolicy manifestLink,
    ReadinessPolicy readiness) {

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
        .readiness(new ReadinessPolicy(toSeverityOverrides(config.policy().readinessOverride())))
        .build();
  }

  private static Map<String, Severity> toSeverityOverrides(Map<String, String> raw) {
    final Map<String, Severity> parsed = new LinkedHashMap<>();
    raw.forEach((scenario, value) -> Severity.parse(value).ifPresent(s -> parsed.put(scenario, s)));
    return parsed;
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
   * "systemd-adapter"}). An entry forces that scenario's effective severity regardless of the
   * severity the scenario declares for itself; absent entries defer to the scenario.
   */
  public record ReadinessPolicy(Map<String, Severity> severityOverrides) {
    public ReadinessPolicy {
      severityOverrides = Map.copyOf(severityOverrides);
    }

    public static ReadinessPolicy none() {
      return new ReadinessPolicy(Map.of());
    }

    /** The operator-forced severity for a scenario, if any. */
    public Optional<Severity> override(String scenarioId) {
      return Optional.ofNullable(severityOverrides.get(scenarioId));
    }

    public Map<String, Object> toOutputMap() {
      final Map<String, Object> outputs = new LinkedHashMap<>();
      severityOverrides.forEach(
          (scenario, severity) ->
              outputs.put("readiness.override." + scenario, severity.name().toLowerCase()));
      return outputs;
    }
  }

  public static final class Builder {
    private DebugPolicy debug;
    private NetworkPolicy network;
    private ProvisioningPolicy provisioning;
    private ManifestLinkPolicy manifestLink;
    private ReadinessPolicy readiness = ReadinessPolicy.none();

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

    public ControlplanePolicy build() {
      return new ControlplanePolicy(debug, network, provisioning, manifestLink, readiness);
    }
  }
}
