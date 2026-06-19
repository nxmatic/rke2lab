package io.nxmatic.rke2lab.unitrepo.realgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.nxmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * @deprecated Part of the R4-superseded {@code realgraph} fixture — see {@code package-info}.
 */
@Deprecated(forRemoval = true)
@Tag("osgi")
class UniverseBuilderTest {

  @Test
  void mergesBothLayersIntoOneUniverse() {
    UniverseBuilder builder = new UniverseBuilder();
    List<UnitResource> universe = builder.universe();
    Map<String, UnitResource> byId = builder.byId();

    // 8 modules + 10 domains + 28 units = 46 unit-resources, one id-space
    assertEquals(46, universe.size(), "8 modules + 10 domains + 28 units");
    assertNotNull(byId.get("seed-master"), "module landmark");
    assertNotNull(byId.get("gitops"), "domain landmark");
    assertNotNull(byId.get("gitops/flux-root"), "unit landmark");
  }
}
