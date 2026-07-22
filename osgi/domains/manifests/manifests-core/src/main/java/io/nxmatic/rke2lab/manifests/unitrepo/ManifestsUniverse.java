package io.nxmatic.rke2lab.manifests.unitrepo;

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

  public static final String ATTR_DOMAIN = "domain";
  public static final String ATTR_UNIT = "unit";
  public static final String ATTR_MODULE = "module";

  private final Map<String, UnitResource> byId = new LinkedHashMap<>();

  public ManifestsUniverse(ManifestsDomainRegistry registry) {
    for (ManifestsDomain domain : registry.domains()) {
      String id = domain.domainId();
      UnitResource domainResource =
          new UnitResource(id)
              .provide(NS_DOMAIN, Map.of(ATTR_DOMAIN, id, ATTR_MODULE, MANIFESTS_MODULE));
      for (String dep : domain.dependsOnDomainIds()) {
        domainResource.require(NS_DOMAIN, "(" + ATTR_DOMAIN + "=" + dep + ")");
      }
      domainResource.requireAll(NS_UNIT, "(" + ATTR_DOMAIN + "=" + id + ")");
      byId.put(id, domainResource);
    }

    for (ManifestsUnit unit : registry.manifestUnits()) {
      String id = unit.manifestUnitId();
      String domainId = registry.requireDomainIdForManifestsUnit(id);
      UnitResource unitResource =
          new UnitResource(id).provide(NS_UNIT, Map.of(ATTR_UNIT, id, ATTR_DOMAIN, domainId));
      for (String dep : unit.dependsOnManifestsUnitIds()) {
        unitResource.require(NS_UNIT, "(" + ATTR_UNIT + "=" + dep + ")");
      }
      if (byId.put(id, unitResource) != null) {
        throw new IllegalStateException(
            "unit id collides with an existing domain or unit id: " + id);
      }
    }
  }

  public List<UnitResource> universe() {
    return List.copyOf(byId.values());
  }

  public Map<String, UnitResource> byId() {
    return Map.copyOf(byId);
  }
}
