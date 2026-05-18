// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime.cloudinit;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class RuntimeCloudConfigLayer extends Construct {

  public static final String LEGACY_PATH_PREFIX = "runtime/cloud-config/";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("runtime", "cloud-config");

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

    configMap.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "userData",
                readResource("/runtime/cloud-config/user-data"),
                "metaData",
                readResource("/runtime/cloud-config/meta-data"),
                "networkData",
                readResource("/runtime/cloud-config/network-config"))));
  }

  private String readResource(final String resourcePath) {
    final InputStream input = RuntimeCloudConfigLayer.class.getResourceAsStream(resourcePath);
    if (input == null) {
      throw new IllegalStateException("Missing runtime cloud-config resource: " + resourcePath);
    }

    try {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Failed reading runtime cloud-config resource: " + resourcePath, ex);
    }
  }
}
