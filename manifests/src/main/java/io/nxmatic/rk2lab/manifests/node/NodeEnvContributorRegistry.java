package io.nxmatic.rk2lab.manifests.node;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Aggregates all {@link NodeEnvContributor} implementations discovered via {@link
 * java.util.ServiceLoader} and manages env var generation for runtime env-config synthesis.
 */
public class NodeEnvContributorRegistry {

  private final List<NodeEnvContributor> contributors;
  private final NodeEnvContext context;

  public NodeEnvContributorRegistry(NodeEnvContext context) {
    this.context = context;
    this.contributors = loadContributors();
  }

  /** Load all registered NodeEnvContributor implementations via ServiceLoader. */
  private List<NodeEnvContributor> loadContributors() {
    var loader = ServiceLoader.load(NodeEnvContributor.class);
    var list = new ArrayList<NodeEnvContributor>();
    for (var contributor : loader) {
      list.add(contributor);
    }
    return list;
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
    contributors.sort(
        (a, b) ->
            order.getOrDefault(a.domainId(), 99).compareTo(order.getOrDefault(b.domainId(), 99)));
    return contributors;
  }

  /**
   * Aggregate all domain contributions into a merged env map (for 99-configmap generation). Later
   * domains override earlier ones (cluster < node < storage < networking < high-availability <
   * runtime < gitops).
   */
  public Map<String, String> aggregateContributions() throws IOException {
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
  public void writeAllContributions(Path outputDir) throws IOException {
    for (var contributor : orderedContributors()) {
      contributor.writeConfigMap(outputDir, context);
    }
  }
}
