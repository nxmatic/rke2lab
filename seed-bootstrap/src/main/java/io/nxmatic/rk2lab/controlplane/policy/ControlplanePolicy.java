package io.nxmatic.rk2lab.controlplane.policy;

import com.pulumi.Config;
import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.api.ManifestDomainPolicy;
import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical operational policy derived from Pulumi config for Stage A bootstrap. */
public record ControlplanePolicy(DebugPolicy debug, ManifestLinkPolicy manifestLink) {

  private static final ManifestDomainCatalog MANIFEST_DOMAIN_CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

  public static ControlplanePolicy defaults() {
    return new ControlplanePolicy(
        new DebugPolicy(false, false), ManifestLinkPolicy.stageA(true, true, true, true, false));
  }

  public static ControlplanePolicy from(Config config) {
    EnvironmentValues environment = new EnvironmentValues(config);

    DebugPolicy debugPolicy =
        new DebugPolicy(
            environment.bool("policy.debug.kdns.enabled", false),
            environment.bool("policy.debug.floxShimWrapper.enabled", false));

    ManifestLinkPolicy manifestLinkPolicy =
        new ManifestLinkPolicy(
            ManifestDomainPolicy.builder()
                .domainCatalog(MANIFEST_DOMAIN_CATALOG)
                .stageALinkPolicy(
                    environment.bool("policy.link.highAvailability.enabled", true),
                    environment.bool("policy.link.networking.enabled", true),
                    environment.bool("policy.link.replication.enabled", true),
                    environment.bool("policy.link.storage.enabled", true),
                    environment.bool("policy.link.mesh.enabled", false))
                .build());

    return new ControlplanePolicy(debugPolicy, manifestLinkPolicy);
  }

  public Map<String, String> toEnvMap() {
    Map<String, String> env = new LinkedHashMap<>();
    env.putAll(debug.toEnvMap());
    env.putAll(manifestLink.toEnvMap());
    return env;
  }

  public Map<String, Object> toOutputMap() {
    Map<String, Object> outputs = new LinkedHashMap<>();
    outputs.putAll(debug.toOutputMap());
    outputs.putAll(manifestLink.toOutputMap());
    return outputs;
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
