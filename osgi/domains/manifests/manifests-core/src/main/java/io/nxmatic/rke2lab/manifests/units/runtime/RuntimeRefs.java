// @codebase
package io.nxmatic.rke2lab.manifests.units.runtime;

import io.nxmatic.rke2lab.manifests.refs.ConfigMapRef;
import io.nxmatic.rke2lab.manifests.units.cluster.ClusterRefs;

/** Shared runtime references that can be consumed independently of resource realization. */
public final class RuntimeRefs {

  public static final ConfigMapRef DAEMONSET_SCRIPT_POLICY_CONFIGMAP =
      ConfigMapRef.of(
          "runtime/daemonset-script-policy-configmap",
          ClusterRefs.RUNTIME_SYSTEM_NAMESPACE,
          "runtime-daemonset-script-policy");

  public static final ConfigMapRef FLOX_ENV_CONFIGMAP =
      ConfigMapRef.of(
          "runtime/flox-env-configmap", ClusterRefs.RUNTIME_SYSTEM_NAMESPACE, "flox-env");

  // FLOX_RUNTIME_INSTALLER_ASSETS_CONFIGMAP retired — installer assets ride a
  // hostPath volume now (seed-master writes them at prepareHostState).
  // See FloxRuntimeAssets.writeInstallerAssetTree(...) and FloxRuntimeLayer.

  private RuntimeRefs() {}
}
