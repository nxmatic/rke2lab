package io.nxmatic.rke2lab.manifests.node;

import io.nxmatic.rke2lab.manifests.ManifestAnnotations;
import io.nxmatic.rke2lab.manifests.ManifestYaml;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contract for domains to contribute node environment variables. Implementations are registered via
 * Java Service Provider Interface metadata, discovered through {@link java.util.ServiceLoader}, and
 * aggregated by {@link NodeEnvContributorRegistry} for runtime env-config synthesis.
 */
public interface NodeEnvContributor {

  /**
   * Unique identifier for this contributor (e.g., "networking", "storage", "high-availability").
   * Used for ConfigMap naming and override ordering.
   */
  String domainId();

  /**
   * List of environment sections this domain contributes. Examples: ["cilium", "network-cluster",
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
  Map<String, String> contributeVariables(String sectionName, NodeEnvContext context)
      throws IOException;

  /**
   * Optional: Write ConfigMap YAML for this contribution to disk. Default implementation uses
   * Kubernetes API conventions.
   *
   * @param outputDir directory where ConfigMap YAML will be written
   * @param context bootstrap context
   * @throws IOException if write fails
   */
  default void writeConfigMap(Path outputDir, NodeEnvContext context) throws IOException {
    for (String section : contributedSections()) {
      final Map<String, Object> document =
          buildConfigMapDocument(
              "env-section-" + section, section, contributeVariables(section, context));
      ManifestYaml.writeDocument(outputDir.resolve(domainId() + "-" + section + ".yml"), document);
    }
  }

  /**
   * Build a Kubernetes ConfigMap document for a contributor section. The caller hands the result to
   * {@link ManifestYaml} for rendering — no caller serializes YAML by hand.
   */
  static Map<String, Object> buildConfigMapDocument(
      String name, String section, Map<String, String> variables) {
    final Map<String, Object> annotations = new LinkedHashMap<>();
    annotations.put(ManifestAnnotations.LOCAL_CONFIG, "true");
    annotations.put("env.rke2lab.nxmatic.io/section", section);
    annotations.put("rke2lab.nxmatic.io/managed-by", "node-env-contributor");

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
