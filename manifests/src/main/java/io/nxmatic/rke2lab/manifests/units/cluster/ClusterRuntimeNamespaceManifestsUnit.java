// @codebase
package io.nxmatic.rke2lab.manifests.units.cluster;

import io.nxmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.ManifestsUnitContext;
import io.nxmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import software.constructs.Construct;

public final class ClusterRuntimeNamespaceManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID =
      ManifestDomainCatalog.CLUSTER + "/runtime-system-namespace";

  public static final String OUTPUT_DIR = "runtime-system-namespace";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(ManifestDomainCatalog.CLUSTER, OUTPUT_DIR);

  public ClusterRuntimeNamespaceManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    ApiObject namespace =
        new ApiObject(
            scope,
            "namespace-" + ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name(),
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Namespace")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name())
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Namespace|default|"
                                    + ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name()))
                        .labels(Map.of("rke2lab.nxmatic.io/shared-namespace", "true"))
                        .build())
                .build());

    context.registry().publish(ClusterRefs.RUNTIME_SYSTEM_NAMESPACE, namespace);
  }
}
