package io.nxmatic.rk2lab.manifests.layers.env;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Contract for layer domains to contribute environment variables. Implementations are registered
 * via Java Service Provider Interface metadata, discovered through {@link java.util.ServiceLoader},
 * and aggregated by {@link LayerEnvContributorRegistry} for runtime env-config synthesis.
 */
public interface LayerEnvContributor {

  /**
   * Unique identifier for this contributor (e.g., "networking", "storage", "ha"). Used for
   * ConfigMap naming and override ordering.
   */
  String layerId();

  /**
   * List of environment sections this layer contributes. Examples: ["cilium", "network-cluster",
   * "network-node"]
   */
  List<String> contributedSections();

  /**
   * Generate environment variables for the given section.
   *
   * @param sectionName the section being contributed (one of contributedSections())
   * @param context read-only context with bootstrap paths, node identity, cluster topology
   * @return map of KEY=VALUE environment variables
   * @throws IOException if contribution fails
   */
  Map<String, String> contributeVariables(String sectionName, LayerEnvContext context)
      throws IOException;

  /**
   * Optional: Write ConfigMap YAML for this contribution to disk. Default implementation uses
   * Kubernetes API conventions.
   *
   * @param outputDir directory where ConfigMap YAML will be written
   * @param context bootstrap context
   * @throws IOException if write fails
   */
  default void writeConfigMap(Path outputDir, LayerEnvContext context) throws IOException {
    for (String section : contributedSections()) {
      String configMapName = "env-section-" + section;
      Map<String, String> variables = contributeVariables(section, context);

      // Write standard Kubernetes ConfigMap YAML
      String yaml = generateConfigMapYaml(configMapName, section, variables);
      Path outputFile = outputDir.resolve(layerId() + "-" + section + ".yml");
      java.nio.file.Files.writeString(outputFile, yaml);
    }
  }

  /** Standard ConfigMap YAML generation (reusable by all contributors). */
  static String generateConfigMapYaml(String name, String section, Map<String, String> variables) {

    StringBuilder yaml = new StringBuilder();
    yaml.append("---\n");
    yaml.append("apiVersion: v1\n");
    yaml.append("kind: ConfigMap\n");
    yaml.append("metadata:\n");
    yaml.append("  annotations:\n");
    yaml.append("    config.kubernetes.io/local-config: \"true\"\n");
    yaml.append("    env.rk2lab.nxmatic.io/section: ").append(section).append("\n");
    yaml.append("    rk2lab.nxmatic.io/managed-by: layer-contributor\n");
    yaml.append("  name: ").append(name).append("\n");
    yaml.append("data:\n");

    for (Map.Entry<String, String> entry : variables.entrySet()) {
      yaml.append("  ")
          .append(entry.getKey())
          .append(": ")
          .append(quoteIfNeeded(entry.getValue()))
          .append("\n");
    }

    return yaml.toString();
  }

  /** Quote YAML values if they contain spaces or special chars. */
  static String quoteIfNeeded(String value) {
    if (value.isEmpty()
        || value.contains(" ")
        || value.contains(":")
        || value.equals("false")
        || value.equals("true")) {
      return "\"" + value.replace("\"", "\\\"") + "\"";
    }
    return value;
  }
}
