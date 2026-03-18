// @codebase
package io.nxmatic.rk2lab.manifests.layers.mesh;

import io.nxmatic.rk2lab.manifests.layers.common.refs.ApiObjectRefLifecycle;
import io.nxmatic.rk2lab.manifests.layers.common.refs.ConfigMapRef;
import io.nxmatic.rk2lab.manifests.layers.common.refs.NamespaceRef;
import io.nxmatic.rk2lab.manifests.layers.common.refs.SecretRef;

/** Shared mesh references that can be consumed independently of resource realization. */
public final class MeshLayerRefs {

  public static final NamespaceRef HEADSCALE_SYSTEM_NAMESPACE =
      NamespaceRef.of("mesh/headscale-system-namespace", "headscale-system");

  public static final ConfigMapRef HEADPLANE_ENV_CONFIGMAP =
      ConfigMapRef.of("mesh/headplane-env-configmap", HEADSCALE_SYSTEM_NAMESPACE, "headplane-env");

  public static final ConfigMapRef HEADSCALE_CONFIG_CONFIGMAP =
      ConfigMapRef.of(
          "mesh/headscale-config-configmap", HEADSCALE_SYSTEM_NAMESPACE, "headscale-config");

  public static final ConfigMapRef HEADSCALE_ENV_CONFIGMAP =
      ConfigMapRef.of("mesh/headscale-env-configmap", HEADSCALE_SYSTEM_NAMESPACE, "headscale-env");

  public static final SecretRef HEADSCALE_CLIENT_AUTH_SECRET =
      SecretRef.of(
          "mesh/headscale-client-auth-secret",
          HEADSCALE_SYSTEM_NAMESPACE,
          "headscale-client-auth",
          ApiObjectRefLifecycle.RUNTIME_CREATED);

  public static final SecretRef HEADPLANE_SECRETS_SECRET =
      SecretRef.of(
          "mesh/headplane-secrets-secret", HEADSCALE_SYSTEM_NAMESPACE, "headplane-secrets");

  private MeshLayerRefs() {}
}
