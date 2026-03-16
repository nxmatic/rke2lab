// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ManifestUnitRegistry {

    private final Map<String, ManifestUnit> manifestUnitsById;
    private final Map<String, ManifestUnit> manifestUnitsByLegacyPrefix;

    public ManifestUnitRegistry(final List<ManifestUnit> manifestUnits) {
        LinkedHashMap<String, ManifestUnit> byId = new LinkedHashMap<>();
        LinkedHashMap<String, ManifestUnit> byLegacyPrefix = new LinkedHashMap<>();

        for (ManifestUnit manifestUnit : manifestUnits) {
            if (byId.put(manifestUnit.manifestUnitId(), manifestUnit) != null) {
                throw new IllegalStateException("Duplicate manifest unit id: " + manifestUnit.manifestUnitId());
            }
            for (String prefix : manifestUnit.legacyPathPrefixes()) {
                if (byLegacyPrefix.put(prefix, manifestUnit) != null) {
                    throw new IllegalStateException("Duplicate legacy path prefix mapping: " + prefix);
                }
            }
        }

        this.manifestUnitsById = Map.copyOf(byId);
        this.manifestUnitsByLegacyPrefix = Map.copyOf(byLegacyPrefix);
    }

    public Optional<ManifestUnit> findByLegacyPath(final String relativePath) {
        for (Map.Entry<String, ManifestUnit> entry : manifestUnitsByLegacyPrefix.entrySet()) {
            if (relativePath.startsWith(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    public ManifestUnit requireById(final String manifestUnitId) {
        ManifestUnit manifestUnit = manifestUnitsById.get(manifestUnitId);
        if (manifestUnit == null) {
            throw new IllegalStateException("Manifest unit dependency references unknown manifest unit: " + manifestUnitId);
        }
        return manifestUnit;
    }
}
