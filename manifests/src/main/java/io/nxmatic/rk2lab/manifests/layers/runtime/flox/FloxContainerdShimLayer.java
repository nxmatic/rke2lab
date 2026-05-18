// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime.flox;

import io.nxmatic.rk2lab.manifests.layers.cluster.ClusterLayerRefs;
import io.nxmatic.rk2lab.manifests.layers.common.KptMetadata;
import io.nxmatic.rk2lab.manifests.layers.common.registry.ManifestUnitReferenceRegistry;
import io.nxmatic.rk2lab.manifests.layers.mesh.MeshLayerRefs;
import io.nxmatic.rk2lab.manifests.layers.runtime.RuntimeLayerRefs;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class FloxContainerdShimLayer extends Construct {

  public static final String LEGACY_PATH_PREFIX = "runtime/flox-containerd-shim/";

  private static final String LAYER_NAME = "runtime";

  private static final String PACKAGE_NAME = "flox-containerd-shim";

  private final KptMetadata kptMetadata = new KptMetadata();

  private final ManifestUnitReferenceRegistry registry;

  public FloxContainerdShimLayer(final Construct scope, final String id) {
    this(scope, id, null);
  }

  public FloxContainerdShimLayer(
      final Construct scope, final String id, final ManifestUnitReferenceRegistry registry) {
    super(scope, id);
    this.registry = registry;

    createRuntimeClass("flox", "flox");
    createRuntimeClass("flox-delve", "flox-delve");
    ApiObject envConfigMap = createFloxEnvConfigMap();
    ApiObject installerAssetsConfigMap = createInstallerAssetsConfigMap();
    ApiObject serviceAccount = createServiceAccount();
    createInstallerDaemonSet(envConfigMap, installerAssetsConfigMap, serviceAccount);
  }

  private void createRuntimeClass(final String runtimeClassName, final String handlerName) {
    ApiObject runtimeClass =
        new ApiObject(
            this,
            "runtimeclass-" + runtimeClassName,
            ApiObjectProps.builder()
                .apiVersion("node.k8s.io/v1")
                .kind("RuntimeClass")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(runtimeClassName)
                        .annotations(
                            kptMetadata.packageAnnotations(
                                LAYER_NAME,
                                PACKAGE_NAME,
                                "node.k8s.io|RuntimeClass|default|" + runtimeClassName))
                        .build())
                .build());

    runtimeClass.addJsonPatch(
        JsonPatch.add("/handler", handlerName),
        JsonPatch.add("/scheduling", Map.of("nodeSelector", Map.of("flox.dev/enabled", "true"))));
  }

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

    configMap.addJsonPatch(
        JsonPatch.add("/data", FloxContainerdShimAssets.installerConfigMapData()));
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
                    Map.of(
                        "automountServiceAccountToken",
                        false,
                        "containers",
                        new Object[] {
                          Map.of(
                              "image",
                              "registry.k8s.io/pause:3.10",
                              "name",
                              "pause",
                              "resources",
                              Map.of(
                                  "limits",
                                  Map.of(
                                      "cpu", "10m", "ephemeral-storage", "64Mi", "memory", "64Mi"),
                                  "requests",
                                  Map.of(
                                      "cpu", "5m", "ephemeral-storage", "32Mi", "memory", "32Mi")))
                        },
                        "hostPID",
                        true,
                        "initContainers",
                        new Object[] {
                          Map.of(
                              "command",
                              new Object[] {"/.sh/shim-installer-entrypoint.sh"},
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
                                    "/srv/host/k8s-daemonset.d/runtime/flox-containerd-shim"),
                                Map.of(
                                    "name",
                                    "CONTAINERD_ADDRESS",
                                    "value",
                                    "/run/k3s/containerd/containerd.sock"),
                                Map.of("name", "SCRIPT_MOUNT_DIR", "value", "/.sh"),
                                Map.of("name", "SCRIPT_POLICY_ROOT", "value", "/runtime-daemonset"),
                                Map.of("name", "BUILD_ASSETS_DIR", "value", "/.sh/build-assets"),
                                Map.of("name", "HOST_ROOT", "value", "/host-root")
                              },
                              "image",
                              "alpine:3.20",
                              "imagePullPolicy",
                              "IfNotPresent",
                              "name",
                              "shim-installer",
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
                        },
                        "nodeSelector",
                        Map.of("flox.dev/enabled", "true"),
                        "restartPolicy",
                        "Always",
                        "serviceAccountName",
                        "flox-runtime-installer",
                        "tolerations",
                        new Object[] {Map.of("operator", "Exists")},
                        "volumes",
                        new Object[] {
                          Map.of(
                              "configMap",
                              Map.of(
                                  "defaultMode",
                                  493,
                                  "items",
                                  FloxContainerdShimAssets.installerVolumeItems(),
                                  "name",
                                  RuntimeLayerRefs.FLOX_RUNTIME_INSTALLER_ASSETS_CONFIGMAP.name()),
                              "name",
                              "runtime-installer-assets"),
                          Map.of(
                              "configMap",
                              Map.of(
                                  "defaultMode",
                                  493,
                                  "items",
                                  new Object[] {
                                    Map.of(
                                        "key",
                                        "daemonset-logging.sh",
                                        "path",
                                        ".sh.d/daemonset-logging.sh"),
                                    Map.of(
                                        "key",
                                        "daemonless-host-asset-materializer.sh",
                                        "path",
                                        ".sh.d/daemonless-host-asset-materializer.sh"),
                                    Map.of(
                                        "key",
                                        "daemonless-host-shell-policy.sh",
                                        "path",
                                        ".sh.d/daemonless-host-shell-policy.sh"),
                                    Map.of(
                                        "key",
                                        "daemonless-trampoline.sh",
                                        "path",
                                        ".sh.d/daemonless-trampoline.sh")
                                  },
                                  "name",
                                  RuntimeLayerRefs.DAEMONSET_SCRIPT_POLICY_CONFIGMAP.name()),
                              "name",
                              "runtime-daemonset-script-policy"),
                          Map.of(
                              "hostPath",
                              Map.of("path", "/", "type", "Directory"),
                              "name",
                              "host-root")
                        })),
                "updateStrategy",
                Map.of("rollingUpdate", Map.of("maxUnavailable", 1), "type", "RollingUpdate"))));
  }
}
