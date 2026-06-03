package io.nxmatic.rke2lab.controlplane.policy;

import com.pulumi.Config;
import io.nxmatic.rke2lab.controlplane.SeedLog;
import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.ManifestDomainPolicy;
import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical operational policy derived from Pulumi config for Stage A bootstrap. */
public record ControlplanePolicy(
    DebugPolicy debug,
    NetworkPolicy network,
    ProvisioningPolicy provisioning,
    ManifestLinkPolicy manifestLink) {

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

  public static ControlplanePolicy from(Config config) {
    EnvironmentValues environment = new EnvironmentValues(config);

    DebugPolicy debugPolicy =
        new DebugPolicy(
            environment.bool("policy.debug.mesh.enabled", false),
            environment.bool("policy.debug.networking.enabled", false),
            environment.bool("policy.debug.nriPlugins.flox.enabled", false));

    NetworkPolicy networkPolicy =
        new NetworkPolicy(environment.bool("policy.network.lan.binding.enabled", true));

    ProvisioningPolicy provisioningPolicy =
        new ProvisioningPolicy(environment.bool("policy.gitDirtyCheck.enabled", true));

    boolean clusterApiEnabled = environment.bool("policy.link.clusterApi.enabled", true);
    SeedLog.debug(
        "policy",
        "clusterApi raw='"
            + environment.raw("policy.link.clusterApi.enabled")
            + "' parsed="
            + clusterApiEnabled);

    ManifestLinkPolicy manifestLinkPolicy =
        new ManifestLinkPolicy(
            ManifestDomainPolicy.builder()
                .domainCatalog(MANIFEST_DOMAIN_CATALOG)
                .stageADefaults()
                .cluster(true) // Always enabled - creates rke2lab-system namespace
                .storage(environment.bool("policy.link.storage.enabled", true))
                .gitops(environment.bool("policy.link.gitops.enabled", true))
                .runtime(true) // Always enabled - RKE2 config, Flox runtime, core bootstrap
                .networking(environment.bool("policy.link.networking.enabled", true))
                .mesh(environment.bool("policy.link.mesh.enabled", false))
                .highAvailability(environment.bool("policy.link.highAvailability.enabled", true))
                .cicd(environment.bool("policy.link.cicd.enabled", true))
                .clusterApi(clusterApiEnabled)
                .platform(true) // Always enabled - cert-manager, kubernetes-replicator
                .build(),
            new ManifestLinkPolicy.DebugPolicy(debugPolicy::domainDebug));

    return builder()
        .debug(debugPolicy)
        .network(networkPolicy)
        .provisioning(provisioningPolicy)
        .manifestLink(manifestLinkPolicy)
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

  public static final class Builder {
    private DebugPolicy debug;
    private NetworkPolicy network;
    private ProvisioningPolicy provisioning;
    private ManifestLinkPolicy manifestLink;

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

    public ControlplanePolicy build() {
      return new ControlplanePolicy(debug, network, provisioning, manifestLink);
    }
  }

  private record EnvironmentValues(Config config) {
    @SuppressWarnings("null")
    String raw(String key) {
      return config.get(key).orElse("");
    }

    boolean bool(String key, boolean defaultValue) {
      String value = raw(key);
      if (value.isBlank()) {
        return defaultValue;
      }
      return switch (value.trim().toLowerCase()) {
        case "1", "true", "yes", "on" -> true;
        case "0", "false", "no", "off" -> false;
        default -> throw new IllegalArgumentException("Invalid boolean for " + key + ": " + value);
      };
    }
  }
}
