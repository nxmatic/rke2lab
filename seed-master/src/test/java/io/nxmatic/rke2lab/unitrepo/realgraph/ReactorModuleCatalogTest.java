package io.nxmatic.rke2lab.unitrepo.realgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.osgi.resource.Capability;

class ReactorModuleCatalogTest {

  @Test
  void emitsAModuleUnitPerModuleWithRealEdges() {
    ReactorModuleCatalog catalog = new ReactorModuleCatalog();
    Map<String, UnitResource> byId = catalog.byId();

    // the 8 modules in seed-master's closure are present
    assertEquals(8, byId.size(), "8 module-units modeled");
    assertTrue(byId.containsKey("seed-master"));
    assertTrue(byId.containsKey("manifests"));
    assertTrue(byId.containsKey("cdk8s-systemd"));
    assertTrue(byId.containsKey("netplan"));

    // every module provides the unitrepo.module capability carrying its own id
    UnitResource seedMaster = byId.get("seed-master");
    List<Capability> moduleCaps = seedMaster.getCapabilities(ReactorModuleCatalog.NS_MODULE);
    assertEquals(1, moduleCaps.size(), "one unitrepo.module capability");
    assertEquals("seed-master", moduleCaps.get(0).getAttributes().get("module"));
  }
}
