// @codebase
package io.nxmatic.rk2lab.manifests;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ManifestsUnitRegistry {

  private final Map<String, ManifestsUnit> manifestUnitsById;

  public ManifestsUnitRegistry(final List<ManifestsUnit> manifestUnits) {
    LinkedHashMap<String, ManifestsUnit> byId = new LinkedHashMap<>();

    for (ManifestsUnit manifestUnit : manifestUnits) {
      if (byId.put(manifestUnit.manifestUnitId(), manifestUnit) != null) {
        throw new IllegalStateException(
            "Duplicate manifest unit id: " + manifestUnit.manifestUnitId());
      }
    }

    this.manifestUnitsById = Map.copyOf(byId);
  }

  public ManifestsUnit requireById(final String manifestUnitId) {
    ManifestsUnit manifestUnit = manifestUnitsById.get(manifestUnitId);
    if (manifestUnit == null) {
      throw new IllegalStateException(
          "Manifest unit dependency references unknown manifest unit: " + manifestUnitId);
    }
    return manifestUnit;
  }
}
