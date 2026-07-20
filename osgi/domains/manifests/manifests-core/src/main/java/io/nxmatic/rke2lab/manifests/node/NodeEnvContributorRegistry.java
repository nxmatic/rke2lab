package io.nxmatic.rke2lab.manifests.node;

import io.nxmatic.rke2lab.manifests.contract.node.NodeEnvContributor;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

/**
 * Registry of all {@link NodeEnvContributor} implementations, ordered by domain priority for
 * runtime env-config synthesis. Under SCR the contributors arrive by {@link Reference} field
 * injection (cardinality {@code MULTIPLE}).
 */
@Component(service = NodeEnvContributorRegistry.class)
public class NodeEnvContributorRegistry {

  @Reference(cardinality = ReferenceCardinality.MULTIPLE)
  private List<NodeEnvContributor> contributors;

  /**
   * DS activation path: SCR instantiates via this constructor and injects {@link #contributors}.
   */
  public NodeEnvContributorRegistry() {}

  /**
   * Order contributors by domain priority (deterministic). Execution order: cluster → node →
   * storage → networking → high-availability → runtime → gitops; any other domain (e.g. publish)
   * follows. Sections are disjoint across contributors, so the order only makes iteration
   * deterministic, never resolves a conflict.
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
}
