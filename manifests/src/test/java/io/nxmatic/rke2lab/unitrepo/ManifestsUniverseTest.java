package io.nxmatic.rke2lab.unitrepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistry;
import io.nxmatic.rke2lab.manifests.ManifestsUnit;
import io.nxmatic.rke2lab.manifests.ManifestsUnitContext;
import io.nxmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("osgi")
class ManifestsUniverseTest {

  private record StubUnit(String manifestUnitId, List<String> dependsOnManifestsUnitIds)
      implements ManifestsUnit {
    @Override
    public void apply(ManifestsUnitContext context) {}
  }

  private static ManifestsDomainRegistry fixtureRegistry() {
    ManifestsUnit aOne = new StubUnit("a/one", List.of());
    ManifestsUnit aTwo = new StubUnit("a/two", List.of("a/one"));
    ManifestsUnit bX = new StubUnit("b/x", List.of());

    ManifestsDomain a = new ManifestsDomain("a", List.of(), List.of(aOne, aTwo));
    ManifestsDomain b = new ManifestsDomain("b", List.of("a"), List.of(bX));

    return new ManifestsDomainRegistry(List.of(a, b));
  }

  @Test
  void emitsDomainAndUnitResourcesFromTheGivenRegistry() {
    ManifestsDomainRegistry registry = fixtureRegistry();

    ManifestsUniverse universe = new ManifestsUniverse(registry);

    assertEquals(
        registry.domains().size() + registry.manifestUnits().size(), universe.universe().size());

    Map<String, UnitResource> byId = universe.byId();
    assertNotNull(byId.get("a"), "known domain id resolves");
    assertNotNull(byId.get("a/two"), "known unit id resolves");

    UnitResource domain = byId.get("a");
    Map<String, Object> domainAttributes =
        domain.getCapabilities(ManifestsUniverse.NS_DOMAIN).getFirst().getAttributes();
    assertEquals("a", domainAttributes.get("domain"));
    assertEquals(ManifestsUniverse.MANIFESTS_MODULE, domainAttributes.get("module"));

    UnitResource unit = byId.get("a/two");
    Map<String, Object> unitAttributes =
        unit.getCapabilities(ManifestsUniverse.NS_UNIT).getFirst().getAttributes();
    assertEquals("a/two", unitAttributes.get("unit"));
    assertEquals("a", unitAttributes.get("domain"));

    assertTrue(byId.containsKey("b"));
    assertTrue(byId.containsKey("b/x"));
  }
}
