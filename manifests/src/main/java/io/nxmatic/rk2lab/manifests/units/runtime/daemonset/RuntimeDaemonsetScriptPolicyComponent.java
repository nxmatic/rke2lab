// @codebase
package io.nxmatic.rk2lab.manifests.units.runtime.daemonset;

import io.nxmatic.rk2lab.manifests.ManifestAnnotations;
import io.nxmatic.rk2lab.manifests.registry.ManifestUnitReferenceRegistry;
import io.nxmatic.rk2lab.manifests.units.cluster.ClusterRefs;
import io.nxmatic.rk2lab.manifests.units.runtime.RuntimeRefs;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class RuntimeDaemonsetScriptPolicyComponent extends Construct {

  public static final String SCRIPT_POLICY_CONFIGMAP_NAME =
      RuntimeRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP.name();

  private static final String LAYER_NAME = "runtime";

  private static final String PACKAGE_NAME = "daemonset";

  private final ManifestAnnotations manifestAnnotations = new ManifestAnnotations();

  private final ManifestUnitReferenceRegistry registry;

  private final RuntimeDaemonsetScriptPolicyAssets runtimeDaemonsetScriptPolicyAssets;

  public RuntimeDaemonsetScriptPolicyComponent(final Construct scope, final String id) {
    this(scope, id, null);
  }

  public RuntimeDaemonsetScriptPolicyComponent(
      final Construct scope, final String id, final ManifestUnitReferenceRegistry registry) {
    super(scope, id);
    this.registry = registry;
    this.runtimeDaemonsetScriptPolicyAssets = RuntimeDaemonsetScriptPolicyAssets.builder().build();
    createScriptPolicyConfigMap();
  }

  private void createScriptPolicyConfigMap() {
    ApiObject configMap =
        new ApiObject(
            this,
            "configmap-" + RuntimeRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP.name(),
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(RuntimeRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP.name())
                        .namespace(RuntimeRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP.namespaceName())
                        .annotations(
                            manifestAnnotations.packageAnnotations(LAYER_NAME, PACKAGE_NAME))
                        .build())
                .build());

    if (registry != null) {
      configMap.addDependency(registry.require(ClusterRefs.RUNTIME_SYSTEM_NAMESPACE));
      registry.publish(RuntimeRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP, configMap);
    }

    configMap.addJsonPatch(
        JsonPatch.add("/data", runtimeDaemonsetScriptPolicyAssets.configMapData()));
  }
}
