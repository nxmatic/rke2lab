// @codebase
package io.nxmatic.rk2lab.manifests.refs;

/** Marker interface for stable references that can index realized cdk8s ApiObjects. */
public interface ApiObjectRef {

  String referenceId();

  ApiObjectRefLifecycle lifecycle();

  default boolean isRegistryOwned() {
    return lifecycle() == ApiObjectRefLifecycle.SYNTHESIZED;
  }
}
