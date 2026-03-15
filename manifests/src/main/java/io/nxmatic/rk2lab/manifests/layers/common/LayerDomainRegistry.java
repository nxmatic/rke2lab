// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import org.cdk8s.Chart;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LayerDomainRegistry {

    private final Map<String, LayerDomain> domainsById;
    private final List<ModeledLayer> modeledLayers;
    private final Map<String, String> domainIdByLayerId;

    public LayerDomainRegistry(final List<LayerDomain> domains) {
        if (domains == null || domains.isEmpty()) {
            throw new IllegalArgumentException("At least one domain must be configured");
        }

        LinkedHashMap<String, LayerDomain> byId = new LinkedHashMap<>();
        for (LayerDomain domain : domains) {
            if (byId.put(domain.domainId(), domain) != null) {
                throw new IllegalStateException("Duplicate domain id: " + domain.domainId());
            }
        }

        this.domainsById = Map.copyOf(byId);
        this.modeledLayers = byId.values().stream()
                .flatMap(domain -> domain.layers().stream())
                .toList();

        HashMap<String, String> byLayerId = new HashMap<>();
        for (LayerDomain domain : byId.values()) {
            for (ModeledLayer layer : domain.layers()) {
                String previous = byLayerId.put(layer.layerId(), domain.domainId());
                if (previous != null) {
                    throw new IllegalStateException("Layer is assigned to multiple domains: " + layer.layerId());
                }
            }
        }
        this.domainIdByLayerId = Map.copyOf(byLayerId);

        validateDomainDependencies();
    }

    public List<LayerDomain> domains() {
        return List.copyOf(domainsById.values());
    }

    public List<ModeledLayer> modeledLayers() {
        return modeledLayers;
    }

    public void applyLayerWithDomainDependencies(
            final String layerId,
            final LayerDependencyApplier layerDependencyApplier,
            final Chart chart
    ) {
        String domainId = domainIdByLayerId.get(layerId);
        if (domainId == null) {
            throw new IllegalStateException("Unable to resolve domain for modeled layer: " + layerId);
        }

        applyDomainWithDependencies(
                domainId,
                layerDependencyApplier,
                chart,
                new HashSet<>(),
                new HashSet<>()
        );

        layerDependencyApplier.applyLayerWithDependencies(layerId, chart);
    }

    private void validateDomainDependencies() {
        for (LayerDomain domain : domainsById.values()) {
            for (String dependencyDomainId : domain.dependsOnDomainIds()) {
                if (!domainsById.containsKey(dependencyDomainId)) {
                    throw new IllegalStateException(
                            "Domain dependency references unknown domain: "
                                    + domain.domainId() + " -> " + dependencyDomainId
                    );
                }
            }
        }
    }

    private void applyDomainWithDependencies(
            final String domainId,
            final LayerDependencyApplier layerDependencyApplier,
            final Chart chart,
            final Set<String> visitingDomainIds,
            final Set<String> appliedDomainIds
    ) {
        if (appliedDomainIds.contains(domainId)) {
            return;
        }

        if (!visitingDomainIds.add(domainId)) {
            throw new IllegalStateException("Cyclic domain dependency detected at: " + domainId);
        }

        LayerDomain domain = domainsById.get(domainId);
        if (domain == null) {
            throw new IllegalStateException("Unknown domain dependency: " + domainId);
        }

        for (String dependencyDomainId : domain.dependsOnDomainIds()) {
            applyDomainWithDependencies(
                    dependencyDomainId,
                    layerDependencyApplier,
                    chart,
                    visitingDomainIds,
                    appliedDomainIds
            );
        }

        for (ModeledLayer layer : domain.layers()) {
            layerDependencyApplier.applyLayerWithDependencies(layer.layerId(), chart);
        }

        visitingDomainIds.remove(domainId);
        appliedDomainIds.add(domainId);
    }
}
