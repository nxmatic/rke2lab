// @codebase
package io.nxmatic.rke2lab.manifests;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ManifestsDomainRegistry {

  private final Map<String, ManifestsDomain> domainsById;
  private final List<ManifestsUnit> manifestUnits;
  private final Map<String, String> domainIdByManifestsUnitId;

  public ManifestsDomainRegistry(final List<ManifestsDomain> domains) {
    if (domains == null || domains.isEmpty()) {
      throw new IllegalArgumentException("At least one domain must be configured");
    }

    LinkedHashMap<String, ManifestsDomain> byId = new LinkedHashMap<>();
    for (ManifestsDomain domain : domains) {
      if (byId.put(domain.domainId(), domain) != null) {
        throw new IllegalStateException("Duplicate domain id: " + domain.domainId());
      }
    }

    this.domainsById = Map.copyOf(byId);
    this.manifestUnits =
        byId.values().stream()
            .flatMap(domain -> domain.units().stream())
            .map(layer -> (ManifestsUnit) layer)
            .toList();

    HashMap<String, String> byManifestsUnitId = new HashMap<>();
    for (ManifestsDomain domain : byId.values()) {
      for (ManifestsUnit manifestUnit : domain.units()) {
        String previous = byManifestsUnitId.put(manifestUnit.manifestUnitId(), domain.domainId());
        if (previous != null) {
          throw new IllegalStateException(
              "Manifest unit is assigned to multiple domains: " + manifestUnit.manifestUnitId());
        }
      }
    }
    this.domainIdByManifestsUnitId = Map.copyOf(byManifestsUnitId);

    validateDomainDependencies();
    validateManifestsUnitDependencies();
  }

  public List<ManifestsDomain> domains() {
    return List.copyOf(domainsById.values());
  }

  public List<ManifestsUnit> manifestUnits() {
    return manifestUnits;
  }

  public String requireDomainIdForManifestsUnit(final String manifestUnitId) {
    final String domainId = domainIdByManifestsUnitId.get(manifestUnitId);
    if (domainId == null) {
      throw new IllegalStateException(
          "Unable to resolve domain for manifest unit: " + manifestUnitId);
    }
    return domainId;
  }

  public void applyManifestsUnitWithDomainDependencies(
      final String manifestUnitId,
      final ManifestsUnitDependencyApplier manifestUnitDependencyApplier) {
    String domainId = requireDomainIdForManifestsUnit(manifestUnitId);

    applyDomainWithDependencies(
        domainId, manifestUnitDependencyApplier, new HashSet<>(), new HashSet<>());

    manifestUnitDependencyApplier.applyManifestsUnitWithDependencies(manifestUnitId);
  }

  private void validateDomainDependencies() {
    for (ManifestsDomain domain : domainsById.values()) {
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

  private void validateManifestsUnitDependencies() {
    for (ManifestsUnit manifestUnit : manifestUnits) {
      final String manifestUnitId = manifestUnit.manifestUnitId();
      final String manifestUnitDomainId = requireDomainIdForManifestsUnit(manifestUnitId);
      for (String dependencyManifestsUnitId : manifestUnit.dependsOnManifestsUnitIds()) {
        final String dependencyDomainId = domainIdByManifestsUnitId.get(dependencyManifestsUnitId);
        if (dependencyDomainId == null) {
          throw new IllegalStateException(
              "Manifest unit dependency references unknown unit: "
                  + manifestUnitId
                  + " -> "
                  + dependencyManifestsUnitId);
        }
        if (!manifestUnitDomainId.equals(dependencyDomainId)
            && !dependsOnDomainTransitively(manifestUnitDomainId, dependencyDomainId)) {
          throw new IllegalStateException(
              "Manifest unit dependency crosses domains without a matching domain dependency: "
                  + manifestUnitId
                  + " -> "
                  + dependencyManifestsUnitId
                  + " ("
                  + manifestUnitDomainId
                  + " -> "
                  + dependencyDomainId
                  + ")");
        }
      }
    }

    final Set<String> visitingManifestsUnitIds = new HashSet<>();
    final Set<String> visitedManifestsUnitIds = new HashSet<>();
    for (ManifestsUnit manifestUnit : manifestUnits) {
      validateManifestsUnitAcyclic(
          manifestUnit.manifestUnitId(), visitingManifestsUnitIds, visitedManifestsUnitIds);
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

    final ManifestsDomain domain = domainsById.get(domainId);
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

  private void validateManifestsUnitAcyclic(
      final String manifestUnitId,
      final Set<String> visitingManifestsUnitIds,
      final Set<String> visitedManifestsUnitIds) {
    if (visitedManifestsUnitIds.contains(manifestUnitId)) {
      return;
    }
    if (!visitingManifestsUnitIds.add(manifestUnitId)) {
      throw new IllegalStateException(
          "Cyclic manifest unit dependency detected at: " + manifestUnitId);
    }

    final ManifestsUnit manifestUnit =
        manifestUnits.stream()
            .filter(candidate -> candidate.manifestUnitId().equals(manifestUnitId))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Manifest unit dependency references unknown unit: " + manifestUnitId));

    for (String dependencyManifestsUnitId : manifestUnit.dependsOnManifestsUnitIds()) {
      validateManifestsUnitAcyclic(
          dependencyManifestsUnitId, visitingManifestsUnitIds, visitedManifestsUnitIds);
    }

    visitingManifestsUnitIds.remove(manifestUnitId);
    visitedManifestsUnitIds.add(manifestUnitId);
  }

  private void applyDomainWithDependencies(
      final String domainId,
      final ManifestsUnitDependencyApplier manifestUnitDependencyApplier,
      final Set<String> visitingDomainIds,
      final Set<String> appliedDomainIds) {
    if (appliedDomainIds.contains(domainId)) {
      return;
    }

    if (!visitingDomainIds.add(domainId)) {
      throw new IllegalStateException("Cyclic domain dependency detected at: " + domainId);
    }

    ManifestsDomain domain = domainsById.get(domainId);
    if (domain == null) {
      throw new IllegalStateException("Unknown domain dependency: " + domainId);
    }

    for (String dependencyDomainId : domain.dependsOnDomainIds()) {
      applyDomainWithDependencies(
          dependencyDomainId, manifestUnitDependencyApplier, visitingDomainIds, appliedDomainIds);
    }

    for (ManifestsUnit manifestUnit : domain.units()) {
      manifestUnitDependencyApplier.applyManifestsUnitWithDependencies(
          manifestUnit.manifestUnitId());
    }

    visitingDomainIds.remove(domainId);
    appliedDomainIds.add(domainId);
  }
}
