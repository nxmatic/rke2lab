// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime.cloudinit;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class RuntimeCloudConfigLayer extends Construct {

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("runtime", "cloud-config");

  private final RuntimeCloudConfigAssets runtimeCloudConfigAssets =
      RuntimeCloudConfigAssets.builder().build();

  public RuntimeCloudConfigLayer(final Construct scope, final String id) {
    super(scope, id);
    createCloudConfigManifest();
  }

  private void createCloudConfigManifest() {
    ApiObject configMap =
        new ApiObject(
            this,
            "configmap-cloud-config",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("cloud-config")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ConfigMap|default|cloud-config",
                                Map.of(
                                    "config.kubernetes.io/local-config",
                                    "true",
                                    "description.kpt.dev",
                                    "Cloud-init NoCloud payload")))
                        .build())
                .build());

    configMap.addJsonPatch(JsonPatch.add("/data", runtimeCloudConfigAssets.configMapData()));
  }
}
