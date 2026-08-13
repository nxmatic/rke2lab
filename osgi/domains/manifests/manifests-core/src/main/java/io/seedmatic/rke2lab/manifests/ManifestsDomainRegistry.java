// @codebase
package io.seedmatic.rke2lab.manifests;

import io.seedmatic.rke2lab.manifests.unitrepo.CrossDomainRule;
import io.seedmatic.rke2lab.manifests.unitrepo.ManifestsUniverse;
import io.seedmatic.rke2lab.manifests.unitrepo.ManifestsVisitOrder;
import io.seedmatic.rke2lab.unitrepo.core.UnitResolver;
import io.seedmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.service.resolver.ResolutionException;
import org.osgi.service.resolver.Resolver;

/**
 * The ASSEMBLED state: an indexed view of policy-filtered domains and units that enforces only
 * structural invariants (no duplicate domain ids, no unit in two domains, no empty domain). It
 * deliberately does NOT validate coherence (unknown refs, cycles, cross-domain) — that is the job
 * of {@link #resolve()}, the single coherence gate. Construction never throws on a coherence
 * violation; a malformed-but-structurally-sound graph is buildable and reports its problems only at
 * resolve.
 */
public final class ManifestsDomainRegistry {

  private final Map<String, ManifestsDomain> domainsById;
  private final List<ManifestsUnit> manifestUnits;
  private final Map<String, String> domainIdByManifestsUnitId;

  public ManifestsDomainRegistry(final List<ManifestsDomain> domains) {
    if (domains == null || domains.isEmpty()) {
      throw new IllegalArgumentException("At least one domain must be configured");
    }

    LinkedHashMap<String, ManifestsDomain> byId = new LinkedHashMap<>();
    for (ManifestsDomain domain : domains) {
      if (byId.put(domain.domainId(), domain) != null) {
        throw new IllegalStateException("Duplicate domain id: " + domain.domainId());
      }
    }

    this.domainsById = Map.copyOf(byId);
    this.manifestUnits =
        byId.values().stream()
            .flatMap(domain -> domain.units().stream())
            .map(layer -> (ManifestsUnit) layer)
            .toList();

    HashMap<String, String> byManifestsUnitId = new HashMap<>();
    for (ManifestsDomain domain : byId.values()) {
      for (ManifestsUnit manifestUnit : domain.units()) {
        String previous = byManifestsUnitId.put(manifestUnit.manifestUnitId(), domain.domainId());
        if (previous != null) {
          throw new IllegalStateException(
              "Manifest unit is assigned to multiple domains: " + manifestUnit.manifestUnitId());
        }
      }
    }
    this.domainIdByManifestsUnitId = Map.copyOf(byManifestsUnitId);
  }

  public List<ManifestsDomain> domains() {
    return List.copyOf(domainsById.values());
  }

  public List<ManifestsUnit> manifestUnits() {
    return manifestUnits;
  }

  public String requireDomainIdForManifestsUnit(final String manifestUnitId) {
    final String domainId = domainIdByManifestsUnitId.get(manifestUnitId);
    if (domainId == null) {
      throw new IllegalStateException(
          "Unable to resolve domain for manifest unit: " + manifestUnitId);
    }
    return domainId;
  }

  /**
   * The single coherence gate. It folds in the three guarantees of the retired hand-rolled trio:
   * cross-domain (CrossDomainRule), unknown/unsatisfiable refs (UnitResolver, as
   * ResolutionException wrapped here), and cycles (ManifestsVisitOrder.order(), the carry-forward
   * of the old acyclic check). CrossDomainRule runs first: it yields the most specific,
   * domain-aware diagnosis, so it wins when several problems coexist; the resolver and topo-sort
   * then catch the structural failures it does not model. The OSGi {@link ResolutionException} is
   * wrapped into an {@link IllegalStateException} so callers never depend on that OSGi type.
   *
   * <p>The {@code resolver} is the injected {@code org.osgi.service.resolver.Resolver} — in live
   * the felix.resolver service bound by SCR on {@code DefaultManifestSynthesisService}; tests pass
   * their own. It is the one OSGi type this gate's signature exposes, by necessity.
   */
  public CoherentManifestsDomainRegistry resolve(Resolver resolver) {
    CrossDomainRule.check(this);

    final ManifestsUniverse universe = new ManifestsUniverse(this);

    final UnitResource root =
        new UnitResource("synthesis-root")
            .requireAll(
                ManifestsUniverse.NS_DOMAIN,
                "("
                    + ManifestsUniverse.ATTR_MODULE
                    + "="
                    + ManifestsUniverse.MANIFESTS_MODULE
                    + ")");

    final List<UnitResource> closure = new ArrayList<>(universe.universe());
    closure.add(root);

    final Map<Resource, List<Wire>> wiring;
    try {
      wiring = new UnitResolver(closure, resolver).resolve(root);
    } catch (ResolutionException cause) {
      throw new IllegalStateException(
          "manifest closure is incoherent: " + cause.getMessage(), cause);
    }

    final List<String> orderedUnitIds = new ManifestsVisitOrder(wiring, universe.byId()).order();

    return new CoherentManifestsDomainRegistry(this, orderedUnitIds);
  }
}
