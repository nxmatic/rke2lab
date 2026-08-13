// @codebase
package io.seedmatic.rke2lab.manifests;

import java.util.List;

/**
 * A manifest domain groups related ManifestsUnits.
 *
 * <p>Domains correspond to a coherent set of Kubernetes manifests (e.g., "cluster-api", "gitops")
 * synthesised and delivered together to the node's {@code server/manifests} tree.
 */
public class ManifestsDomain {
  private final String domainId;
  private final List<String> dependsOnDomainIds;
  private final List<? extends ManifestsUnit> units;

  public ManifestsDomain(
      String domainId, List<String> dependsOnDomainIds, List<? extends ManifestsUnit> units) {
    if (domainId.isBlank()) {
      throw new IllegalArgumentException("Domain id must not be blank");
    }
    if (units.isEmpty()) {
      throw new IllegalArgumentException("Domain must define at least one unit: " + domainId);
    }
    this.domainId = domainId;
    this.dependsOnDomainIds = List.copyOf(dependsOnDomainIds);
    this.units = List.copyOf(units);
  }

  public ManifestsDomain(String domainId, List<? extends ManifestsUnit> units) {
    this(domainId, List.of(), units);
  }

  public String domainId() {
    return domainId;
  }

  public List<String> dependsOnDomainIds() {
    return dependsOnDomainIds;
  }

  public List<? extends ManifestsUnit> units() {
    return units;
  }
}
