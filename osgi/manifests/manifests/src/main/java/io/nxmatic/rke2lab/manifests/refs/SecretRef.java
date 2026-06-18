// @codebase
package io.nxmatic.rke2lab.manifests.refs;

/** Stable reference to a Kubernetes Secret. */
public record SecretRef(String referenceId, NamespaceRef namespaceRef, String name)
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
  }

  public static SecretRef of(
      final String referenceId, final NamespaceRef namespaceRef, final String name) {
    return new SecretRef(referenceId, namespaceRef, name);
  }

  @Override
  public String kind() {
    return "Secret";
  }

  @Override
  public String namespace() {
    return namespaceRef.name();
  }

  public String namespaceName() {
    return namespaceRef.name();
  }

  public String qualifiedName() {
    return namespaceName() + "/" + name;
  }
}
