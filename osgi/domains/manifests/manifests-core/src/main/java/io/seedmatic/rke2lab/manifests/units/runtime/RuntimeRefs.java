// @codebase
package io.seedmatic.rke2lab.manifests.units.runtime;

import io.seedmatic.rke2lab.manifests.refs.ConfigMapRef;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRefs;

/** Shared runtime references that can be consumed independently of resource realization. */
public final class RuntimeRefs {

  public static final ConfigMapRef FLOX_ENV_CONFIGMAP =
      ConfigMapRef.of(
          "runtime/flox-env-configmap", ClusterRefs.RUNTIME_SYSTEM_NAMESPACE, "flox-env");

  private RuntimeRefs() {}
}
