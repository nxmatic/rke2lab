// @codebase
package io.nxmatic.rk2lab.manifests.layers.cluster;

import io.nxmatic.rk2lab.manifests.layers.common.refs.NamespaceRef;

/** Shared cluster-owned references that may be consumed before resources are realized. */
public final class ClusterLayerRefs {

  public static final NamespaceRef RUNTIME_SYSTEM_NAMESPACE =
      NamespaceRef.of("cluster/runtime-system-namespace", "rke2lab-system");

  private ClusterLayerRefs() {}
}
