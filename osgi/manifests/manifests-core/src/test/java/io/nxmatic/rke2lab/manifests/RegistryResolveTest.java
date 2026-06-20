package io.nxmatic.rke2lab.manifests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.apache.felix.resolver.Logger;
import org.apache.felix.resolver.ResolverImpl;
import org.junit.jupiter.api.Test;
import org.osgi.service.resolver.Resolver;

/**
 * The integration keystone: {@link ManifestsDomainRegistry} is now a build-only ASSEMBLED state
 * that never validates at construction, and {@link ManifestsDomainRegistry#resolve(Resolver)} is
 * the single coherence-rule coordinator that folds in the retired trio's guarantees — unknown refs,
 * cycles, and cross-domain violations all surface here, never silently.
 */
final class RegistryResolveTest {

  /** The resolver the test injects — a direct ResolverImpl; production binds the OSGi service. */
  private static Resolver resolver() {
    return new ResolverImpl(new Logger(Logger.LOG_ERROR));
  }

  private record StubUnit(String manifestUnitId, List<String> dependsOnManifestsUnitIds)
      implements ManifestsUnit {
    @Override
    public void apply(ManifestsUnitContext context) {}
  }

  @Test
  void constructionNeverThrowsOnMalformedButAcyclicGraph() {
    // domain "b" depends on a domain "ghost" that does not exist in the registry. The retired
    // validateDomainDependencies() rejected this at construction; the assembled state no longer
    // does.
    ManifestsDomain a =
        new ManifestsDomain("a", List.of(), List.of(new StubUnit("a/A", List.of())));
    ManifestsDomain b =
        new ManifestsDomain("b", List.of("ghost"), List.of(new StubUnit("b/B", List.of())));

    assertDoesNotThrow(() -> new ManifestsDomainRegistry(List.of(a, b)));
  }

  @Test
  void resolveReturnsCoherentRegistryWithValidVisitOrder() {
    // a/C <- a/B <- a/A (unit chain); domain b depends on domain a.
    ManifestsUnit aC = new StubUnit("a/C", List.of());
    ManifestsUnit aB = new StubUnit("a/B", List.of("a/C"));
    ManifestsUnit aA = new StubUnit("a/A", List.of("a/B"));
    ManifestsUnit bX = new StubUnit("b/X", List.of());

    ManifestsDomain a = new ManifestsDomain("a", List.of(), List.of(aC, aB, aA));
    ManifestsDomain b = new ManifestsDomain("b", List.of("a"), List.of(bX));

    ManifestsDomainRegistry registry = new ManifestsDomainRegistry(List.of(a, b));

    CoherentManifestsDomainRegistry coherent = registry.resolve(resolver());
    assertNotNull(coherent);

    List<ManifestsUnit> order = coherent.visitOrder();
    assertEquals(4, order.size(), "every unit present exactly once");

    List<String> ids = order.stream().map(ManifestsUnit::manifestUnitId).toList();
    assertTrue(ids.indexOf("a/C") < ids.indexOf("a/B"), "C precedes B");
    assertTrue(ids.indexOf("a/B") < ids.indexOf("a/A"), "B precedes A");
    assertTrue(ids.indexOf("a/A") < ids.indexOf("b/X"), "a's units precede b/X");

    assertEquals("a", coherent.requireDomainIdForManifestsUnit("a/A"));
    assertEquals("b", coherent.requireDomainIdForManifestsUnit("b/X"));
  }

