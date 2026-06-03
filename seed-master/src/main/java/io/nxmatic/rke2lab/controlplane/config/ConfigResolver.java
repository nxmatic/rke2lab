package io.nxmatic.rke2lab.controlplane.config;

import com.pulumi.Config;

/** Helper for resolving Pulumi configuration values with defaults and validation. */
public final class ConfigResolver {

  private ConfigResolver() {}

  public static boolean resolveReadinessEnabled(Config config) {
    return resolveBooleanWithDefault(config, "readiness.enabled", true);
  }

  public static boolean resolveCleanWorktreeRequired(Config config) {
    return resolveBooleanWithDefault(config, "entryGate.cleanWorktree.required", true);
  }

  public static boolean resolveBboxFailOnError(Config config) {
    return resolveBooleanWithDefault(config, "bbox.reconcile.failOnError", true);
  }

  private static boolean resolveBooleanWithDefault(
      Config config, String key, boolean defaultValue) {
    if (config == null) {
      return defaultValue;
    }

    final String raw = config.get(key).orElse("");
    if (raw.isBlank()) {
      return defaultValue;
    }

    return switch (raw.toLowerCase()) {
      case "1", "true", "yes", "on" -> true;
      case "0", "false", "no", "off" -> false;
      default -> throw new IllegalArgumentException("Invalid boolean for " + key + ": " + raw);
    };
  }
}
