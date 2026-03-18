// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime;

import io.nxmatic.rk2lab.manifests.layers.common.KptMetadata;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class RuntimeDaemonsetLayer extends Construct {

  public static final String LEGACY_PATH_PREFIX = "runtime/daemonset/";

  public static final String SCRIPT_POLICY_CONFIGMAP_NAME = "runtime-daemonset-script-policy";

  private static final String NAMESPACE = "rke2lab-system";

  private static final String LAYER_NAME = "runtime";

  private static final String PACKAGE_NAME = "daemonset";

  private final KptMetadata kptMetadata = new KptMetadata();

  public RuntimeDaemonsetLayer(final Construct scope, final String id) {
    super(scope, id);
    createScriptPolicyConfigMap();
  }

  private void createScriptPolicyConfigMap() {
    ApiObject configMap =
        new ApiObject(
            this,
            "configmap-runtime-daemonset-script-policy",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(SCRIPT_POLICY_CONFIGMAP_NAME)
                        .namespace(NAMESPACE)
                        .annotations(
                            kptMetadata.packageAnnotations(
                                LAYER_NAME,
                                PACKAGE_NAME,
                                "|ConfigMap|" + NAMESPACE + "|" + SCRIPT_POLICY_CONFIGMAP_NAME))
                        .build())
                .build());

    configMap.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "daemonset-logging.sh",
                readResource("/runtime/daemonset/.sh.d/daemonset-logging.sh"))));
  }

  private String readResource(final String resourcePath) {
    final InputStream input = RuntimeDaemonsetLayer.class.getResourceAsStream(resourcePath);
    if (input == null) {
      throw new IllegalStateException("Missing runtime daemonset resource: " + resourcePath);
    }

    try {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Failed reading runtime daemonset resource: " + resourcePath, ex);
    }
  }
}
