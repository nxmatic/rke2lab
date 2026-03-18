// @codebase
package io.nxmatic.rk2lab.manifests.layers.common.registry;

import io.nxmatic.rk2lab.manifests.layers.common.refs.ApiObjectRef;
import java.util.Optional;
import org.cdk8s.ApiObject;

/** Manifest-unit-scoped hierarchical registry view used during a unit apply/build. */
public final class ManifestUnitReferenceRegistry {

  private final DomainReferenceRegistry domainRegistry;
  private final String manifestUnitId;

  ManifestUnitReferenceRegistry(
      final DomainReferenceRegistry domainRegistry, final String manifestUnitId) {
    if (domainRegistry == null) {
      throw new IllegalArgumentException("domainRegistry must not be null");
    }
    if (manifestUnitId == null || manifestUnitId.isBlank()) {
      throw new IllegalArgumentException("manifestUnitId must not be blank");
    }
    this.domainRegistry = domainRegistry;
    this.manifestUnitId = manifestUnitId;
  }

  public String domainId() {
    return domainRegistry.domainId();
  }

  public String manifestUnitId() {
    return manifestUnitId;
  }

  public Optional<ApiObject> resolve(final ApiObjectRef ref) {
    return domainRegistry.resolve(ref);
  }

  public ApiObject require(final ApiObjectRef ref) {
    return domainRegistry.require(ref);
  }

  public void publish(final ApiObjectRef ref, final ApiObject apiObject) {
    domainRegistry.publish(ref, apiObject, manifestUnitId);
  }
}
