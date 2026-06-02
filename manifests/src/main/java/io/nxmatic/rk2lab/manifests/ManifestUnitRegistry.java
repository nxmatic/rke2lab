// @codebase
package io.nxmatic.rk2lab.manifests;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ManifestUnitRegistry {

  private final Map<String, ManifestUnit> manifestUnitsById;

  public ManifestUnitRegistry(final List<ManifestUnit> manifestUnits) {
    LinkedHashMap<String, ManifestUnit> byId = new LinkedHashMap<>();

    for (ManifestUnit manifestUnit : manifestUnits) {
      if (byId.put(manifestUnit.manifestUnitId(), manifestUnit) != null) {
        throw new IllegalStateException(
            "Duplicate manifest unit id: " + manifestUnit.manifestUnitId());
      }
    }

    this.manifestUnitsById = Map.copyOf(byId);
  }

  public ManifestUnit requireById(final String manifestUnitId) {
    ManifestUnit manifestUnit = manifestUnitsById.get(manifestUnitId);
    if (manifestUnit == null) {
      throw new IllegalStateException(
          "Manifest unit dependency references unknown manifest unit: " + manifestUnitId);
    }
    return manifestUnit;
  }
}
