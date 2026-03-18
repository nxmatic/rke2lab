// @codebase
package io.nxmatic.rk2lab.manifests.layers.cluster;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import io.nxmatic.rk2lab.manifests.layers.common.registry.ManifestUnitReferenceRegistry;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import software.constructs.Construct;

/** Realizes the shared runtime system namespace owned by the cluster layer. */
public final class ClusterRuntimeNamespaceLayer extends Construct {

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cluster", "runtime-system-namespace");

  private final ManifestUnitReferenceRegistry registry;

  public ClusterRuntimeNamespaceLayer(final Construct scope, final String id) {
    this(scope, id, null);
  }

  public ClusterRuntimeNamespaceLayer(
      final Construct scope, final String id, final ManifestUnitReferenceRegistry registry) {
    super(scope, id);
    this.registry = registry;
    createNamespace();
  }

  private void createNamespace() {
    final ApiObject namespace =
        new ApiObject(
            this,
            "namespace-rke2lab-system",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Namespace")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(ClusterLayerRefs.RUNTIME_SYSTEM_NAMESPACE.name())
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Namespace|default|"
                                    + ClusterLayerRefs.RUNTIME_SYSTEM_NAMESPACE.name()))
                        .labels(Map.of("rk2lab.nxmatic.io/shared-namespace", "true"))
                        .build())
                .build());
    if (registry != null) {
      registry.publish(ClusterLayerRefs.RUNTIME_SYSTEM_NAMESPACE, namespace);
    }
  }
}
