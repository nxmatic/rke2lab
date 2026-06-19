package io.nxmatic.rke2lab.unitrepo.realgraph;

import io.nxmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges the coarse ({@link ReactorModuleCatalog}) and fine ({@link ManifestsUniverse}) layers into
 * one {@link UnitResource} universe, attaching the single cross-layer containment edge: the {@code
 * manifests} module gathers all manifest domains via {@code requireAll(module=manifests)}. The
 * combined {@code byId} map (assembled from the layer builders' own id-keyed maps, so no accessor
 * is needed on {@link UnitResource}) lets the resolution test map wiring results back to ids.
 *
 * @deprecated Part of the R4-superseded {@code realgraph} fixture — see {@code package-info}.
 */
@Deprecated(forRemoval = true)
final class UniverseBuilder {

  private final List<UnitResource> universe = new ArrayList<>();
  private final Map<String, UnitResource> byId = new LinkedHashMap<>();

  UniverseBuilder() {
    ReactorModuleCatalog modules = new ReactorModuleCatalog();
    ManifestsUniverse manifests = new ManifestsUniverse();

    // moduleById holds shared UnitResource instances; mutating the manifests unit here is the
    // cross-layer containment edge that addAll(moduleById) below carries into the universe.
    Map<String, UnitResource> moduleById = modules.byId();
    moduleById
        .get(ManifestsUniverse.MANIFESTS_MODULE)
        .requireAll(
            ManifestsUniverse.NS_DOMAIN, "(module=" + ManifestsUniverse.MANIFESTS_MODULE + ")");

    // reuse each layer's own id->unit map — UnitResource needs no id() accessor
    addAll(moduleById);
    addAll(manifests.domainsById());
    addAll(manifests.unitsById());
  }

  private void addAll(Map<String, UnitResource> layer) {
    for (Map.Entry<String, UnitResource> entry : layer.entrySet()) {
      universe.add(entry.getValue());
      byId.put(entry.getKey(), entry.getValue());
    }
  }

  List<UnitResource> universe() {
    return List.copyOf(universe);
  }

  Map<String, UnitResource> byId() {
    return Map.copyOf(byId);
  }
}
