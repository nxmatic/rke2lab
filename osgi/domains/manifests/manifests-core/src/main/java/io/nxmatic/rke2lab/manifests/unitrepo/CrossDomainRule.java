package io.nxmatic.rke2lab.manifests.unitrepo;

import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistry;
import io.nxmatic.rke2lab.manifests.ManifestsUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The cross-domain rule the resolver cannot enforce on its own: a unit in domain A may depend on a
 * unit in domain B only if A {@code dependsOn} B transitively (or A == B). A plain {@code
 * require("(unit=X)")} resolves X regardless of domain edges, and scoping the require filter to A's
 * reachable domains does not fail resolution — the offending unit is silently pruned via the {@code
 * cardinality:=multiple} containment requirement rather than reported. So the rule is a separate
 * pure check over the assembled registry's domain/unit graph, reusing the retired walker's
 * transitive-domain reachability.
 */
public final class CrossDomainRule {

  private CrossDomainRule() {}

  /** Throws {@link IllegalStateException} on the first illegal cross-domain unit dependency. */
  public static void check(ManifestsDomainRegistry registry) {
    Map<String, List<String>> dependsOnDomainIds = new LinkedHashMap<>();
    Map<String, List<String>> unitDependencies = new LinkedHashMap<>();
    Map<String, String> domainIdByUnitId = new LinkedHashMap<>();
    for (ManifestsDomain domain : registry.domains()) {
      dependsOnDomainIds.put(domain.domainId(), domain.dependsOnDomainIds());
      for (ManifestsUnit unit : domain.units()) {
        domainIdByUnitId.put(unit.manifestUnitId(), domain.domainId());
        unitDependencies.put(unit.manifestUnitId(), unit.dependsOnManifestsUnitIds());
      }
    }
    check(dependsOnDomainIds, unitDependencies, domainIdByUnitId);
  }

  /**
   * Graph form, testable directly without a registry. {@code dependsOnDomainIds}: domain id → its
   * direct domain deps; {@code unitDependencies}: unit id → its {@code dependsOn} unit ids; {@code
   * domainIdByUnitId}: unit id → its owning domain id.
   */
  static void check(
      Map<String, List<String>> dependsOnDomainIds,
      Map<String, List<String>> unitDependencies,
      Map<String, String> domainIdByUnitId) {
    for (Map.Entry<String, List<String>> entry : unitDependencies.entrySet()) {
      String unitId = entry.getKey();
      String unitDomainId = requireDomainId(domainIdByUnitId, unitId);
      for (String dependencyUnitId : entry.getValue()) {
        String dependencyDomainId =
            requireDomainIdWithContext(domainIdByUnitId, unitId, dependencyUnitId);
        if (!unitDomainId.equals(dependencyDomainId)
            && !dependsOnDomainTransitively(dependsOnDomainIds, unitDomainId, dependencyDomainId)) {
          throw new IllegalStateException(
              "Manifest unit dependency crosses domains without a matching domain dependency: "
                  + unitId
                  + " -> "
                  + dependencyUnitId
                  + " ("
                  + unitDomainId
                  + " -> "
                  + dependencyDomainId
                  + ")");
        }
      }
    }
  }

  private static String requireDomainId(Map<String, String> domainIdByUnitId, String unitId) {
    String domainId = domainIdByUnitId.get(unitId);
    if (domainId == null) {
      throw new IllegalStateException(
          "Manifest unit dependency references unknown unit: " + unitId);
    }
    return domainId;
  }

  private static String requireDomainIdWithContext(
      Map<String, String> domainIdByUnitId, String requiringUnitId, String dependencyUnitId) {
    String domainId = domainIdByUnitId.get(dependencyUnitId);
    if (domainId == null) {
      throw new IllegalStateException(
          "Manifest unit dependency references unknown unit: "
              + requiringUnitId
              + " -> "
              + dependencyUnitId);
    }
    return domainId;
  }

  private static boolean dependsOnDomainTransitively(
      Map<String, List<String>> dependsOnDomainIds, String domainId, String dependencyDomainId) {
    return dependsOnDomainTransitively(
        dependsOnDomainIds, domainId, dependencyDomainId, new HashSet<>());
  }

  private static boolean dependsOnDomainTransitively(
      Map<String, List<String>> dependsOnDomainIds,
      String domainId,
      String dependencyDomainId,
      Set<String> visited) {
    if (!visited.add(domainId)) {
      return false;
    }
    for (String direct : dependsOnDomainIds.getOrDefault(domainId, List.of())) {
      if (direct.equals(dependencyDomainId)
          || dependsOnDomainTransitively(dependsOnDomainIds, direct, dependencyDomainId, visited)) {
        return true;
      }
    }
    return false;
  }
}
