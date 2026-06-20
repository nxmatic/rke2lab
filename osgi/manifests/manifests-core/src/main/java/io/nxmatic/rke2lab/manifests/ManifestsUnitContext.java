// @codebase
package io.nxmatic.rke2lab.manifests;

import io.nxmatic.rke2lab.manifests.node.NodeEnvContributorRegistry;
import org.cdk8s.Chart;

/** Scoped execution context for applying a manifest unit. */
public record ManifestsUnitContext(
    Chart chart,
    String domainId,
    String manifestUnitId,
    Cdk8sApiObjectResolver resolver,
    NodeEnvContributorRegistry contributorRegistry) {

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
    if (resolver == null) {
      throw new IllegalArgumentException("resolver must not be null");
    }
    if (contributorRegistry == null) {
      throw new IllegalArgumentException("contributorRegistry must not be null");
    }
  }
}
