// @codebase
package io.nxmatic.rke2lab.manifests.refs;

/** Stable reference to a Kubernetes Namespace independent of realization lifecycle. */
public record NamespaceRef(String referenceId, String name, ApiObjectRefLifecycle lifecycle)
    implements ApiObjectRef {

  public NamespaceRef {
    if (referenceId == null || referenceId.isBlank()) {
      throw new IllegalArgumentException("referenceId must not be blank");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (lifecycle == null) {
      throw new IllegalArgumentException("lifecycle must not be null");
    }
  }

  public static NamespaceRef of(final String referenceId, final String name) {
    return of(referenceId, name, ApiObjectRefLifecycle.SYNTHESIZED);
  }

  public static NamespaceRef of(
      final String referenceId, final String name, final ApiObjectRefLifecycle lifecycle) {
    return new NamespaceRef(referenceId, name, lifecycle);
  }
}
