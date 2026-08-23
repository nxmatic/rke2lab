package io.seedmatic.rke2lab.manifests.unitrepo;

import io.seedmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import org.osgi.resource.Namespace;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;

/**
 * Derives the manifest-unit visit order from a resolved {@link
 * io.seedmatic.rke2lab.unitrepo.core.UnitResolver} wiring — a pure function with no synthesis side
 * effects. It reproduces the retired hand-rolled walker's guarantees: a unit is visited after the
 * units it depends on, and units of a depended-on domain precede those of the depending domain.
 *
 * <p>Two wire kinds live on {@link ManifestsUniverse#NS_UNIT}: a unit's real {@code require}
 * dependency and a domain's {@code requireAll} containment. The latter carries the {@code
 * cardinality:=multiple} directive and is EXCLUDED — containment must not inject ordering between
 * sibling units. Domain→domain {@code require} edges on {@link ManifestsUniverse#NS_DOMAIN} are
 * projected onto units: for a domain dependency {@code B → A}, every unit of A is made to precede
 * every unit of B.
 */
public final class ManifestsVisitOrder {

  private final Map<String, Set<String>> dependencies;

  public ManifestsVisitOrder(Map<Resource, List<Wire>> wiring, Map<String, UnitResource> byId) {
    this.dependencies = buildDependencyGraph(wiring, byId);
  }

  /**
   * The dependency graph as {@code dependent unit id → set of unit ids it must follow}. Exposed so
   * callers can assert containment did not inject spurious sibling edges.
   */
  public Map<String, Set<String>> dependencyEdges() {
    return Map.copyOf(dependencies);
  }

  /** Topologically ordered unit ids: every unit appears after the units it depends on. */
  public List<String> order() {
    Map<String, Integer> inDegree = new LinkedHashMap<>();
    Map<String, Set<String>> dependents = new LinkedHashMap<>();
    for (String unit : dependencies.keySet()) {
      inDegree.putIfAbsent(unit, 0);
      dependents.putIfAbsent(unit, new LinkedHashSet<>());
    }
    for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
      String dependent = entry.getKey();
      for (String dependency : entry.getValue()) {
        dependents.computeIfAbsent(dependency, k -> new LinkedHashSet<>()).add(dependent);
        inDegree.merge(dependent, 1, Integer::sum);
      }
    }

    // A PriorityQueue (natural id order), NOT a FIFO deque: Kahn's algorithm is free to emit any
    // ready unit, so the tie-break decides the order of independent/sibling units. Insertion order
    // would inherit the non-deterministic iteration order of the upstream resolver maps (byId /
    // wiring), churning the synthesis output run to run; a sorted tie-break makes the visit order
    // CANONICAL by construction — deterministic regardless of any upstream HashMap.
    PriorityQueue<String> ready = new PriorityQueue<>();
    for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
      if (entry.getValue() == 0) {
        ready.add(entry.getKey());
      }
    }

    List<String> ordered = new ArrayList<>(inDegree.size());
    while (!ready.isEmpty()) {
      String unit = ready.poll();
      ordered.add(unit);
      for (String dependent : dependents.getOrDefault(unit, Set.of())) {
        if (inDegree.merge(dependent, -1, Integer::sum) == 0) {
          ready.add(dependent);
        }
      }
    }

    if (ordered.size() != inDegree.size()) {
      List<String> cyclic =
          inDegree.entrySet().stream()
              .filter(e -> e.getValue() > 0)
              .map(Map.Entry::getKey)
              .sorted()
              .toList();
      throw new IllegalStateException("Cyclic manifest unit dependency detected: " + cyclic);
    }
    return ordered;
  }

  private static Map<String, Set<String>> buildDependencyGraph(
      Map<Resource, List<Wire>> wiring, Map<String, UnitResource> byId) {
    Map<Resource, String> idByResource = new LinkedHashMap<>();
    for (Map.Entry<String, UnitResource> entry : byId.entrySet()) {
      idByResource.put(entry.getValue(), entry.getKey());
    }

    Map<String, Set<String>> graph = new LinkedHashMap<>();
    Map<String, Set<String>> unitsOfDomain = new LinkedHashMap<>();
    Map<String, Set<String>> domainDependencies = new LinkedHashMap<>();

    for (List<Wire> wires : wiring.values()) {
      for (Wire wire : wires) {
        String namespace = wire.getRequirement().getNamespace();
        if (isContainment(wire)) {
          continue;
        }
        String provider = idByResource.get(wire.getProvider());
        String requirer = idByResource.get(wire.getRequirer());
        if (provider == null || requirer == null) {
          continue;
        }
        if (ManifestsUniverse.NS_UNIT.equals(namespace)) {
          graph.computeIfAbsent(requirer, k -> new LinkedHashSet<>()).add(provider);
        } else if (ManifestsUniverse.NS_DOMAIN.equals(namespace)) {
          domainDependencies.computeIfAbsent(requirer, k -> new LinkedHashSet<>()).add(provider);
        }
      }
    }

    for (Map.Entry<String, UnitResource> entry : byId.entrySet()) {
      var unitCaps = entry.getValue().getCapabilities(ManifestsUniverse.NS_UNIT);
      if (unitCaps.isEmpty()) {
        continue;
      }
      String id = entry.getKey();
      String domain =
          (String) unitCaps.getFirst().getAttributes().get(ManifestsUniverse.ATTR_DOMAIN);
      if (domain == null) {
        throw new IllegalStateException("unit " + id + " is missing its domain attribute");
      }
      unitsOfDomain.computeIfAbsent(domain, k -> new LinkedHashSet<>()).add(id);
      graph.putIfAbsent(id, new LinkedHashSet<>());
    }

    // project domain → domain dependencies onto units: units of A precede units of B when B → A.
    for (Map.Entry<String, Set<String>> entry : domainDependencies.entrySet()) {
      String dependingDomain = entry.getKey();
      Set<String> dependingUnits = unitsOfDomain.getOrDefault(dependingDomain, Set.of());
      for (String dependedDomain : entry.getValue()) {
        Set<String> dependedUnits = unitsOfDomain.getOrDefault(dependedDomain, Set.of());
        for (String dependingUnit : dependingUnits) {
          graph.computeIfAbsent(dependingUnit, k -> new LinkedHashSet<>()).addAll(dependedUnits);
        }
      }
    }

    return graph;
  }

  private static boolean isContainment(Wire wire) {
    return Namespace.CARDINALITY_MULTIPLE.equals(
        wire.getRequirement().getDirectives().get(Namespace.REQUIREMENT_CARDINALITY_DIRECTIVE));
  }
}
