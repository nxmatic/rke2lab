// @codebase
package io.nxmatic.rke2lab.manifests.refs;

/** Stable reference to a Kubernetes ConfigMap. */
public record ConfigMapRef(String referenceId, NamespaceRef namespaceRef, String name)
    implements ApiObjectRef {

  public ConfigMapRef {
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

  public static ConfigMapRef of(
      final String referenceId, final NamespaceRef namespaceRef, final String name) {
    return new ConfigMapRef(referenceId, namespaceRef, name);
  }

  @Override
  public String kind() {
    return "ConfigMap";
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
