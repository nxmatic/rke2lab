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

public final class FloxContainerdShimLayer extends Construct {

  public static final String LEGACY_PATH_PREFIX = "runtime/flox-containerd-shim/";

  private static final String NAMESPACE = "rke2lab-system";

  private static final String LAYER_NAME = "runtime";

  private static final String PACKAGE_NAME = "flox-containerd-shim";

  private final KptMetadata kptMetadata = new KptMetadata();

  public FloxContainerdShimLayer(final Construct scope, final String id) {
    super(scope, id);

    ApiObject namespace = createNamespace();
    createRuntimeClass();
    ApiObject envConfigMap = createFloxEnvConfigMap(namespace);
    ApiObject installerAssetsConfigMap = createInstallerAssetsConfigMap(namespace);
    ApiObject serviceAccount = createServiceAccount(namespace);
    createInstallerDaemonSet(namespace, envConfigMap, installerAssetsConfigMap, serviceAccount);
  }

  private ApiObject createNamespace() {
    ApiObject namespace =
        new ApiObject(
            this,
            "namespace-flox-containerd-shim",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Namespace")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(NAMESPACE)
                        .annotations(
                            kptMetadata.packageAnnotations(
                                LAYER_NAME, PACKAGE_NAME, "|Namespace|default|" + NAMESPACE))
                        .labels(Map.of("flox.dev/component", "runtime"))
                        .build())
                .build());
    return namespace;
  }

  private void createRuntimeClass() {
    ApiObject runtimeClass =
        new ApiObject(
            this,
            "runtimeclass-flox",
            ApiObjectProps.builder()
                .apiVersion("node.k8s.io/v1")
                .kind("RuntimeClass")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("flox")
                        .annotations(
                            kptMetadata.packageAnnotations(
                                LAYER_NAME, PACKAGE_NAME, "node.k8s.io|RuntimeClass|default|flox"))
                        .build())
                .build());

    runtimeClass.addJsonPatch(
        JsonPatch.add("/handler", "flox"),
        JsonPatch.add("/scheduling", Map.of("nodeSelector", Map.of("flox.dev/enabled", "true"))));
  }

  private ApiObject createFloxEnvConfigMap(final ApiObject namespace) {
    ApiObject configMap =
        new ApiObject(
            this,
            "configmap-flox-env",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("flox-env")
                        .namespace(NAMESPACE)
                        .annotations(
                            kptMetadata.packageAnnotations(
                                LAYER_NAME,
                                PACKAGE_NAME,
                                "|ConfigMap|" + NAMESPACE + "|flox-env",
                                Map.of(
                                    "replicator.v1.mittwald.de/replicate-to", "headscale-system")))
                        .labels(Map.of("app.kubernetes.io/replicated", "true"))
                        .build())
                .build());

    configMap.addDependency(namespace);
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

  private ApiObject createInstallerAssetsConfigMap(final ApiObject namespace) {
    ApiObject configMap =
        new ApiObject(
            this,
            "configmap-flox-runtime-installer-assets",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("flox-runtime-installer-assets")
                        .namespace(NAMESPACE)
                        .annotations(
                            kptMetadata.packageAnnotations(
                                LAYER_NAME,
                                PACKAGE_NAME,
                                "|ConfigMap|" + NAMESPACE + "|flox-runtime-installer-assets"))
                        .build())
                .build());

    configMap.addDependency(namespace);
    configMap.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "shim-installer.sh",
                readResource("/runtime/flox-containerd-shim/shim-installer.sh"),
                "shim-installer-host.sh",
                readResource("/runtime/flox-containerd-shim/shim-installer-host.sh"),
                "shim-installer-entrypoint.sh",
                readResource("/runtime/flox-containerd-shim/shim-installer-entrypoint.sh"),
                "flox-shim-build.sh",
                readResource("/runtime/flox-containerd-shim/flox-shim-build.sh"),
                "flox-shim-build.yaml",
                readResource("/runtime/flox-containerd-shim/flox-shim-build.yaml"),
                "mesh-headplane-flake.nix",
                readResource("/runtime/flox-containerd-shim/mesh/headplane/flake.nix"),
                "networking-kdns-flake.nix",
                readResource("/runtime/flox-containerd-shim/networking/kdns/flake.nix"))));
    return configMap;
  }

  private String readResource(final String resourcePath) {
    final InputStream input = FloxContainerdShimLayer.class.getResourceAsStream(resourcePath);
    if (input == null) {
      throw new IllegalStateException("Missing flox-containerd-shim resource: " + resourcePath);
    }

    try {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Failed reading flox-containerd-shim resource: " + resourcePath, ex);
    }
  }

  private ApiObject createServiceAccount(final ApiObject namespace) {
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
                        .namespace(NAMESPACE)
                        .annotations(
                            kptMetadata.packageAnnotations(
                                LAYER_NAME,
                                PACKAGE_NAME,
                                "|ServiceAccount|" + NAMESPACE + "|flox-runtime-installer"))
                        .build())
                .build());
    serviceAccount.addDependency(namespace);
    return serviceAccount;
  }

  private void createInstallerDaemonSet(
      final ApiObject namespace,
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
                        .namespace(NAMESPACE)
                        .annotations(
                            kptMetadata.packageAnnotations(
                                LAYER_NAME,
                                PACKAGE_NAME,
                                "apps|DaemonSet|" + NAMESPACE + "|flox-runtime-installer"))
                        .labels(
                            Map.of(
                                "app.kubernetes.io/component",
                                "runtime-shim",
                                "app.kubernetes.io/name",
                                "flox-runtime-installer"))
                        .build())
                .build());

    daemonSet.addDependency(namespace);
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
                                    "/var/lib/rancher/rke2/agent/etc/containerd/config-v3.toml"),
                                Map.of(
                                    "name",
                                    "CONTAINERD_ADDRESS",
                                    "value",
                                    "/run/k3s/containerd/containerd.sock"),
                                Map.of("name", "SCRIPT_MOUNT_DIR", "value", "/.sh"),
                                Map.of("name", "SCRIPT_POLICY_DIR", "value", "/runtime-daemonset"),
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
                                  new Object[] {
                                    Map.of("key", "shim-installer.sh", "path", "shim-installer.sh"),
                                    Map.of(
                                        "key",
                                        "shim-installer-host.sh",
                                        "path",
                                        "shim-installer-host.sh"),
                                    Map.of(
                                        "key",
                                        "shim-installer-entrypoint.sh",
                                        "path",
                                        "shim-installer-entrypoint.sh"),
                                    Map.of(
                                        "key",
                                        "flox-shim-build.sh",
                                        "path",
                                        "build-assets/flox-shim-build.sh"),
                                    Map.of(
                                        "key",
                                        "flox-shim-build.yaml",
                                        "path",
                                        "build-assets/flox-shim-build.yaml"),
                                    Map.of(
                                        "key",
                                        "mesh-headplane-flake.nix",
                                        "path",
                                        "build-assets/mesh/headplane/flake.nix"),
                                    Map.of(
                                        "key",
                                        "networking-kdns-flake.nix",
                                        "path",
                                        "build-assets/networking/kdns/flake.nix")
                                  },
                                  "name",
                                  "flox-runtime-installer-assets"),
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
                                        "daemonset-logging.sh")
                                  },
                                  "name",
                                  RuntimeDaemonsetLayer.SCRIPT_POLICY_CONFIGMAP_NAME),
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
