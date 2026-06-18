// @codebase
package io.nxmatic.rke2lab.manifests.refs;

/**
 * Stable reference to a realized cdk8s ApiObject, addressed by its Kubernetes coordinates.
 *
 * <p>A ref is the single source of truth for an object's identity: the producing unit creates the
 * ApiObject under these coordinates and consumers resolve it from the cdk8s tree via {@code
 * Cdk8sApiObjectResolver}.
 */
public interface ApiObjectRef {

  String referenceId();

  String kind();

  String name();

  /** Namespace for namespaced resources; {@code null} for cluster-scoped resources. */
  String namespace();
}
