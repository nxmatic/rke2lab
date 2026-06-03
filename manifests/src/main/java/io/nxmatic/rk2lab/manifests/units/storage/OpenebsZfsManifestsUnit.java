// @codebase
package io.nxmatic.rk2lab.manifests.units.storage;

import io.nxmatic.rk2lab.manifests.AbstractManifestsUnit;
import io.nxmatic.rk2lab.manifests.ManifestAnnotations;
import io.nxmatic.rk2lab.manifests.ManifestDomainCatalog;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class OpenebsZfsManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.STORAGE + "/openebs-zfs";

  private static final String LAYER_NAME = "storage";
  private static final String PACKAGE_NAME = "openebs-zfs";

  private final ManifestAnnotations manifestAnnotations = new ManifestAnnotations();

  private final String chartVersion;

  public OpenebsZfsManifestsUnit(final Construct scope, final String id) {
    super(scope, id, MANIFEST_UNIT_ID, List.of());
    this.chartVersion = componentVersions().openebsZfsChart();
    ApiObject namespace = createNamespace();
    createStorageClass();
    createHelmChart(namespace);
  }

  private ApiObject createNamespace() {
    return new ApiObject(
        this,
        "namespace-openebs",
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name("openebs")
                    .annotations(manifestAnnotations.packageAnnotations(LAYER_NAME, PACKAGE_NAME))
                    .build())
            .build());
  }

  private void createStorageClass() {
    ApiObject storageClass =
        new ApiObject(
            this,
            "storageclass-openebs-zfs",
            ApiObjectProps.builder()
                .apiVersion("storage.k8s.io/v1")
                .kind("StorageClass")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("openebs-zfs")
                        .annotations(
                            manifestAnnotations.packageAnnotations(
                                LAYER_NAME,
                                PACKAGE_NAME,
                                Map.of("storageclass.kubernetes.io/is-default-class", "true")))
                        .build())
                .build());

    storageClass.addJsonPatch(
        JsonPatch.add("/allowVolumeExpansion", true),
        JsonPatch.add(
            "/parameters", Map.of("fstype", "zfs", "poolname", "tank/rke2/control-nodes/master")),
        JsonPatch.add("/provisioner", "zfs.csi.openebs.io"),
        JsonPatch.add("/reclaimPolicy", "Delete"),
        JsonPatch.add("/volumeBindingMode", "WaitForFirstConsumer"));
  }

  private void createHelmChart(final ApiObject namespace) {
    ApiObject helmChart =
        new ApiObject(
            this,
            "helmchart-openebs-zfs",
            ApiObjectProps.builder()
                .apiVersion("helm.cattle.io/v1")
                .kind("HelmChart")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("openebs-zfs")
                        .namespace("openebs")
                        .annotations(
                            manifestAnnotations.packageAnnotations(LAYER_NAME, PACKAGE_NAME))
                        .build())
                .build());

    helmChart.addDependency(namespace);

    helmChart.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "chart",
                "zfs-localpv",
                "createNamespace",
                true,
                "repo",
                "https://openebs.github.io/zfs-localpv",
                "targetNamespace",
                "openebs",
                "valuesContent",
                "crds:\n  csi:\n    volumeSnapshots:\n      enabled: false\nzfs:\n  bin: /usr/sbin/zfs\nzfsNode:\n  kubeletDir: /var/lib/kubelet\n",
                "version",
                chartVersion)));
  }
}