  @Test
  void unitCycleConstructsButResolveThrows() {
    // a/X <-> a/Y unit-level cycle, both in domain "a". The retired validateManifestsUnitAcyclic()
    // rejected this at construction; now construction succeeds and the cycle is carried forward —
    // ManifestsVisitOrder.order() throws inside resolve().
    ManifestsUnit aX = new StubUnit("a/X", List.of("a/Y"));
    ManifestsUnit aY = new StubUnit("a/Y", List.of("a/X"));
    ManifestsDomain a = new ManifestsDomain("a", List.of(), List.of(aX, aY));

    ManifestsDomainRegistry registry =
        assertDoesNotThrow(() -> new ManifestsDomainRegistry(List.of(a)));

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> registry.resolve(resolver()));
    assertTrue(
        failure.getMessage().contains("a/X") || failure.getMessage().contains("a/Y"),
        "cycle diagnosis names an offending unit: " + failure.getMessage());
  }

  @Test
  void illegalCrossDomainDependencyConstructsButResolveThrows() {
    // a/A depends on b/B, but domain "a" does not dependsOn domain "b": an illegal cross-domain
    // dep.
    // The retired validation rejected it at construction; now CrossDomainRule fires inside
    // resolve().
    ManifestsUnit aA = new StubUnit("a/A", List.of("b/B"));
    ManifestsUnit bB = new StubUnit("b/B", List.of());
    ManifestsDomain a = new ManifestsDomain("a", List.of(), List.of(aA));
    ManifestsDomain b = new ManifestsDomain("b", List.of(), List.of(bB));

    ManifestsDomainRegistry registry =
        assertDoesNotThrow(() -> new ManifestsDomainRegistry(List.of(a, b)));

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> registry.resolve(resolver()));
    assertTrue(
        failure.getMessage().contains("a/A") && failure.getMessage().contains("b/B"),
        "cross-domain diagnosis names the offending units: " + failure.getMessage());
  }

  @Test
  void visitOrderSatisfiesProducerBeforeConsumerForEveryDependency() {
    // Synthesis-parity invariant: Cdk8sApiObjectResolver lets a consuming unit look up an ApiObject
    // produced by a unit it depends on, assuming the producer ran first. The retired ordering trio
    // is gone; resolve()/visitOrder() must still place every producer before its consumer.
    //
    // Unit chain x/C <- x/B <- x/A inside domain x, plus a cross-domain dep y/Y -> x/A. Domain y
    // dependsOn domain x, so the cross-domain unit dep is legal under CrossDomainRule.
    ManifestsUnit xC = new StubUnit("x/C", List.of());
    ManifestsUnit xB = new StubUnit("x/B", List.of("x/C"));
    ManifestsUnit xA = new StubUnit("x/A", List.of("x/B"));
    ManifestsUnit yY = new StubUnit("y/Y", List.of("x/A"));
    ManifestsUnit yZ = new StubUnit("y/Z", List.of());

    ManifestsDomain x = new ManifestsDomain("x", List.of(), List.of(xC, xB, xA));
    ManifestsDomain y = new ManifestsDomain("y", List.of("x"), List.of(yY, yZ));

    ManifestsDomainRegistry registry = new ManifestsDomainRegistry(List.of(x, y));

    List<ManifestsUnit> order = registry.resolve(resolver()).visitOrder();

    List<String> ids = order.stream().map(ManifestsUnit::manifestUnitId).toList();

    // Completeness: every registered unit appears exactly once — nothing silently pruned.
    assertEquals(5, order.size(), "every unit present exactly once");
    assertEquals(
        Set.of("x/A", "x/B", "x/C", "y/Y", "y/Z"),
        Set.copyOf(ids),
        "visitOrder contains exactly the registered units");
    assertEquals(ids.size(), Set.copyOf(ids).size(), "no unit appears more than once");

    // Producer-before-consumer, asserted generically over the whole order rather than hand-picked
    // pairs: for every unit, each of its declared dependencies must precede it.
    for (ManifestsUnit unit : order) {
      int consumerIndex = ids.indexOf(unit.manifestUnitId());
      for (String dependencyId : unit.dependsOnManifestsUnitIds()) {
        int producerIndex = ids.indexOf(dependencyId);
        assertTrue(
            producerIndex >= 0,
            "dependency " + dependencyId + " of " + unit.manifestUnitId() + " is present in order");
        assertTrue(
            producerIndex < consumerIndex,
            "producer "
                + dependencyId
                + " (index "
                + producerIndex
                + ") must precede consumer "
                + unit.manifestUnitId()
                + " (index "
                + consumerIndex
                + ")");
      }
    }
  }
}
