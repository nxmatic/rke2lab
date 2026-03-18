// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import java.util.List;

public record LayerDomain(
    String domainId, List<String> dependsOnDomainIds, List<? extends ManifestUnit> layers) {

  public LayerDomain(final String domainId, final List<? extends ManifestUnit> layers) {
    this(domainId, List.of(), layers);
  }

  public LayerDomain {
    if (domainId == null || domainId.isBlank()) {
      throw new IllegalArgumentException("Domain id must not be blank");
    }
    if (dependsOnDomainIds == null) {
      throw new IllegalArgumentException("Domain dependencies must not be null: " + domainId);
    }
    dependsOnDomainIds = List.copyOf(dependsOnDomainIds);
    if (layers == null || layers.isEmpty()) {
      throw new IllegalArgumentException("Domain must define at least one layer: " + domainId);
    }
    layers = List.copyOf(layers);
  }
}
