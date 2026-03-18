// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime;

import io.nxmatic.rk2lab.manifests.layers.cluster.ClusterLayerRefs;
import io.nxmatic.rk2lab.manifests.layers.common.refs.ConfigMapRef;

/** Shared runtime references that can be consumed independently of resource realization. */
public final class RuntimeLayerRefs {

  public static final ConfigMapRef DAEMONSET_SCRIPT_POLICY_CONFIGMAP =
      ConfigMapRef.of(
          "runtime/daemonset-script-policy-configmap",
          ClusterLayerRefs.RUNTIME_SYSTEM_NAMESPACE,
          "runtime-daemonset-script-policy");

  public static final ConfigMapRef FLOX_ENV_CONFIGMAP =
      ConfigMapRef.of(
          "runtime/flox-env-configmap", ClusterLayerRefs.RUNTIME_SYSTEM_NAMESPACE, "flox-env");

  public static final ConfigMapRef FLOX_RUNTIME_INSTALLER_ASSETS_CONFIGMAP =
      ConfigMapRef.of(
          "runtime/flox-runtime-installer-assets-configmap",
          ClusterLayerRefs.RUNTIME_SYSTEM_NAMESPACE,
          "flox-runtime-installer-assets");

  private RuntimeLayerRefs() {}
}
