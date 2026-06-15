package io.nxmatic.rke2lab.unitrepo.realgraph;

import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.ManifestDomainPolicy;
import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistry;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistryBuilder;
import io.nxmatic.rke2lab.manifests.ManifestsUnit;
import io.nxmatic.rke2lab.manifests.domain.CicdDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.ClusterApiDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.ClusterDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.GitopsDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.HighAvailabilityDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.MeshDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.NetworkingDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.PlatformDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.RuntimeDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.StorageDomainRegistrar;
import io.nxmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fine (domain + unit) layer of the real-graph universe: reads the <em>real</em> assembled
 * {@link ManifestsDomainRegistry} (all-enabled policy + the 10 public registrars) and re-expresses
 * its domains and units as {@link UnitResource}s in the resolver's vocabulary. Latent: it reads the
 * registry, never modifies a unit or domain class.
 */
final class ManifestsUniverse {

  static final String NS_DOMAIN = "unitrepo.manifest.domain";
  static final String NS_UNIT = "unitrepo.unit";
  static final String MANIFESTS_MODULE = "manifests";

  private final Map<String, UnitResource> domainsById = new LinkedHashMap<>();
  private final Map<String, UnitResource> unitsById = new LinkedHashMap<>();

  ManifestsUniverse() {
    ManifestsDomainRegistry registry = buildRealRegistry();

    for (ManifestsDomain domain : registry.domains()) {
      String id = domain.domainId();
      UnitResource domainUnit =
          new UnitResource(id).provide(NS_DOMAIN, Map.of("domain", id, "module", MANIFESTS_MODULE));
      for (String dep : domain.dependsOnDomainIds()) {
        domainUnit.require(NS_DOMAIN, "(domain=" + dep + ")");
      }
      domainUnit.requireAll(NS_UNIT, "(domain=" + id + ")");
      domainsById.put(id, domainUnit);
    }

    for (ManifestsUnit unit : registry.manifestUnits()) {
      String id = unit.manifestUnitId();
      String domainId = registry.requireDomainIdForManifestsUnit(id);
      UnitResource unitResource =
          new UnitResource(id).provide(NS_UNIT, Map.of("unit", id, "domain", domainId));
      for (String dep : unit.dependsOnManifestsUnitIds()) {
        unitResource.require(NS_UNIT, "(unit=" + dep + ")");
      }
      unitsById.put(id, unitResource);
    }
  }

  private static ManifestsDomainRegistry buildRealRegistry() {
    ManifestDomainCatalog catalog =
        ManifestDomainCatalog.builder()
            .addDefaultDomains()
            .addDefaultStageALinkableDomains()
            .build();
    ManifestDomainPolicy allEnabled =
        ManifestDomainPolicy.builder().enableOnly(catalog.all()).build();
    // Mirrors the package-private DefaultManifestSynthesisService.buildDomainRegistry, which is
    // unreachable from this package: the proof must read the real assembled registry. The
    // domains().size() == 10 check in ManifestsUniverseTest trips if this list diverges from it.
    return new ManifestsDomainRegistryBuilder()
        .register(new ClusterDomainRegistrar(), allEnabled)
        .register(new StorageDomainRegistrar(), allEnabled)
        .register(new GitopsDomainRegistrar(), allEnabled)
        .register(new RuntimeDomainRegistrar(), allEnabled)
        .register(new NetworkingDomainRegistrar(), allEnabled)
        .register(new MeshDomainRegistrar(), allEnabled)
        .register(new HighAvailabilityDomainRegistrar(), allEnabled)
        .register(new CicdDomainRegistrar(), allEnabled)
        .register(new ClusterApiDomainRegistrar(), allEnabled)
        .register(new PlatformDomainRegistrar(), allEnabled)
        .build();
  }

  Map<String, UnitResource> domainsById() {
    return Map.copyOf(domainsById);
  }

  Map<String, UnitResource> unitsById() {
    return Map.copyOf(unitsById);
  }
}
