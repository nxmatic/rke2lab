// @codebase
package io.nxmatic.rk2lab.manifests;

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
    validateManifestUnitDependencies();
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

  private void validateManifestUnitDependencies() {
    for (ManifestUnit manifestUnit : manifestUnits) {
      final String manifestUnitId = manifestUnit.manifestUnitId();
      final String manifestUnitDomainId = requireDomainIdForManifestUnit(manifestUnitId);
      for (String dependencyManifestUnitId : manifestUnit.dependsOnManifestUnitIds()) {
        final String dependencyDomainId = domainIdByManifestUnitId.get(dependencyManifestUnitId);
        if (dependencyDomainId == null) {
          throw new IllegalStateException(
              "Manifest unit dependency references unknown unit: "
                  + manifestUnitId
                  + " -> "
                  + dependencyManifestUnitId);
        }
        if (!manifestUnitDomainId.equals(dependencyDomainId)
            && !dependsOnDomainTransitively(manifestUnitDomainId, dependencyDomainId)) {
          throw new IllegalStateException(
              "Manifest unit dependency crosses domains without a matching domain dependency: "
                  + manifestUnitId
                  + " -> "
                  + dependencyManifestUnitId
                  + " ("
                  + manifestUnitDomainId
                  + " -> "
                  + dependencyDomainId
                  + ")");
        }
      }
    }

    final Set<String> visitingManifestUnitIds = new HashSet<>();
    final Set<String> visitedManifestUnitIds = new HashSet<>();
    for (ManifestUnit manifestUnit : manifestUnits) {
      validateManifestUnitAcyclic(
          manifestUnit.manifestUnitId(), visitingManifestUnitIds, visitedManifestUnitIds);
    }
  }

  private boolean dependsOnDomainTransitively(
      final String domainId, final String dependencyDomainId) {
    return dependsOnDomainTransitively(domainId, dependencyDomainId, new HashSet<>());
  }

  private boolean dependsOnDomainTransitively(
      final String domainId, final String dependencyDomainId, final Set<String> visitedDomainIds) {
    if (!visitedDomainIds.add(domainId)) {
      return false;
    }

    final LayerDomain domain = domainsById.get(domainId);
    if (domain == null) {
      return false;
    }

    for (String directDependencyDomainId : domain.dependsOnDomainIds()) {
      if (directDependencyDomainId.equals(dependencyDomainId)
          || dependsOnDomainTransitively(
              directDependencyDomainId, dependencyDomainId, visitedDomainIds)) {
        return true;
      }
    }
    return false;
  }

  private void validateManifestUnitAcyclic(
      final String manifestUnitId,
      final Set<String> visitingManifestUnitIds,
      final Set<String> visitedManifestUnitIds) {
    if (visitedManifestUnitIds.contains(manifestUnitId)) {
      return;
    }
    if (!visitingManifestUnitIds.add(manifestUnitId)) {
      throw new IllegalStateException(
          "Cyclic manifest unit dependency detected at: " + manifestUnitId);
    }

    final ManifestUnit manifestUnit =
        manifestUnits.stream()
            .filter(candidate -> candidate.manifestUnitId().equals(manifestUnitId))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Manifest unit dependency references unknown unit: " + manifestUnitId));

    for (String dependencyManifestUnitId : manifestUnit.dependsOnManifestUnitIds()) {
      validateManifestUnitAcyclic(
          dependencyManifestUnitId, visitingManifestUnitIds, visitedManifestUnitIds);
    }

    visitingManifestUnitIds.remove(manifestUnitId);
    visitedManifestUnitIds.add(manifestUnitId);
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
