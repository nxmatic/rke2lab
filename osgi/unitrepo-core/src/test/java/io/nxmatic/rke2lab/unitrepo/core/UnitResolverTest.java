package io.nxmatic.rke2lab.unitrepo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.service.resolver.ResolutionException;

/**
 * Proves the resolution track on the real stack: the Apache Felix Resolver, running STANDALONE (no
 * OSGi framework), resolves a unit's Provide/Require closure. This is the rke2lab hand-rolled
 * dependency walker ({@code ManifestsUnit.dependsOn…} + the manifests traversal loops) re-expressed
 * as capabilities and handed to the resolver — the foundational "it runs for real" of the OSGi
 * migration's resolution half.
 */
@Tag("osgi")
final class UnitResolverTest {

  private static final String NS_DOMAIN = "unitrepo.manifest.domain";
  private static final String NS_EXTENDER = "osgi.extender";

  @Test
  void resolvesClosureAcrossDomainAndHandlerEdges() throws ResolutionException {
    // a handler bundle providing the extender capability every unit requires (constitutive edge)
    UnitResource handler =
        new UnitResource("visit-handler")
            .provide(NS_EXTENDER, Map.of("osgi.extender", "unitrepo.type.visit"));

    // a unit providing the "networking" manifest domain
    UnitResource networking =
        new UnitResource("networking-unit")
            .provide(NS_DOMAIN, Map.of("unitrepo.manifest.domain", "networking"))
            .require(NS_EXTENDER, "(osgi.extender=unitrepo.type.visit)");

    // the root unit: requires the networking domain AND its handler (the dependsOn… graph)
    UnitResource root =
        new UnitResource("gitops-unit")
            .provide(NS_DOMAIN, Map.of("unitrepo.manifest.domain", "gitops"))
            .require(NS_DOMAIN, "(unitrepo.manifest.domain=networking)")
            .require(NS_EXTENDER, "(osgi.extender=unitrepo.type.visit)");

    UnitResolver resolver = new UnitResolver(List.of(handler, networking, root));
    Map<Resource, List<Wire>> wiring = resolver.resolve(root);

    // the resolved closure must contain all three: root + networking (its domain dep) + handler
    assertEquals(3, wiring.size(), "closure = root + networking + handler");
    assertTrue(wiring.containsKey(root), "root resolved");
    assertTrue(wiring.containsKey(networking), "networking pulled in by the domain requirement");
    assertTrue(wiring.containsKey(handler), "handler pulled in by the constitutive extender edge");

    // root's wires must point at the two providers it required (the walker's edges, now resolved)
    List<Wire> rootWires = wiring.get(root);
    assertEquals(2, rootWires.size(), "root has two satisfied requirements");
  }

  @Test
  void unsatisfiableRequirementFailsResolution() {
    // a unit requiring a domain nobody provides — must NOT silently return empty (errors-as-values)
    UnitResource orphan =
        new UnitResource("orphan-unit").require(NS_DOMAIN, "(unitrepo.manifest.domain=absent)");

    UnitResolver resolver = new UnitResolver(List.of(orphan));
    assertThrows(
        ResolutionException.class,
        () -> resolver.resolve(orphan),
        "an unmet requirement is a diagnosable failure, not a silent empty closure");
  }

  @Test
  void requireAllWiresEveryMatchingProvider() throws ResolutionException {
    UnitResource memberA = new UnitResource("member-a").provide(NS_DOMAIN, Map.of("group", "g1"));
    UnitResource memberB = new UnitResource("member-b").provide(NS_DOMAIN, Map.of("group", "g1"));
    UnitResource memberC = new UnitResource("member-c").provide(NS_DOMAIN, Map.of("group", "g1"));

    UnitResource parent = new UnitResource("parent").requireAll(NS_DOMAIN, "(group=g1)");

    UnitResolver resolver = new UnitResolver(List.of(memberA, memberB, memberC, parent));
    Map<Resource, List<Wire>> wiring = resolver.resolve(parent);

    // parent must wire to ALL three members (cardinality:=multiple), not just one
    assertEquals(3, wiring.get(parent).size(), "requireAll fans out to every match");
    assertEquals(4, wiring.size(), "closure = parent + three members");
  }
}
