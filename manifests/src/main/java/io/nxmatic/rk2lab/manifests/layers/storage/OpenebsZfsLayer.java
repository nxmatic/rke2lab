// @codebase
package io.nxmatic.rk2lab.manifests.layers.storage;

import io.nxmatic.rk2lab.manifests.layers.common.KptMetadata;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

import java.util.Map;

public final class OpenebsZfsLayer extends Construct {

    public static final String LEGACY_PATH_PREFIX = "storage/openebs-zfs/";
        private static final String LAYER_NAME = "storage";
        private static final String PACKAGE_NAME = "openebs-zfs";

        private final KptMetadata kptMetadata = new KptMetadata();

    public OpenebsZfsLayer(final Construct scope, final String id) {
        super(scope, id);
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
                        .metadata(ApiObjectMetadata.builder()
                                .name("openebs")
                                .annotations(kptMetadata.packageAnnotations(
                                        LAYER_NAME,
                                        PACKAGE_NAME,
                                        "|Namespace|default|openebs"
                                ))
                                .build())
                        .build()
        );
    }

    private void createStorageClass() {
        ApiObject storageClass = new ApiObject(
                this,
                "storageclass-openebs-zfs",
                ApiObjectProps.builder()
                        .apiVersion("storage.k8s.io/v1")
                        .kind("StorageClass")
                        .metadata(ApiObjectMetadata.builder()
                                .name("openebs-zfs")
                                .annotations(kptMetadata.packageAnnotations(
                                        LAYER_NAME,
                                        PACKAGE_NAME,
                                        "storage.k8s.io|StorageClass|default|openebs-zfs",
                                        Map.of("storageclass.kubernetes.io/is-default-class", "true")
                                ))
                                .build())
                        .build()
        );

        storageClass.addJsonPatch(
                JsonPatch.add("/allowVolumeExpansion", true),
                JsonPatch.add("/parameters", Map.of(
                        "fstype", "zfs",
                        "poolname", "tank/rke2/control-nodes/master"
                )),
                JsonPatch.add("/provisioner", "zfs.csi.openebs.io"),
                JsonPatch.add("/reclaimPolicy", "Delete"),
                JsonPatch.add("/volumeBindingMode", "WaitForFirstConsumer")
        );
    }

        private void createHelmChart(final ApiObject namespace) {
        ApiObject helmChart = new ApiObject(
                this,
                "helmchart-openebs-zfs",
                ApiObjectProps.builder()
                        .apiVersion("helm.cattle.io/v1")
                        .kind("HelmChart")
                        .metadata(ApiObjectMetadata.builder()
                                .name("openebs-zfs")
                                .namespace("openebs")
                                .annotations(kptMetadata.packageAnnotations(
                                        LAYER_NAME,
                                        PACKAGE_NAME,
                                        "helm.cattle.io|HelmChart|openebs|openebs-zfs"
                                ))
                                .build())
                        .build()
        );

        helmChart.addDependency(namespace);

        helmChart.addJsonPatch(
                JsonPatch.add("/spec", Map.of(
                        "chart", "zfs-localpv",
                        "createNamespace", true,
                        "repo", "https://openebs.github.io/zfs-localpv",
                        "targetNamespace", "openebs",
                        "valuesContent", "crds:\n  csi:\n    volumeSnapshots:\n      enabled: false\nzfs:\n  bin: /usr/sbin/zfs\nzfsNode:\n  kubeletDir: /var/lib/kubelet\n",
                        "version", "2.8.0"
                ))
        );
    }
}
