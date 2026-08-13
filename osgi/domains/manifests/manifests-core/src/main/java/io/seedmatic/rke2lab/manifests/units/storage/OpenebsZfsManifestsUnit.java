// @codebase
package io.seedmatic.rke2lab.manifests.units.storage;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotations;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class OpenebsZfsManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.STORAGE + "/openebs-zfs";

  private static final String DOMAIN_NAME = "storage";
  private static final String PACKAGE_NAME = "openebs-zfs";

  private final ManifestAnnotations manifestAnnotations = new ManifestAnnotations();

  public OpenebsZfsManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String chartVersion =
        ManifestSynthesisContext.current().componentVersions().of(Component.OPENEBS_ZFS_CHART);
    ApiObject namespace = createNamespace(scope);
    createStorageClass(scope);
    createHelmChart(scope, namespace, chartVersion);
  }

  private ApiObject createNamespace(final Construct scope) {
    return new ApiObject(
        scope,
        "namespace-openebs",
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name("openebs")
                    .annotations(manifestAnnotations.packageAnnotations(DOMAIN_NAME, PACKAGE_NAME))
                    .build())
            .build());
  }

  private void createStorageClass(final Construct scope) {
    ApiObject storageClass =
        new ApiObject(
            scope,
            "storageclass-openebs-zfs",
            ApiObjectProps.builder()
                .apiVersion("storage.k8s.io/v1")
                .kind("StorageClass")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("openebs-zfs")
                        .annotations(
                            manifestAnnotations.packageAnnotations(
                                DOMAIN_NAME,
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

  private void createHelmChart(
      final Construct scope, final ApiObject namespace, final String chartVersion) {
    ApiObject helmChart =
        new ApiObject(
            scope,
            "helmchart-openebs-zfs",
            ApiObjectProps.builder()
                .apiVersion("helm.cattle.io/v1")
                .kind("HelmChart")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("openebs-zfs")
                        .namespace("openebs")
                        .annotations(
                            manifestAnnotations.packageAnnotations(DOMAIN_NAME, PACKAGE_NAME))
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
