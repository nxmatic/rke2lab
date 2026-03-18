// @codebase
package io.nxmatic.rk2lab.manifests.layers.common.refs;

/** Stable reference to a Kubernetes Namespace independent of realization lifecycle. */
public record NamespaceRef(String referenceId, String name) implements ApiObjectRef {

  public NamespaceRef {
    if (referenceId == null || referenceId.isBlank()) {
      throw new IllegalArgumentException("referenceId must not be blank");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
  }

  public static NamespaceRef of(final String referenceId, final String name) {
    return new NamespaceRef(referenceId, name);
  }
}
