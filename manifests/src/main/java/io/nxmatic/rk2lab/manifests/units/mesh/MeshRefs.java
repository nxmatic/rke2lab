// @codebase
package io.nxmatic.rk2lab.manifests.units.mesh;

import io.nxmatic.rk2lab.manifests.refs.ApiObjectRefLifecycle;
import io.nxmatic.rk2lab.manifests.refs.ConfigMapRef;
import io.nxmatic.rk2lab.manifests.refs.NamespaceRef;
import io.nxmatic.rk2lab.manifests.refs.SecretRef;

/** Shared mesh references that can be consumed independently of resource realization. */
public final class MeshRefs {

  public static final NamespaceRef MESH_SYSTEM_NAMESPACE =
      NamespaceRef.of("mesh/system-namespace", "mesh-system");

  public static final NamespaceRef HEADSCALE_SYSTEM_NAMESPACE = MESH_SYSTEM_NAMESPACE;

  public static final ConfigMapRef HEADPLANE_ENV_CONFIGMAP =
      ConfigMapRef.of("mesh/headplane-env-configmap", MESH_SYSTEM_NAMESPACE, "headplane-env");

  public static final ConfigMapRef HEADSCALE_CONFIG_CONFIGMAP =
      ConfigMapRef.of("mesh/headscale-config-configmap", MESH_SYSTEM_NAMESPACE, "headscale-config");

  public static final ConfigMapRef HEADSCALE_ENV_CONFIGMAP =
      ConfigMapRef.of("mesh/headscale-env-configmap", MESH_SYSTEM_NAMESPACE, "headscale-env");

  public static final SecretRef HEADSCALE_CLIENT_AUTH_SECRET =
      SecretRef.of(
          "mesh/headscale-client-auth-secret",
          MESH_SYSTEM_NAMESPACE,
          "headscale-client-auth",
          ApiObjectRefLifecycle.RUNTIME_CREATED);

  public static final SecretRef HEADPLANE_SECRETS_SECRET =
      SecretRef.of("mesh/headplane-secrets-secret", MESH_SYSTEM_NAMESPACE, "headplane-secrets");

  private MeshRefs() {}
}
