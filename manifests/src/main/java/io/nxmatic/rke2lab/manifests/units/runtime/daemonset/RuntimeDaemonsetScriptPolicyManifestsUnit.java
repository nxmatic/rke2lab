// @codebase
package io.nxmatic.rke2lab.manifests.units.runtime.daemonset;

import io.nxmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.nxmatic.rke2lab.manifests.ManifestAnnotations;
import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.units.cluster.ClusterRuntimeNamespaceManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.runtime.RuntimeRefs;
import java.util.List;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class RuntimeDaemonsetScriptPolicyManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.RUNTIME + "/daemonset";

  public static final String SCRIPT_POLICY_CONFIGMAP_NAME =
      RuntimeRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP.name();

  private static final String LAYER_NAME = "runtime";

  private static final String PACKAGE_NAME = "daemonset";

  private final ManifestAnnotations manifestAnnotations = new ManifestAnnotations();

  private final RuntimeDaemonsetScriptPolicyAssets runtimeDaemonsetScriptPolicyAssets;

  public RuntimeDaemonsetScriptPolicyManifestsUnit(final Construct scope, final String id) {
    super(
        scope,
        id,
        MANIFEST_UNIT_ID,
        List.of(ClusterRuntimeNamespaceManifestsUnit.MANIFEST_UNIT_ID));
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

    configMap.addJsonPatch(
        JsonPatch.add("/data", runtimeDaemonsetScriptPolicyAssets.configMapData()));
  }
}
