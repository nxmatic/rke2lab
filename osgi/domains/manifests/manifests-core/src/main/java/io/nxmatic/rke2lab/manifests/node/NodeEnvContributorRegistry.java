package io.nxmatic.rke2lab.manifests.node;

import io.nxmatic.rke2lab.manifests.YamlMapper;
import io.nxmatic.rke2lab.manifests.port.ManifestAnnotations;
import io.nxmatic.rke2lab.manifests.port.node.NodeEnvContext;
import io.nxmatic.rke2lab.manifests.port.node.NodeEnvContributor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

/**
 * Aggregates all {@link NodeEnvContributor} implementations and manages env var generation for
 * runtime env-config synthesis. Under SCR the contributors arrive by {@link Reference} field
 * injection (cardinality {@code MULTIPLE}).
 */
@Component(service = NodeEnvContributorRegistry.class)
public class NodeEnvContributorRegistry {

  @Reference(cardinality = ReferenceCardinality.MULTIPLE)
  private List<NodeEnvContributor> contributors;

  @Reference private YamlMapper yaml;

  /**
   * DS activation path: SCR instantiates via this constructor and injects {@link #contributors} and
   * {@link #yaml}.
   */
  public NodeEnvContributorRegistry() {}

  /**
   * Order contributors by domain priority (deterministic). Execution order: cluster → node →
   * storage → networking → high-availability → runtime → gitops.
   */
  public List<NodeEnvContributor> orderedContributors() {
    var order =
        Map.of(
            "cluster", 1,
            "node", 2,
            "storage", 3,
            "networking", 4,
            "high-availability", 5,
            "runtime", 6,
            "gitops", 7);
    return contributors.stream()
        .sorted(Comparator.comparingInt(c -> order.getOrDefault(c.domainId(), 99)))
        .toList();
  }

  /**
   * Aggregate all domain contributions into a merged env map (for 99-configmap generation). Later
   * domains override earlier ones (cluster < node < storage < networking < high-availability <
   * runtime < gitops).
   */
  public Map<String, String> aggregateContributions(NodeEnvContext context) throws IOException {
    var aggregated = new TreeMap<String, String>();
    for (var contributor : orderedContributors()) {
      for (String section : contributor.contributedSections()) {
        var vars = contributor.contributeVariables(section, context);
        aggregated.putAll(vars); // Later domains override
      }
    }
    return aggregated;
  }

  /** Write all domain contributions as individual ConfigMap YAML files. */
  public void writeAllContributions(Path outputDir, NodeEnvContext context) throws IOException {
    for (var contributor : orderedContributors()) {
      for (String section : contributor.contributedSections()) {
        final Map<String, Object> document =
            buildConfigMapDocument(
                "env-section-" + section,
                section,
                contributor.contributeVariables(section, context));
        yaml.write(outputDir.resolve(contributor.domainId() + "-" + section + ".yml"))
            .document(document);
      }
    }
  }

  /**
   * Build a Kubernetes ConfigMap document for a contributor section. The result is handed to {@link
   * YamlMapper} for rendering — no caller serializes YAML by hand.
   */
  private static Map<String, Object> buildConfigMapDocument(
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
