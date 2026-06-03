// @codebase
package io.nxmatic.rk2lab.manifests;

import io.nxmatic.rk2lab.manifests.registry.ManifestsUnitReferenceRegistry;
import org.cdk8s.Chart;

/** Scoped execution context for applying a manifest unit. */
public record ManifestsUnitContext(
    Chart chart, String domainId, String manifestUnitId, ManifestsUnitReferenceRegistry registry) {

  public ManifestsUnitContext {
    if (chart == null) {
      throw new IllegalArgumentException("chart must not be null");
    }
    if (domainId == null || domainId.isBlank()) {
      throw new IllegalArgumentException("domainId must not be blank");
    }
    if (manifestUnitId == null || manifestUnitId.isBlank()) {
      throw new IllegalArgumentException("manifestUnitId must not be blank");
    }
    if (registry == null) {
      throw new IllegalArgumentException("registry must not be null");
    }
  }
}
