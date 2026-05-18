// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime;

import io.nxmatic.rk2lab.manifests.layers.cluster.ClusterLayerRefs;
import io.nxmatic.rk2lab.manifests.layers.common.KptMetadata;
import io.nxmatic.rk2lab.manifests.layers.common.registry.ManifestUnitReferenceRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class RuntimeDaemonsetScriptPolicyLayer extends Construct {

  public static final String SCRIPT_POLICY_CONFIGMAP_NAME =
      RuntimeLayerRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP.name();

  private static final String LAYER_NAME = "runtime";

  private static final String PACKAGE_NAME = "daemonset";

  private final KptMetadata kptMetadata = new KptMetadata();

  private final ManifestUnitReferenceRegistry registry;

  public RuntimeDaemonsetScriptPolicyLayer(final Construct scope, final String id) {
    this(scope, id, null);
  }

  public RuntimeDaemonsetScriptPolicyLayer(
      final Construct scope, final String id, final ManifestUnitReferenceRegistry registry) {
    super(scope, id);
    this.registry = registry;
    createScriptPolicyConfigMap();
  }

  private void createScriptPolicyConfigMap() {
    ApiObject configMap =
        new ApiObject(
            this,
            "configmap-" + RuntimeLayerRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP.name(),
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(RuntimeLayerRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP.name())
                        .namespace(
                            RuntimeLayerRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP.namespaceName())
                        .annotations(
                            kptMetadata.packageAnnotations(
                                LAYER_NAME,
                                PACKAGE_NAME,
                                "|ConfigMap|"
                                    + RuntimeLayerRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP
                                        .namespaceName()
                                    + "|"
                                    + RuntimeLayerRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP.name()))
                        .build())
                .build());

    if (registry != null) {
      configMap.addDependency(registry.require(ClusterLayerRefs.RUNTIME_SYSTEM_NAMESPACE));
      registry.publish(RuntimeLayerRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP, configMap);
    }

    configMap.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "daemonset-logging.sh",
                readResource("/runtime/daemonset/.sh.d/daemonset-logging.sh"),
                "daemonless-host-asset-materializer.sh",
                readResource("/runtime/daemonset/.sh.d/daemonless-host-asset-materializer.sh"),
                "daemonless-trampoline.sh",
                readResource("/runtime/daemonset/.sh.d/daemonless-trampoline.sh"))));
  }

  private String readResource(final String resourcePath) {
    final InputStream input =
        RuntimeDaemonsetScriptPolicyLayer.class.getResourceAsStream(resourcePath);
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
