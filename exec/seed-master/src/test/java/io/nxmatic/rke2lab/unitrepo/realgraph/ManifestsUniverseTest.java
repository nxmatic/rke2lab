package io.nxmatic.rke2lab.unitrepo.realgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * @deprecated Part of the R4-superseded {@code realgraph} fixture — see {@code package-info}.
 */
@Deprecated(forRemoval = true)
@Tag("osgi")
class ManifestsUniverseTest {

  @Test
  void emitsDomainAndUnitResourcesFromTheRealRegistry() {
    ManifestsUniverse universe = new ManifestsUniverse();

    Map<String, UnitResource> domains = universe.domainsById();
    Map<String, UnitResource> units = universe.unitsById();

    // the 10 real domains and the real flux gitops chain are present
    assertEquals(10, domains.size(), "10 manifest domains");
    assertTrue(domains.containsKey("gitops"));
    assertTrue(domains.containsKey("platform"));

    assertTrue(units.containsKey("gitops/flux-root"));
    assertTrue(units.containsKey("gitops/flux-instance"));
    assertTrue(units.containsKey("gitops/flux-operator"));
  }
}
