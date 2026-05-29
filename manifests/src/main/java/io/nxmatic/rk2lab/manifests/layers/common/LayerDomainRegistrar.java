// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainPolicy;

/**
 * Registers a manifest domain with its manifest units. Implementations can conditionally include
 * units based on {@link ManifestDomainPolicy}.
 *
 * <h2>Component Diagram: Policy-Aware Registration</h2>
 *
 * <pre>{@code
 * graph LR
 *     LDRB[LayerDomainRegistryBuilder]
 *     LDR[LayerDomainRegistrar]
 *     MDP[ManifestDomainPolicy]
 *     LD[LayerDomain]
 *     MU[ManifestUnit List]
 *
 *     LDRB -->|register registrar, policy| LDR
 *     LDR -->|queries| MDP
 *     MDP -->|isEnabled flag| LDR
 *     LDR -->|creates| LD
 *     LD -->|contains| MU
 *
 *     style MDP fill:#e1f5ff,stroke:#01579b
 *     style LDR fill:#f3e5f5,stroke:#4a148c
 *     style MU fill:#e8f5e9,stroke:#1b5e20
 * }</pre>
 *
 * <h3>Usage Patterns</h3>
 *
 * <p><b>Backwards compatible (no policy awareness):</b>
 *
 * <pre>{@code
 * public class SimpleDomainRegistrar implements LayerDomainRegistrar {
 *   @Override
 *   public LayerDomain domain() {
 *     return new LayerDomain("simple", List.of(new SomeManifestUnit()));
 *   }
 * }
 * }</pre>
 *
 * <p><b>Policy-aware (conditional units):</b>
 *
 * <pre>{@code
 * public class GitopsDomainRegistrar implements LayerDomainRegistrar {
 *   @Override
 *   public LayerDomain domain(ManifestDomainPolicy policy) {
 *     List<ManifestUnit> units = new ArrayList<>();
 *     units.add(new FluxInstanceManifestUnit());
 *     if (policy.isEnabled("porch")) {
 *       units.add(new PorchResourcesManifestUnit());
 *     }
 *     return new LayerDomain("gitops", List.of("replication"), units);
 *   }
 * }
 * }</pre>
 *
 * @see ManifestDomainPolicy
 * @see LayerDomain
 * @see LayerDomainRegistryBuilder
 */
public interface LayerDomainRegistrar {

  LayerDomain domain();

  default LayerDomain domain(ManifestDomainPolicy policy) {
    return domain();
  }
}
