// @codebase
package io.nxmatic.rke2lab.manifests.units.runtime.daemonset;

import io.nxmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.nxmatic.rke2lab.manifests.ManifestAnnotations;
import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.ManifestsUnitContext;
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

  private static final String DOMAIN_NAME = "runtime";

  private static final String PACKAGE_NAME = "daemonset";

  private final ManifestAnnotations manifestAnnotations = new ManifestAnnotations();

  private final RuntimeDaemonsetScriptPolicyAssets runtimeDaemonsetScriptPolicyAssets =
      RuntimeDaemonsetScriptPolicyAssets.builder().build();

  public RuntimeDaemonsetScriptPolicyManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of(ClusterRuntimeNamespaceManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    createScriptPolicyConfigMap(scope, context);
  }

  private void createScriptPolicyConfigMap(
      final Construct scope, final ManifestsUnitContext context) {
    ApiObject configMap =
        new ApiObject(
            scope,
            "configmap-" + RuntimeRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP.name(),
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(RuntimeRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP.name())
                        .namespace(RuntimeRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP.namespaceName())
                        .annotations(
                            manifestAnnotations.packageAnnotations(DOMAIN_NAME, PACKAGE_NAME))
                        .build())
                .build());

    configMap.addJsonPatch(
        JsonPatch.add("/data", runtimeDaemonsetScriptPolicyAssets.configMapData()));

    context.registry().publish(RuntimeRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP, configMap);
  }
}
