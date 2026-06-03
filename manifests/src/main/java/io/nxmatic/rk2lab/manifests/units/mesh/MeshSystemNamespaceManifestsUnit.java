package io.nxmatic.rk2lab.manifests.units.mesh;

import io.nxmatic.rk2lab.manifests.AbstractManifestsUnit;
import io.nxmatic.rk2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.ManifestsUnitContext;
import io.nxmatic.rk2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import software.constructs.Construct;

public final class MeshSystemNamespaceManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.MESH + "/system-namespace";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("mesh", "system-namespace");

  public MeshSystemNamespaceManifestsUnit(final Construct scope, final String id) {
    super(scope, id, MANIFEST_UNIT_ID, List.of());
    createNamespace();
  }

  private void createNamespace() {
    new ApiObject(
        this,
        "namespace-" + MeshRefs.MESH_SYSTEM_NAMESPACE.name(),
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name(MeshRefs.MESH_SYSTEM_NAMESPACE.name())
                    .annotations(
                        packageProfile.packageAnnotations(
                            "|Namespace|default|" + MeshRefs.MESH_SYSTEM_NAMESPACE.name()))
                    .labels(Map.of("rk2lab.nxmatic.io/shared-namespace", "true"))
                    .build())
            .build());
  }

  @Override
  public void apply(final ManifestsUnitContext context) {
    new MeshSystemNamespaceManifestsUnit(context.chart(), "layer-mesh-system-namespace");
  }
}
