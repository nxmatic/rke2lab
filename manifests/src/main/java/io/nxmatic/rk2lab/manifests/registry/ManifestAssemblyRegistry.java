// @codebase
package io.nxmatic.rk2lab.manifests.registry;

import io.nxmatic.rk2lab.manifests.refs.ApiObjectRef;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.cdk8s.ApiObject;

/**
 * Root backing store for realized ApiObjects published during a synthesis run.
 *
 * <p>Domain- and unit-scoped registries are hierarchical views over this assembly-level registry.
 */
public final class ManifestAssemblyRegistry {

  private final Map<ApiObjectRef, ApiObject> apiObjectsByRef = new LinkedHashMap<>();
  private final Map<ApiObjectRef, PublicationScope> publicationScopeByRef = new LinkedHashMap<>();

  public DomainReferenceRegistry domainRegistry(final String domainId) {
    return new DomainReferenceRegistry(this, domainId);
  }

  Optional<ApiObject> resolve(final ApiObjectRef ref) {
    return Optional.ofNullable(apiObjectsByRef.get(ref));
  }

  ApiObject require(final ApiObjectRef ref) {
    if (ref == null) {
      throw new IllegalArgumentException("ref must not be null");
    }
    if (!ref.isRegistryOwned()) {
      throw new IllegalStateException(
          "Reference is not registry-owned and cannot be required: "
              + ref.referenceId()
              + " (lifecycle="
              + ref.lifecycle()
              + ")");
    }
    return resolve(ref)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No ApiObject has been published for reference: " + ref.referenceId()));
  }

  void publish(final ApiObjectRef ref, final ApiObject apiObject, final PublicationScope scope) {
    if (ref == null) {
      throw new IllegalArgumentException("ref must not be null");
    }
    if (!ref.isRegistryOwned()) {
      throw new IllegalStateException(
          "Reference is not registry-owned and cannot be published: "
              + ref.referenceId()
              + " (lifecycle="
              + ref.lifecycle()
              + ")");
    }
    if (apiObject == null) {
      throw new IllegalArgumentException("apiObject must not be null");
    }
    if (scope == null) {
      throw new IllegalArgumentException("scope must not be null");
    }

    final ApiObject previous = apiObjectsByRef.putIfAbsent(ref, apiObject);
    if (previous != null && previous != apiObject) {
      final PublicationScope previousScope = publicationScopeByRef.get(ref);
      throw new IllegalStateException(
          "Reference already published: "
              + ref.referenceId()
              + " (previous publisher="
              + previousScope
              + ", new publisher="
              + scope
              + ")");
    }
    publicationScopeByRef.putIfAbsent(ref, scope);
  }

  record PublicationScope(String domainId, String manifestUnitId) {

    PublicationScope {
      if (domainId == null || domainId.isBlank()) {
        throw new IllegalArgumentException("domainId must not be blank");
      }
      if (manifestUnitId == null || manifestUnitId.isBlank()) {
        throw new IllegalArgumentException("manifestUnitId must not be blank");
      }
    }

    @Override
    public String toString() {
      return domainId + "/" + manifestUnitId;
    }
  }
}
