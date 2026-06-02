// @codebase
package io.nxmatic.rk2lab.manifests.refs;

/** Lifecycle/ownership model for a stable Kubernetes object reference. */
public enum ApiObjectRefLifecycle {
  /** Realized and published by this synthesis/registry pipeline. */
  SYNTHESIZED,

  /** Created by runtime/bootstrap behavior rather than manifest synthesis. */
  RUNTIME_CREATED,

  /** Pre-existing or foreign object not owned by the synthesis pipeline. */
  EXTERNAL
}
