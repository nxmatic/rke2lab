// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LayerDomainRegistry {

  private final Map<String, LayerDomain> domainsById;
  private final List<ManifestUnit> manifestUnits;
  private final Map<String, String> domainIdByManifestUnitId;

  public LayerDomainRegistry(final List<LayerDomain> domains) {
    if (domains == null || domains.isEmpty()) {
      throw new IllegalArgumentException("At least one domain must be configured");
    }

    LinkedHashMap<String, LayerDomain> byId = new LinkedHashMap<>();
    for (LayerDomain domain : domains) {
      if (byId.put(domain.domainId(), domain) != null) {
        throw new IllegalStateException("Duplicate domain id: " + domain.domainId());
      }
    }

    this.domainsById = Map.copyOf(byId);
    this.manifestUnits =
        byId.values().stream()
            .flatMap(domain -> domain.layers().stream())
            .map(layer -> (ManifestUnit) layer)
            .toList();

    HashMap<String, String> byManifestUnitId = new HashMap<>();
    for (LayerDomain domain : byId.values()) {
      for (ManifestUnit manifestUnit : domain.layers()) {
        String previous = byManifestUnitId.put(manifestUnit.manifestUnitId(), domain.domainId());
        if (previous != null) {
          throw new IllegalStateException(
              "Manifest unit is assigned to multiple domains: " + manifestUnit.manifestUnitId());
        }
      }
    }
    this.domainIdByManifestUnitId = Map.copyOf(byManifestUnitId);

    validateDomainDependencies();
  }

  public List<LayerDomain> domains() {
    return List.copyOf(domainsById.values());
  }

  public List<ManifestUnit> manifestUnits() {
    return manifestUnits;
  }

  public String requireDomainIdForManifestUnit(final String manifestUnitId) {
    final String domainId = domainIdByManifestUnitId.get(manifestUnitId);
    if (domainId == null) {
      throw new IllegalStateException(
          "Unable to resolve domain for manifest unit: " + manifestUnitId);
    }
    return domainId;
  }

  public void applyManifestUnitWithDomainDependencies(
      final String manifestUnitId,
      final ManifestUnitDependencyApplier manifestUnitDependencyApplier) {
    String domainId = requireDomainIdForManifestUnit(manifestUnitId);

    applyDomainWithDependencies(
        domainId, manifestUnitDependencyApplier, new HashSet<>(), new HashSet<>());

    manifestUnitDependencyApplier.applyManifestUnitWithDependencies(manifestUnitId);
  }

  private void validateDomainDependencies() {
    for (LayerDomain domain : domainsById.values()) {
      for (String dependencyDomainId : domain.dependsOnDomainIds()) {
        if (!domainsById.containsKey(dependencyDomainId)) {
          throw new IllegalStateException(
              "Domain dependency references unknown domain: "
                  + domain.domainId()
                  + " -> "
                  + dependencyDomainId);
        }
      }
    }
  }

  private void applyDomainWithDependencies(
      final String domainId,
      final ManifestUnitDependencyApplier manifestUnitDependencyApplier,
      final Set<String> visitingDomainIds,
      final Set<String> appliedDomainIds) {
    if (appliedDomainIds.contains(domainId)) {
      return;
    }

    if (!visitingDomainIds.add(domainId)) {
      throw new IllegalStateException("Cyclic domain dependency detected at: " + domainId);
    }

    LayerDomain domain = domainsById.get(domainId);
    if (domain == null) {
      throw new IllegalStateException("Unknown domain dependency: " + domainId);
    }

    for (String dependencyDomainId : domain.dependsOnDomainIds()) {
      applyDomainWithDependencies(
          dependencyDomainId, manifestUnitDependencyApplier, visitingDomainIds, appliedDomainIds);
    }

    for (ManifestUnit manifestUnit : domain.layers()) {
      manifestUnitDependencyApplier.applyManifestUnitWithDependencies(
          manifestUnit.manifestUnitId());
    }

    visitingDomainIds.remove(domainId);
    appliedDomainIds.add(domainId);
  }
}
