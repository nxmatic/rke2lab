// @codebase
package io.seedmatic.rke2lab.manifests;

import io.seedmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.seedmatic.rke2lab.manifests.contract.node.NodeEnvContext;
import org.cdk8s.Chart;

/** Scoped execution context for applying a manifest unit. */
public record ManifestsUnitContext(
    Chart chart,
    String domainId,
    String manifestUnitId,
    Cdk8sApiObjectResolver resolver,
    ManifestDomainPolicy manifestDomainPolicy,
    NodeEnvContext nodeEnvContext,
    YamlMapper yaml) {

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
    if (manifestDomainPolicy == null) {
      throw new IllegalArgumentException("manifestDomainPolicy must not be null");
    }
    if (nodeEnvContext == null) {
      throw new IllegalArgumentException("nodeEnvContext must not be null");
    }
    if (yaml == null) {
      throw new IllegalArgumentException("yaml must not be null");
    }
  }
}
