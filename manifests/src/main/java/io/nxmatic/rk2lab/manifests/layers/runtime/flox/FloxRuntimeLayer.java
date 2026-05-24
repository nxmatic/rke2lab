// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime.flox;

import io.nxmatic.rk2lab.manifests.layers.cluster.ClusterLayerRefs;
import io.nxmatic.rk2lab.manifests.layers.common.KptMetadata;
import io.nxmatic.rk2lab.manifests.layers.common.registry.ManifestUnitReferenceRegistry;
import io.nxmatic.rk2lab.manifests.layers.mesh.MeshLayerRefs;
import io.nxmatic.rk2lab.manifests.layers.runtime.RuntimeLayerRefs;
import io.nxmatic.rk2lab.manifests.layers.runtime.daemonset.RuntimeDaemonsetScriptPolicyAssets;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class FloxRuntimeLayer extends Construct {

  private static final String LAYER_NAME = "runtime";

  private static final String PACKAGE_NAME = "flox-runtime";

  private final KptMetadata kptMetadata = new KptMetadata();

  private final ManifestUnitReferenceRegistry registry;

  private final RuntimeDaemonsetScriptPolicyAssets runtimeDaemonsetScriptPolicyAssets;

  private final FloxRuntimeAssets floxRuntimeAssets;

  public FloxRuntimeLayer(final Construct scope, final String id) {
    this(scope, id, null);
  }

  public FloxRuntimeLayer(
      final Construct scope, final String id, final ManifestUnitReferenceRegistry registry) {
    super(scope, id);
    this.registry = registry;
    this.runtimeDaemonsetScriptPolicyAssets = RuntimeDaemonsetScriptPolicyAssets.builder().build();
    this.floxRuntimeAssets =
        FloxRuntimeAssets.builder()
            .runtimeDaemonsetScriptPolicyAssets(runtimeDaemonsetScriptPolicyAssets)
            .build();

    // RuntimeClass no longer needed with NRI plugin approach
    // NRI plugin intercepts based on flox.dev/environment annotation
    ApiObject envConfigMap = createFloxEnvConfigMap();
    ApiObject installerAssetsConfigMap = createInstallerAssetsConfigMap();
    ApiObject serviceAccount = createServiceAccount();
    createInstallerDaemonSet(envConfigMap, installerAssetsConfigMap, serviceAccount);
  }

  // RuntimeClass removed: NRI plugin approach doesn't need custom runtime handlers
  // The NRI plugin intercepts container creation based on flox.dev/environment Pod annotation

  private ApiObject createFloxEnvConfigMap() {
    ApiObject configMap =
        new ApiObject(
            this,
            "configmap-" + RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.name(),
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.name())
                        .namespace(RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.namespaceName())
                        .annotations(
                            kptMetadata.packageAnnotations(
                                LAYER_NAME,
                                PACKAGE_NAME,
                                "|ConfigMap|"
                                    + RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.namespaceName()
                                    + "|"
                                    + RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.name(),
                                Map.of(
                                    "replicator.v1.mittwald.de/replicate-to",
                                    MeshLayerRefs.HEADSCALE_SYSTEM_NAMESPACE.name())))
                        .labels(Map.of("app.kubernetes.io/replicated", "true"))
                        .build())
                .build());

    if (registry != null) {
      configMap.addDependency(registry.require(ClusterLayerRefs.RUNTIME_SYSTEM_NAMESPACE));
      registry.publish(RuntimeLayerRefs.FLOX_ENV_CONFIGMAP, configMap);
    }

    configMap.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "FLOX_DISABLE_METRICS",
                "true",
                "FLOX_NO_TELEMETRY",
                "1",
                "FLOX_NONINTERACTIVE",
                "1")));
    return configMap;
  }

  private ApiObject createInstallerAssetsConfigMap() {
    ApiObject configMap =
        new ApiObject(
            this,
            "configmap-" + RuntimeLayerRefs.FLOX_RUNTIME_INSTALLER_ASSETS_CONFIGMAP.name(),
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(RuntimeLayerRefs.FLOX_RUNTIME_INSTALLER_ASSETS_CONFIGMAP.name())
                        .namespace(
                            RuntimeLayerRefs.FLOX_RUNTIME_INSTALLER_ASSETS_CONFIGMAP
                                .namespaceName())
                        .annotations(
                            kptMetadata.packageAnnotations(
                                LAYER_NAME,
                                PACKAGE_NAME,
                                "|ConfigMap|"
                                    + RuntimeLayerRefs.FLOX_RUNTIME_INSTALLER_ASSETS_CONFIGMAP
                                        .namespaceName()
                                    + "|"
                                    + RuntimeLayerRefs.FLOX_RUNTIME_INSTALLER_ASSETS_CONFIGMAP
                                        .name()))
                        .build())
                .build());

    if (registry != null) {
      configMap.addDependency(registry.require(ClusterLayerRefs.RUNTIME_SYSTEM_NAMESPACE));
      registry.publish(RuntimeLayerRefs.FLOX_RUNTIME_INSTALLER_ASSETS_CONFIGMAP, configMap);
    }

    configMap.addJsonPatch(JsonPatch.add("/data", floxRuntimeAssets.installerConfigMapData()));
    return configMap;
  }

  private ApiObject createServiceAccount() {
    ApiObject serviceAccount =
        new ApiObject(
            this,
            "serviceaccount-flox-runtime-installer",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ServiceAccount")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("flox-runtime-installer")
                        .namespace(RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.namespaceName())
                        .annotations(
                            kptMetadata.packageAnnotations(
                                LAYER_NAME,
                                PACKAGE_NAME,
                                "|ServiceAccount|"
                                    + RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.namespaceName()
                                    + "|flox-runtime-installer"))
                        .build())
                .build());
    if (registry != null) {
      serviceAccount.addDependency(registry.require(ClusterLayerRefs.RUNTIME_SYSTEM_NAMESPACE));
    }
    return serviceAccount;
  }

  private void createInstallerDaemonSet(
      final ApiObject envConfigMap,
      final ApiObject installerAssetsConfigMap,
      final ApiObject serviceAccount) {
    ApiObject daemonSet =
        new ApiObject(
            this,
            "daemonset-flox-runtime-installer",
            ApiObjectProps.builder()
                .apiVersion("apps/v1")
                .kind("DaemonSet")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("flox-runtime-installer")
                        .namespace(RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.namespaceName())
                        .annotations(
                            kptMetadata.packageAnnotations(
                                LAYER_NAME,
                                PACKAGE_NAME,
                                "apps|DaemonSet|"
                                    + RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.namespaceName()
                                    + "|flox-runtime-installer"))
                        .labels(
                            Map.of(
                                "app.kubernetes.io/component",
                                "runtime-shim",
                                "app.kubernetes.io/name",
                                "flox-runtime-installer"))
                        .build())
                .build());

    if (registry != null) {
      daemonSet.addDependency(registry.require(ClusterLayerRefs.RUNTIME_SYSTEM_NAMESPACE));
      daemonSet.addDependency(registry.require(RuntimeLayerRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP));
    }
    daemonSet.addDependency(envConfigMap);
    daemonSet.addDependency(installerAssetsConfigMap);
    daemonSet.addDependency(serviceAccount);

    daemonSet.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "selector",
                Map.of("matchLabels", Map.of("app.kubernetes.io/name", "flox-runtime-installer")),
                "template",
                Map.of(
                    "metadata",
                    Map.of(
                        "annotations",
                        Map.of(
                            "kpt.dev/package-layer",
                            LAYER_NAME,
                            "kpt.dev/package-name",
                            PACKAGE_NAME),
                        "labels",
                        Map.of(
                            "app.kubernetes.io/component",
                            "runtime-shim",
                            "app.kubernetes.io/name",
                            "flox-runtime-installer")),
                    "spec",
                    Map.ofEntries(
                        Map.entry("automountServiceAccountToken", false),
                        Map.entry(
                            "containers",
                            new Object[] {
                              Map.of(
                                  "command",
                                  new Object[] {"/bin/sh", "/.sh/bin/nri-plugin-run.sh"},
                                  "image",
                                  "busybox:stable",
                                  "name",
                                  "main",
                                  "securityContext",
                                  Map.of("privileged", false, "runAsGroup", 0, "runAsUser", 0),
                                  "volumeMounts",
                                  new Object[] {
                                    Map.of(
                                        "mountPath",
                                        "/.sh",
                                        "name",
                                        "runtime-installer-assets",
                                        "readOnly",
                                        true)
                                  },
                                  "resources",
                                  Map.of(
                                      "limits",
                                      Map.of(
                                          "cpu",
                                          "100m",
                                          "ephemeral-storage",
                                          "128Mi",
                                          "memory",
                                          "128Mi"),
                                      "requests",
                                      Map.of(
                                          "cpu",
                                          "50m",
                                          "ephemeral-storage",
                                          "64Mi",
                                          "memory",
                                          "64Mi")))
                            }),
                        Map.entry("hostPID", true),
                        Map.entry("hostNetwork", true),
                        Map.entry("hostIPC", true),
                        Map.entry(
                            "initContainers",
                            new Object[] {
                              Map.of(
                                  "command",
                                  new Object[] {
                                    "/bin/sh",
                                    "-ec",
                                    "apk add --no-cache bash coreutils && /.sh/bin/runtime-installer.sh"
                                  },
                                  "env",
                                  new Object[] {
                                    Map.of(
                                        "name",
                                        "CONTAINERD_CONFIG_FILE",
                                        "value",
                                        "/var/lib/rancher/rke2/agent/etc/containerd/config.toml"),
                                    Map.of("name", "DAEMONLESS_EXEC_MODE", "value", "pod"),
                                    Map.of(
                                        "name",
                                        "DAEMONLESS_HOST_SCRIPT_ROOT",
                                        "value",
                                        "/srv/host/k8s-daemonset.d/runtime/flox-runtime"),
                                    Map.of(
                                        "name",
                                        "CONTAINERD_ADDRESS",
                                        "value",
                                        "/run/k3s/containerd/containerd.sock"),
                                    Map.of("name", "SCRIPT_MOUNT_DIR", "value", "/.sh"),
                                    Map.of(
                                        "name",
                                        "SCRIPT_POLICY_ROOT",
                                        "value",
                                        "/runtime-daemonset"),
                                    Map.of(
                                        "name", "BUILD_ASSETS_DIR", "value", "/.sh/build-assets"),
                                    Map.of("name", "HOST_ROOT", "value", "/host-root")
                                  },
                                  "image",
                                  "alpine:3.20",
                                  "imagePullPolicy",
                                  "IfNotPresent",
                                  "name",
                                  "init",
                                  "securityContext",
                                  Map.of("privileged", true, "runAsGroup", 0, "runAsUser", 0),
                                  "volumeMounts",
                                  new Object[] {
                                    Map.of(
                                        "mountPath",
                                        "/.sh",
                                        "name",
                                        "runtime-installer-assets",
                                        "readOnly",
                                        true),
                                    Map.of(
                                        "mountPath",
                                        "/runtime-daemonset",
                                        "name",
                                        "runtime-daemonset-script-policy",
                                        "readOnly",
                                        true),
                                    Map.of("mountPath", "/host-root", "name", "host-root")
                                  })
                            }),
                        Map.entry("nodeSelector", Map.of("flox.dev/enabled", "true")),
                        Map.entry("restartPolicy", "Always"),
                        Map.entry("serviceAccountName", "flox-runtime-installer"),
                        Map.entry("tolerations", new Object[] {Map.of("operator", "Exists")}),
                        Map.entry(
                            "volumes",
                            new Object[] {
                              Map.of(
                                  "configMap",
                                  Map.of(
                                      "defaultMode",
                                      493,
                                      "items",
                                      floxRuntimeAssets.installerVolumeItems(),
                                      "name",
                                      RuntimeLayerRefs.FLOX_RUNTIME_INSTALLER_ASSETS_CONFIGMAP
                                          .name()),
                                  "name",
                                  "runtime-installer-assets"),
                              Map.of(
                                  "configMap",
                                  Map.of(
                                      "defaultMode",
                                      493,
                                      "items",
                                      runtimeDaemonsetScriptPolicyAssets.volumeItems(),
                                      "name",
                                      RuntimeLayerRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP.name()),
                                  "name",
                                  "runtime-daemonset-script-policy"),
                              Map.of(
                                  "hostPath",
                                  Map.of("path", "/", "type", "Directory"),
                                  "name",
                                  "host-root")
                            }))),
                "updateStrategy",
                Map.of("rollingUpdate", Map.of("maxUnavailable", 1), "type", "RollingUpdate"))));
  }
}
