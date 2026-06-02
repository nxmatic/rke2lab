// @codebase
package io.nxmatic.rk2lab.manifests.refs;

/** Stable reference to a Kubernetes Secret independent of realization lifecycle. */
public record SecretRef(
    String referenceId, NamespaceRef namespaceRef, String name, ApiObjectRefLifecycle lifecycle)
    implements ApiObjectRef {

  public SecretRef {
    if (referenceId == null || referenceId.isBlank()) {
      throw new IllegalArgumentException("referenceId must not be blank");
    }
    if (namespaceRef == null) {
      throw new IllegalArgumentException("namespaceRef must not be null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (lifecycle == null) {
      throw new IllegalArgumentException("lifecycle must not be null");
    }
  }

  public static SecretRef of(
      final String referenceId, final NamespaceRef namespaceRef, final String name) {
    return of(referenceId, namespaceRef, name, ApiObjectRefLifecycle.SYNTHESIZED);
  }

  public static SecretRef of(
      final String referenceId,
      final NamespaceRef namespaceRef,
      final String name,
      final ApiObjectRefLifecycle lifecycle) {
    return new SecretRef(referenceId, namespaceRef, name, lifecycle);
  }

  public String namespaceName() {
    return namespaceRef.name();
  }

  public String qualifiedName() {
    return namespaceName() + "/" + name;
  }
}
