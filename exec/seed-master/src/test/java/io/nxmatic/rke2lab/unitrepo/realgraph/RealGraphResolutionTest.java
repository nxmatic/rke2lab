package io.nxmatic.rke2lab.unitrepo.realgraph;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.unitrepo.core.UnitResolver;
import io.nxmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.service.resolver.ResolutionException;

/**
 * The proof: the standalone Felix resolver computes rke2lab's real cross-layer closure (modules +
 * manifest domains + units) in one resolve, replacing the retired hand-rolled dependency-applier
 * walker over real data.
 *
 * @deprecated Superseded by the R4 Felix boot seam — see {@code package-info}. Deleted with the
 *     rest of the {@code realgraph} fixture once Felix resolves actually-installed bundles.
 */
@Deprecated(forRemoval = true)
@Tag("osgi")
class RealGraphResolutionTest {

  @Test
  void resolvesRealCrossLayerClosureFromSeedMaster() throws ResolutionException {
    UniverseBuilder builder = new UniverseBuilder();
    Map<String, UnitResource> byId = builder.byId();

    UnitResolver resolver = new UnitResolver(builder.universe());
    Map<Resource, List<Wire>> wiring = resolver.resolve(byId.get("seed-master"));

    Set<Resource> closure = wiring.keySet();

    // module layer reached
    assertContains(closure, byId, "seed-master");
    assertContains(closure, byId, "manifests");
    assertContains(closure, byId, "netplan");
    assertContains(closure, byId, "cdk8s-systemd");

    // domain layer reached via the manifests requireAll containment edge
    assertContains(closure, byId, "gitops");
    assertContains(closure, byId, "platform");

    // unit layer reached via each domain's requireAll containment edge, down the real flux chain
    assertContains(closure, byId, "gitops/flux-root");
    assertContains(closure, byId, "gitops/flux-instance");
    assertContains(closure, byId, "gitops/flux-operator");

    // ANTI-CHEAT: the gitops domain's requireAll must have fanned out to >1 unit
    // (proves cardinality:=multiple actually wired every member, not just one)
    UnitResource gitops = byId.get("gitops");
    long gitopsUnitWires =
        wiring.get(gitops).stream()
            .filter(w -> w.getCapability().getNamespace().equals(ManifestsUniverse.NS_UNIT))
            .count();
    assertTrue(
        gitopsUnitWires > 1, "gitops requireAll must fan out to >1 unit, got " + gitopsUnitWires);
  }

  @Test
  void unsatisfiableCrossLayerRequirementThrows() {
    UniverseBuilder builder = new UniverseBuilder();

    // a rogue unit that requires a manifest domain nobody provides
    UnitResource rogue =
        new UnitResource("rogue-module")
            .require(ManifestsUniverse.NS_DOMAIN, "(domain=does-not-exist)");

    List<UnitResource> universe = new ArrayList<>(builder.universe());
    universe.add(rogue);

    UnitResolver resolver = new UnitResolver(universe);

    assertThrows(
        ResolutionException.class,
        () -> resolver.resolve(rogue),
        "an unmet cross-layer requirement is a diagnosable failure, not a silent empty closure");
  }

  private static void assertContains(
      Set<Resource> closure, Map<String, UnitResource> byId, String id) {
    UnitResource unit = byId.get(id);
    assertNotNull(unit, "no unit-resource with id " + id + " in the universe");
    assertTrue(closure.contains(unit), "closure must contain " + id);
  }
}
