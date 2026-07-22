package io.nxmatic.rke2lab.manifests.unitrepo;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistry;
import io.nxmatic.rke2lab.manifests.ManifestsUnit;
import io.nxmatic.rke2lab.manifests.ManifestsUnitContext;
import io.nxmatic.rke2lab.unitrepo.core.UnitResolver;
import io.nxmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.felix.resolver.Logger;
import org.apache.felix.resolver.ResolverImpl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.service.resolver.ResolutionException;
import org.osgi.service.resolver.Resolver;

/**
 * Drives {@link ManifestsVisitOrder} against a real {@link UnitResolver} wiring. The visit order it
 * derives must reproduce the retired hand-rolled walker's guarantees: a unit visited after its
 * {@code dependsOn} units, units of a depended-on domain before those of the depending domain, and
 * a cycle reported as a diagnosable failure.
 */
@Tag("osgi")
final class ManifestsVisitOrderTest {

  private record StubUnit(String manifestUnitId, List<String> dependsOnManifestsUnitIds)
      implements ManifestsUnit {
    @Override
    public void apply(ManifestsUnitContext context) {}
  }

  private static UnitResource synthesisRoot() {
    return new UnitResource("synthesis-root")
        .requireAll(
            ManifestsUniverse.NS_DOMAIN, "(module=" + ManifestsUniverse.MANIFESTS_MODULE + ")");
  }

  /** The resolver the test injects — a direct ResolverImpl; production binds the OSGi service. */
  private static Resolver resolver() {
    return new ResolverImpl(new Logger(Logger.LOG_ERROR));
  }

  private static int indexOf(List<String> order, String id) {
    int i = order.indexOf(id);
    assertTrue(i >= 0, id + " present in order " + order);
    return i;
  }

  @Test
  void placesDependenciesAndDependedDomainsFirst() throws ResolutionException {
    // unit chain a/A requires a/B requires a/C, all in domain "a".
    ManifestsUnit aC = new StubUnit("a/C", List.of());
    ManifestsUnit aB = new StubUnit("a/B", List.of("a/C"));
    ManifestsUnit aA = new StubUnit("a/A", List.of("a/B"));
    // domain "b" depends on domain "a"; its unit b/X has no unit-level deps.
    ManifestsUnit bX = new StubUnit("b/X", List.of());

    ManifestsDomain a = new ManifestsDomain("a", List.of(), List.of(aC, aB, aA));
    ManifestsDomain b = new ManifestsDomain("b", List.of("a"), List.of(bX));

    ManifestsDomainRegistry registry = new ManifestsDomainRegistry(List.of(a, b));
    ManifestsUniverse universe = new ManifestsUniverse(registry);
    UnitResource root = synthesisRoot();
    Map<Resource, List<Wire>> wiring =
        new UnitResolver(append(universe.universe(), root), resolver()).resolve(root);

    List<String> order = new ManifestsVisitOrder(wiring, universe.byId()).order();

    assertTrue(order.contains("a/A") && order.contains("a/B") && order.contains("a/C"));
    assertTrue(order.contains("b/X"), "b's unit present");

    // unit-level chain: C before B before A
    assertTrue(indexOf(order, "a/C") < indexOf(order, "a/B"), "C precedes B");
    assertTrue(indexOf(order, "a/B") < indexOf(order, "a/A"), "B precedes A");

    // domain b depends on a: every unit of a precedes b/X
    assertTrue(indexOf(order, "a/A") < indexOf(order, "b/X"), "a/A precedes b/X");
    assertTrue(indexOf(order, "a/B") < indexOf(order, "b/X"), "a/B precedes b/X");
    assertTrue(indexOf(order, "a/C") < indexOf(order, "b/X"), "a/C precedes b/X");
  }

  @Test
  void containmentDoesNotOrderSiblingUnits() throws ResolutionException {
    // two sibling units in domain "a", no depends-on between them.
    ManifestsUnit aOne = new StubUnit("a/one", List.of());
    ManifestsUnit aTwo = new StubUnit("a/two", List.of());

    ManifestsDomain a = new ManifestsDomain("a", List.of(), List.of(aOne, aTwo));

    ManifestsDomainRegistry registry = new ManifestsDomainRegistry(List.of(a));
    ManifestsUniverse universe = new ManifestsUniverse(registry);
    UnitResource root = synthesisRoot();
    Map<Resource, List<Wire>> wiring =
        new UnitResolver(append(universe.universe(), root), resolver()).resolve(root);

    ManifestsVisitOrder visitOrder = new ManifestsVisitOrder(wiring, universe.byId());
    List<String> order = visitOrder.order();

    assertTrue(order.contains("a/one"));
    assertTrue(order.contains("a/two"));
    // the shared domain (a requireAll containment wire) must NOT inject a sibling edge:
    // neither unit depends on the other.
    assertTrue(
        visitOrder.dependencyEdges().get("a/one") == null
            || !visitOrder.dependencyEdges().get("a/one").contains("a/two"),
        "no spurious a/two -> a/one edge");
    assertTrue(
        visitOrder.dependencyEdges().get("a/two") == null
            || !visitOrder.dependencyEdges().get("a/two").contains("a/one"),
        "no spurious a/one -> a/two edge");
  }

  @Test
  void cycleInUnitDependenciesIsDiagnosed() throws ResolutionException {
    // build a 2-cycle directly, bypassing the registry's own acyclic guard, so the topo-sort's
    // cycle detection is what we exercise.
    UnitResource x =
        new UnitResource("a/X")
            .provide(ManifestsUniverse.NS_UNIT, Map.of("unit", "a/X", "domain", "a"))
            .require(ManifestsUniverse.NS_UNIT, "(unit=a/Y)");
    UnitResource y =
        new UnitResource("a/Y")
            .provide(ManifestsUniverse.NS_UNIT, Map.of("unit", "a/Y", "domain", "a"))
            .require(ManifestsUniverse.NS_UNIT, "(unit=a/X)");

    UnitResolver resolver = new UnitResolver(List.of(x, y), resolver());
    Map<Resource, List<Wire>> wiring = resolver.resolve(x);

    Map<String, UnitResource> byId = Map.of("a/X", x, "a/Y", y);
    ManifestsVisitOrder visitOrder = new ManifestsVisitOrder(wiring, byId);

    IllegalStateException failure = assertThrows(IllegalStateException.class, visitOrder::order);
    assertTrue(
        failure.getMessage().contains("a/X") && failure.getMessage().contains("a/Y"),
        "cycle diagnosis names the offending units: " + failure.getMessage());
  }

  private static List<UnitResource> append(List<UnitResource> base, UnitResource extra) {
    List<UnitResource> all = new ArrayList<>(base);
    all.add(extra);
    return all;
  }
}
