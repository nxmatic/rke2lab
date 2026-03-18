package io.nxmatic.rk2lab.manifests.layers.env;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Aggregates all LayerEnvContributor implementations and manages env var generation. Used by
 * IncusResourceBootstrap to orchestrate layer contributions.
 */
public class LayerEnvContributorRegistry {

  private final List<LayerEnvContributor> contributors;
  private final LayerEnvContext context;

  public LayerEnvContributorRegistry(LayerEnvContext context) {
    this.context = context;
    this.contributors = loadContributors();
  }

  /** Load all registered LayerEnvContributor implementations via ServiceLoader. */
  private List<LayerEnvContributor> loadContributors() {
    var loader = ServiceLoader.load(LayerEnvContributor.class);
    var list = new ArrayList<LayerEnvContributor>();
    for (var contributor : loader) {
      list.add(contributor);
    }
    return list;
  }

  /**
   * Order contributors by layer priority (deterministic). Execution order: cluster → node → storage
   * → networking → ha → runtime → gitops.
   */
  public List<LayerEnvContributor> orderedContributors() {
    var order =
        Map.of(
            "cluster", 1,
            "node", 2,
            "storage", 3,
            "networking", 4,
            "ha", 5,
            "runtime", 6,
            "gitops", 7);
    contributors.sort(
        (a, b) ->
            order.getOrDefault(a.layerId(), 99).compareTo(order.getOrDefault(b.layerId(), 99)));
    return contributors;
  }

  /**
   * Aggregate all layer contributions into a merged env map (for 99-configmap generation). Later
   * layers override earlier ones (cluster < node < storage < networking < ha < runtime < gitops).
   */
  public Map<String, String> aggregateContributions() throws IOException {
    var aggregated = new HashMap<String, String>();
    for (var contributor : orderedContributors()) {
      for (String section : contributor.contributedSections()) {
        var vars = contributor.contributeVariables(section, context);
        aggregated.putAll(vars); // Later layers override
      }
    }
    return aggregated;
  }

  /** Write all layer contributions as individual ConfigMap YAML files. */
  public void writeAllContributions(Path outputDir) throws IOException {
    for (var contributor : orderedContributors()) {
      contributor.writeConfigMap(outputDir, context);
    }
  }
}
