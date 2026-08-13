// @codebase
package io.seedmatic.rke2lab.manifests.refs;

import java.util.Optional;

/** Stable reference to a Kubernetes Namespace (cluster-scoped). */
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

  @Override
  public String kind() {
    return "Namespace";
  }

  @Override
  public Optional<String> namespace() {
    return Optional.empty();
  }
}
