package io.nxmatic.rk2lab.manifests.layers.mesh;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import io.nxmatic.rk2lab.manifests.layers.common.registry.ManifestUnitReferenceRegistry;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import software.constructs.Construct;

/** Realizes the shared mesh system namespace used by headscale, headplane, and tailscale. */
public final class MeshSystemNamespaceLayer extends Construct {

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("mesh", "system-namespace");

  private final ManifestUnitReferenceRegistry registry;

  public MeshSystemNamespaceLayer(final Construct scope, final String id) {
    this(scope, id, null);
  }

  public MeshSystemNamespaceLayer(
      final Construct scope, final String id, final ManifestUnitReferenceRegistry registry) {
    super(scope, id);
    this.registry = registry;
    createNamespace();
  }

  private void createNamespace() {
    final ApiObject namespace =
        new ApiObject(
            this,
            "namespace-" + MeshLayerRefs.MESH_SYSTEM_NAMESPACE.name(),
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Namespace")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(MeshLayerRefs.MESH_SYSTEM_NAMESPACE.name())
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Namespace|default|" + MeshLayerRefs.MESH_SYSTEM_NAMESPACE.name()))
                        .labels(Map.of("rk2lab.nxmatic.io/shared-namespace", "true"))
                        .build())
                .build());
    if (registry != null) {
      registry.publish(MeshLayerRefs.MESH_SYSTEM_NAMESPACE, namespace);
    }
  }
}
