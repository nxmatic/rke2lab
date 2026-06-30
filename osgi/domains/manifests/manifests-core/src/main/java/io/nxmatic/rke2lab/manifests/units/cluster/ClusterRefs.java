// @codebase
package io.nxmatic.rke2lab.manifests.units.cluster;

import io.nxmatic.rke2lab.manifests.refs.NamespaceRef;

/** Shared cluster-owned references that may be consumed before resources are realized. */
public final class ClusterRefs {

  public static final NamespaceRef RUNTIME_SYSTEM_NAMESPACE =
      NamespaceRef.of("cluster/runtime-system-namespace", "rke2lab-system");

  private ClusterRefs() {}
}
