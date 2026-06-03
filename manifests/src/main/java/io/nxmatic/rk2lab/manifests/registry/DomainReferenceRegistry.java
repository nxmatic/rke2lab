// @codebase
package io.nxmatic.rk2lab.manifests.registry;

import io.nxmatic.rk2lab.manifests.refs.ApiObjectRef;
import java.util.Optional;
import org.cdk8s.ApiObject;

/** Domain-scoped hierarchical view over the assembly registry. */
public final class DomainReferenceRegistry {

  private final ManifestAssemblyRegistry assemblyRegistry;
  private final String domainId;

  DomainReferenceRegistry(final ManifestAssemblyRegistry assemblyRegistry, final String domainId) {
    if (assemblyRegistry == null) {
      throw new IllegalArgumentException("assemblyRegistry must not be null");
    }
    if (domainId == null || domainId.isBlank()) {
      throw new IllegalArgumentException("domainId must not be blank");
    }
    this.assemblyRegistry = assemblyRegistry;
    this.domainId = domainId;
  }

  public String domainId() {
    return domainId;
  }

  public ManifestsUnitReferenceRegistry manifestUnitRegistry(final String manifestUnitId) {
    return new ManifestsUnitReferenceRegistry(this, manifestUnitId);
  }

  public Optional<ApiObject> resolve(final ApiObjectRef ref) {
    return assemblyRegistry.resolve(ref);
  }

  public ApiObject require(final ApiObjectRef ref) {
    return assemblyRegistry.require(ref);
  }

  void publish(final ApiObjectRef ref, final ApiObject apiObject, final String manifestUnitId) {
    assemblyRegistry.publish(
        ref, apiObject, new ManifestAssemblyRegistry.PublicationScope(domainId, manifestUnitId));
  }
}
