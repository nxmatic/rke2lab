package io.nxmatic.rke2lab.manifests.node;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

/**
 * Aggregates all {@link NodeEnvContributor} implementations and manages env var generation for
 * runtime env-config synthesis.
 *
 * <p>Dual-path discovery (R3, additive): under SCR the contributors arrive by {@link Reference}
 * field injection (cardinality {@code MULTIPLE}); the framework-less callers use {@link
 * #forServiceLoader()}, which discovers them via {@link ServiceLoader}. The static path is retired
 * in R5 once every caller boots under Felix.
 */
@Component(service = NodeEnvContributorRegistry.class)
public class NodeEnvContributorRegistry {

  @Reference(cardinality = ReferenceCardinality.MULTIPLE)
  private List<NodeEnvContributor> contributors;

  /**
   * DS activation path: SCR instantiates via this constructor and injects {@link #contributors}.
   */
  public NodeEnvContributorRegistry() {}

  private NodeEnvContributorRegistry(List<NodeEnvContributor> contributors) {
    this.contributors = contributors;
  }

  /** Framework-less path: discover contributors via {@link ServiceLoader}. */
  public static NodeEnvContributorRegistry forServiceLoader() {
    var loader = ServiceLoader.load(NodeEnvContributor.class);
    var list = new ArrayList<NodeEnvContributor>();
    for (var contributor : loader) {
      list.add(contributor);
    }
    return new NodeEnvContributorRegistry(List.copyOf(list));
  }

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
      contributor.writeConfigMap(outputDir, context);
    }
  }
}
