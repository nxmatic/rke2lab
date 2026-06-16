package io.nxmatic.rke2lab.unitrepo;

import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistry;
import io.nxmatic.rke2lab.manifests.ManifestsUnit;
import io.nxmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fine (domain + unit) layer of the manifests universe: re-expresses an already policy-filtered
 * {@link ManifestsDomainRegistry}'s domains and units as {@link UnitResource}s in the resolver's
 * vocabulary. The registry is given; this adapter never assembles or filters one.
 */
public final class ManifestsUniverse {

  public static final String NS_DOMAIN = "unitrepo.manifest.domain";
  public static final String NS_UNIT = "unitrepo.unit";
  public static final String MANIFESTS_MODULE = "manifests";

  private final Map<String, UnitResource> byId = new LinkedHashMap<>();

  public ManifestsUniverse(ManifestsDomainRegistry registry) {
    for (ManifestsDomain domain : registry.domains()) {
      String id = domain.domainId();
      UnitResource domainResource =
          new UnitResource(id).provide(NS_DOMAIN, Map.of("domain", id, "module", MANIFESTS_MODULE));
      for (String dep : domain.dependsOnDomainIds()) {
        domainResource.require(NS_DOMAIN, "(domain=" + dep + ")");
      }
      domainResource.requireAll(NS_UNIT, "(domain=" + id + ")");
      byId.put(id, domainResource);
    }

    for (ManifestsUnit unit : registry.manifestUnits()) {
      String id = unit.manifestUnitId();
      String domainId = registry.requireDomainIdForManifestsUnit(id);
      UnitResource unitResource =
          new UnitResource(id).provide(NS_UNIT, Map.of("unit", id, "domain", domainId));
      for (String dep : unit.dependsOnManifestsUnitIds()) {
        unitResource.require(NS_UNIT, "(unit=" + dep + ")");
      }
      byId.put(id, unitResource);
    }
  }

  public List<UnitResource> universe() {
    return List.copyOf(byId.values());
  }

  public Map<String, UnitResource> byId() {
    return Map.copyOf(byId);
  }
}
