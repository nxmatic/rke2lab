// @codebase
package io.nxmatic.rk2lab.manifests.layers.mesh;

import io.nxmatic.rk2lab.manifests.layers.common.refs.ConfigMapRef;
import io.nxmatic.rk2lab.manifests.layers.common.refs.NamespaceRef;
import io.nxmatic.rk2lab.manifests.layers.common.refs.SecretRef;

/** Shared mesh references that can be consumed independently of resource realization. */
public final class MeshLayerRefs {

  public static final NamespaceRef HEADSCALE_SYSTEM_NAMESPACE =
      NamespaceRef.of("mesh/headscale-system-namespace", "headscale-system");

  public static final ConfigMapRef HEADSCALE_CONFIG_CONFIGMAP =
      ConfigMapRef.of(
          "mesh/headscale-config-configmap", HEADSCALE_SYSTEM_NAMESPACE, "headscale-config");

  public static final SecretRef HEADPLANE_SECRETS_SECRET =
      SecretRef.of(
          "mesh/headplane-secrets-secret", HEADSCALE_SYSTEM_NAMESPACE, "headplane-secrets");

  private MeshLayerRefs() {}
}
