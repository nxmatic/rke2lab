package io.nxmatic.rk2lab.manifests.layers.env;

import io.nxmatic.rk2lab.manifests.api.ManifestYaml;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contract for layer domains to contribute environment variables. Implementations are registered
 * via Java Service Provider Interface metadata, discovered through {@link java.util.ServiceLoader},
 * and aggregated by {@link LayerEnvContributorRegistry} for runtime env-config synthesis.
 */
public interface LayerEnvContributor {

  /**
   * Unique identifier for this contributor (e.g., "networking", "storage", "high-availability").
   * Used for ConfigMap naming and override ordering.
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
      final Map<String, Object> document =
          buildConfigMapDocument(
              "env-section-" + section, section, contributeVariables(section, context));
      ManifestYaml.writeDocument(outputDir.resolve(layerId() + "-" + section + ".yml"), document);
    }
  }

  /**
   * Build a Kubernetes ConfigMap document for a contributor section. The caller hands the result to
   * {@link ManifestYaml} for rendering — no caller serializes YAML by hand.
   */
  static Map<String, Object> buildConfigMapDocument(
      String name, String section, Map<String, String> variables) {
    final Map<String, Object> annotations = new LinkedHashMap<>();
    annotations.put("config.kubernetes.io/local-config", "true");
    annotations.put("env.rk2lab.nxmatic.io/section", section);
    annotations.put("rk2lab.nxmatic.io/managed-by", "layer-contributor");

    final Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("annotations", annotations);
    metadata.put("name", name);

    final Map<String, Object> document = new LinkedHashMap<>();
    document.put("apiVersion", "v1");
    document.put("kind", "ConfigMap");
    document.put("metadata", metadata);
    document.put("data", variables);
    return document;
  }
}
