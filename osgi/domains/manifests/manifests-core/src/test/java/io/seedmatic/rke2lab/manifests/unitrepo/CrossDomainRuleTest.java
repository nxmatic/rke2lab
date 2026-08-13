package io.seedmatic.rke2lab.manifests.unitrepo;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.manifests.ManifestsDomain;
import io.seedmatic.rke2lab.manifests.ManifestsDomainRegistry;
import io.seedmatic.rke2lab.manifests.ManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The cross-domain rule: a unit in domain A may depend on a unit in domain B only if A {@code
 * dependsOn} B transitively (or A == B). The retired hand-rolled walker enforced this in {@code
 * ManifestsDomainRegistry.validateManifestsUnitDependencies}. The resolver cannot re-establish it
 * on its own — a domain-scoped require filter does not fail resolution but silently prunes the
 * offending unit via the {@code cardinality:=multiple} containment requirement. So the rule lives
 * in {@link CrossDomainRule}, invoked by {@code resolve()} (the registry no longer validates at
 * construction). The illegal case is exercised in graph form for a focused unit test; the
 * registry-level path is covered by {@code RegistryResolveTest}. Legal controls use a real
 * registry.
 */
final class CrossDomainRuleTest {

  private record StubUnit(String manifestUnitId, List<String> dependsOnManifestsUnitIds)
      implements ManifestsUnit {
    @Override
    public void apply(ManifestsUnitContext context) {}
  }

  /** a/u depends on b/v with NO A→B domain edge: illegal, must be rejected. */
  @Test
  void illegalCrossDomainDependencyIsRejected() {
    Map<String, List<String>> dependsOnDomainIds = Map.of("a", List.of(), "b", List.of());
    Map<String, List<String>> unitDependencies = Map.of("a/u", List.of("b/v"), "b/v", List.of());
    Map<String, String> domainIdByUnitId = Map.of("a/u", "a", "b/v", "b");

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> CrossDomainRule.check(dependsOnDomainIds, unitDependencies, domainIdByUnitId));
    assertTrue(
        failure.getMessage().contains("a/u") && failure.getMessage().contains("b/v"),
        "diagnosis names the offending dependency: " + failure.getMessage());
  }

  /** A→B domain edge present: a cross-domain unit dependency is legal. */
  @Test
  void legalCrossDomainDependencyIsAccepted() {
    ManifestsUnit bV = new StubUnit("b/v", List.of());
    ManifestsUnit aU = new StubUnit("a/u", List.of("b/v"));

    ManifestsDomain a = new ManifestsDomain("a", List.of("b"), List.of(aU));
    ManifestsDomain b = new ManifestsDomain("b", List.of(), List.of(bV));

    ManifestsDomainRegistry registry = new ManifestsDomainRegistry(List.of(a, b));

    assertDoesNotThrow(() -> CrossDomainRule.check(registry));
  }

  /** Same-domain dependency is legal (A reaches itself). */
  @Test
  void sameDomainDependencyIsAccepted() {
    ManifestsUnit aOne = new StubUnit("a/one", List.of());
    ManifestsUnit aTwo = new StubUnit("a/two", List.of("a/one"));

    ManifestsDomain a = new ManifestsDomain("a", List.of(), List.of(aOne, aTwo));

    ManifestsDomainRegistry registry = new ManifestsDomainRegistry(List.of(a));

    assertDoesNotThrow(() -> CrossDomainRule.check(registry));
  }

  /** Transitive A→B→C edge: a unit in A may depend on a unit in C. */
  @Test
  void transitiveCrossDomainDependencyIsAccepted() {
    ManifestsUnit cW = new StubUnit("c/w", List.of());
    ManifestsUnit bV = new StubUnit("b/v", List.of());
    ManifestsUnit aU = new StubUnit("a/u", List.of("c/w"));

    ManifestsDomain a = new ManifestsDomain("a", List.of("b"), List.of(aU));
    ManifestsDomain b = new ManifestsDomain("b", List.of("c"), List.of(bV));
    ManifestsDomain c = new ManifestsDomain("c", List.of(), List.of(cW));

    ManifestsDomainRegistry registry = new ManifestsDomainRegistry(List.of(a, b, c));

    assertDoesNotThrow(() -> CrossDomainRule.check(registry));
  }
}
