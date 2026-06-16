package io.nxmatic.rke2lab.manifests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The integration keystone: {@link ManifestsDomainRegistry} is now a build-only ASSEMBLED state
 * that never validates at construction, and {@link ManifestsDomainRegistry#resolve()} is the single
 * coherence-rule coordinator that folds in the retired trio's guarantees — unknown refs, cycles,
 * and cross-domain violations all surface here, never silently.
 */
final class RegistryResolveTest {

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

    CoherentManifestsDomainRegistry coherent = registry.resolve();
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

    IllegalStateException failure = assertThrows(IllegalStateException.class, registry::resolve);
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

    IllegalStateException failure = assertThrows(IllegalStateException.class, registry::resolve);
    assertTrue(
        failure.getMessage().contains("a/A") && failure.getMessage().contains("b/B"),
        "cross-domain diagnosis names the offending units: " + failure.getMessage());
  }
}
