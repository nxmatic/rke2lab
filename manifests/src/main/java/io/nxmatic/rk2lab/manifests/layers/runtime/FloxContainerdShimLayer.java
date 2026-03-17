// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime;

import io.nxmatic.rk2lab.manifests.layers.common.KptMetadata;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class FloxContainerdShimLayer extends Construct {

    public static final String LEGACY_PATH_PREFIX = "runtime/flox-containerd-shim/";

    private static final String LAYER_NAME = "runtime";
    private static final String PACKAGE_NAME = "flox-containerd-shim";

        private final KptMetadata kptMetadata = new KptMetadata();

    public FloxContainerdShimLayer(final Construct scope, final String id) {
        super(scope, id);

        ApiObject namespace = createNamespace();
        createRuntimeClass();
        ApiObject envConfigMap = createFloxEnvConfigMap(namespace);
        ApiObject installerScriptConfigMap = createInstallerScriptConfigMap(namespace);
                ApiObject buildAssetsConfigMap = createBuildAssetsConfigMap(namespace);
        ApiObject serviceAccount = createServiceAccount(namespace);
                createInstallerDaemonSet(namespace, envConfigMap, installerScriptConfigMap, buildAssetsConfigMap,
                                serviceAccount);
    }

    private ApiObject createNamespace() {
        ApiObject namespace = new ApiObject(
                this,
                "namespace-flox-containerd-shim",
                ApiObjectProps.builder()
                        .apiVersion("v1")
                        .kind("Namespace")
                        .metadata(ApiObjectMetadata.builder()
                                .name("flox-containerd-shim")
                                .annotations(kptMetadata.packageAnnotations(
                                        LAYER_NAME,
                                        PACKAGE_NAME,
                                        "|Namespace|default|flox-containerd-shim"
                                ))
                                .labels(Map.of("flox.dev/component", "runtime"))
                                .build())
                        .build()
        );
        return namespace;
    }

    private void createRuntimeClass() {
        ApiObject runtimeClass = new ApiObject(
                this,
                "runtimeclass-flox",
                ApiObjectProps.builder()
                        .apiVersion("node.k8s.io/v1")
                        .kind("RuntimeClass")
                        .metadata(ApiObjectMetadata.builder()
                                .name("flox")
                                .annotations(kptMetadata.packageAnnotations(
                                        LAYER_NAME,
                                        PACKAGE_NAME,
                                        "node.k8s.io|RuntimeClass|default|flox"
                                ))
                                .build())
                        .build()
        );

        runtimeClass.addJsonPatch(
                JsonPatch.add("/handler", "flox"),
                JsonPatch.add("/scheduling", Map.of(
                        "nodeSelector", Map.of("flox.dev/enabled", "true")
                ))
        );
    }

    private ApiObject createFloxEnvConfigMap(final ApiObject namespace) {
        ApiObject configMap = new ApiObject(
                this,
                "configmap-flox-env",
                ApiObjectProps.builder()
                        .apiVersion("v1")
                        .kind("ConfigMap")
                        .metadata(ApiObjectMetadata.builder()
                                .name("flox-env")
                                .namespace("flox-containerd-shim")
                                .annotations(kptMetadata.packageAnnotations(
                                        LAYER_NAME,
                                        PACKAGE_NAME,
                                        "|ConfigMap|flox-containerd-shim|flox-env",
                                        Map.of("replicator.v1.mittwald.de/replicate-to", "headscale-system")
                                ))
                                .labels(Map.of("app.kubernetes.io/replicated", "true"))
                                .build())
                        .build()
        );

        configMap.addDependency(namespace);
        configMap.addJsonPatch(JsonPatch.add("/data", Map.of(
                "FLOX_DISABLE_METRICS", "true",
                "FLOX_NO_TELEMETRY", "1",
                "FLOX_NONINTERACTIVE", "1"
        )));
        return configMap;
    }

    private ApiObject createInstallerScriptConfigMap(final ApiObject namespace) {
        ApiObject configMap = new ApiObject(
                this,
                "configmap-flox-runtime-installer-script",
                ApiObjectProps.builder()
                        .apiVersion("v1")
                        .kind("ConfigMap")
                        .metadata(ApiObjectMetadata.builder()
                                .name("flox-runtime-installer-script")
                                .namespace("flox-containerd-shim")
                                .annotations(kptMetadata.packageAnnotations(
                                        LAYER_NAME,
                                        PACKAGE_NAME,
                                        "|ConfigMap|flox-containerd-shim|flox-runtime-installer-script"
                                ))
                                .build())
                        .build()
        );

        configMap.addDependency(namespace);
        configMap.addJsonPatch(JsonPatch.add("/data", Map.of(
                                                                "shim-installer.sh", readResource("/runtime/flox-containerd-shim/shim-installer.sh"),
                                                                "shim-installer-host.sh", readResource("/runtime/flox-containerd-shim/shim-installer-host.sh")
        )));
        return configMap;
    }

    private ApiObject createBuildAssetsConfigMap(final ApiObject namespace) {
        ApiObject configMap = new ApiObject(
                this,
                "configmap-flox-container-build-assets",
                ApiObjectProps.builder()
                        .apiVersion("v1")
                        .kind("ConfigMap")
                        .metadata(ApiObjectMetadata.builder()
                                .name("flox-container-build-assets")
                                .namespace("flox-containerd-shim")
                                .annotations(kptMetadata.packageAnnotations(
                                        LAYER_NAME,
                                        PACKAGE_NAME,
                                        "|ConfigMap|flox-containerd-shim|flox-container-build-assets"
                                ))
                                .build())
                        .build()
        );

        configMap.addDependency(namespace);
        configMap.addJsonPatch(JsonPatch.add("/data", Map.of(
                "rke2lab-flox-build.sh", readResource("/runtime/flox-containerd-shim/rke2lab-flox-build.sh"),
                "rke2lab-flox-build.yaml", readResource("/runtime/flox-containerd-shim/rke2lab-flox-build.yaml")
        )));
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
                        throw new IllegalStateException("Failed reading flox-containerd-shim resource: " + resourcePath, ex);
                }
        }

    private ApiObject createServiceAccount(final ApiObject namespace) {
        ApiObject serviceAccount = new ApiObject(
                this,
                "serviceaccount-flox-runtime-installer",
                ApiObjectProps.builder()
                        .apiVersion("v1")
                        .kind("ServiceAccount")
                        .metadata(ApiObjectMetadata.builder()
                                .name("flox-runtime-installer")
                                .namespace("flox-containerd-shim")
                                .annotations(kptMetadata.packageAnnotations(
                                        LAYER_NAME,
                                        PACKAGE_NAME,
                                        "|ServiceAccount|flox-containerd-shim|flox-runtime-installer"
                                ))
                                .build())
                        .build()
        );
        serviceAccount.addDependency(namespace);
        return serviceAccount;
    }

    private void createInstallerDaemonSet(
            final ApiObject namespace,
            final ApiObject envConfigMap,
            final ApiObject installerScriptConfigMap,
            final ApiObject buildAssetsConfigMap,
            final ApiObject serviceAccount
    ) {
        ApiObject daemonSet = new ApiObject(
                this,
                "daemonset-flox-runtime-installer",
                ApiObjectProps.builder()
                        .apiVersion("apps/v1")
                        .kind("DaemonSet")
                        .metadata(ApiObjectMetadata.builder()
                                .name("flox-runtime-installer")
                                .namespace("flox-containerd-shim")
                                .annotations(kptMetadata.packageAnnotations(
                                        LAYER_NAME,
                                        PACKAGE_NAME,
                                        "apps|DaemonSet|flox-containerd-shim|flox-runtime-installer"
                                ))
                                .labels(Map.of(
                                        "app.kubernetes.io/component", "runtime-shim",
                                        "app.kubernetes.io/name", "flox-runtime-installer"
                                ))
                                .build())
                        .build()
        );

        daemonSet.addDependency(namespace);
        daemonSet.addDependency(envConfigMap);
        daemonSet.addDependency(installerScriptConfigMap);
        daemonSet.addDependency(buildAssetsConfigMap);
        daemonSet.addDependency(serviceAccount);

        daemonSet.addJsonPatch(JsonPatch.add("/spec", Map.of(
                "selector", Map.of(
                        "matchLabels", Map.of("app.kubernetes.io/name", "flox-runtime-installer")
                ),
                "template", Map.of(
                        "metadata", Map.of(
                                "annotations", Map.of(
                                        "kpt.dev/package-layer", LAYER_NAME,
                                        "kpt.dev/package-name", PACKAGE_NAME
                                ),
                                "labels", Map.of(
                                        "app.kubernetes.io/component", "runtime-shim",
                                        "app.kubernetes.io/name", "flox-runtime-installer"
                                )
                        ),
                        "spec", Map.of(
                                "automountServiceAccountToken", false,
                                "containers", new Object[]{
                                        Map.of(
                                                "image", "registry.k8s.io/pause:3.10",
                                                "name", "pause",
                                                "resources", Map.of(
                                                        "limits", Map.of(
                                                                "cpu", "10m",
                                                                "ephemeral-storage", "64Mi",
                                                                "memory", "64Mi"
                                                        ),
                                                        "requests", Map.of(
                                                                "cpu", "5m",
                                                                "ephemeral-storage", "32Mi",
                                                                "memory", "32Mi"
                                                        )
                                                )
                                        )
                                },
                                "hostPID", true,
                                "initContainers", new Object[]{
                                        Map.of(
                                                "command", new Object[]{
                                                        "/bin/sh",
                                                        "-c",
                                                        "set -euxo pipefail\n: \"Install bash and coreutils (GNU env) for script compatibility\"\napk add --no-cache bash coreutils\n\n: \"Run the shim installer script\"\n/scripts/shim-installer.sh\n"
                                                },
                                                "env", new Object[]{
                                                        Map.of("name", "CONTAINERD_CONFIG_FILE", "value", "/var/lib/rancher/rke2/agent/etc/containerd/config-v3.toml"),
                                                        Map.of("name", "CONTAINERD_ADDRESS", "value", "/run/k3s/containerd/containerd.sock")
                                                },
                                                "image", "alpine:3.20",
                                                "imagePullPolicy", "IfNotPresent",
                                                "name", "shim-installer",
                                                "securityContext", Map.of(
                                                        "privileged", true,
                                                        "runAsGroup", 0,
                                                        "runAsUser", 0
                                                ),
                                                "volumeMounts", new Object[]{
                                                        Map.of(
                                                                "mountPath", "/scripts",
                                                                "name", "runtime-installer-script",
                                                                "readOnly", true
                                                        ),
                                                        Map.of(
                                                                "mountPath", "/build-assets",
                                                                "name", "flox-container-build-assets",
                                                                "readOnly", true
                                                        )
                                                }
                                        )
                                },
                                "nodeSelector", Map.of("flox.dev/enabled", "true"),
                                "restartPolicy", "Always",
                                "serviceAccountName", "flox-runtime-installer",
                                "tolerations", new Object[]{
                                        Map.of("operator", "Exists")
                                },
                                "volumes", new Object[]{
                                        Map.of(
                                                "configMap", Map.of(
                                                        "defaultMode", 493,
                                                        "name", "flox-runtime-installer-script"
                                                ),
                                                "name", "runtime-installer-script"
                                        ),
                                        Map.of(
                                                "configMap", Map.of(
                                                        "defaultMode", 493,
                                                        "name", "flox-container-build-assets"
                                                ),
                                                "name", "flox-container-build-assets"
                                        )
                                }
                        )
                ),
                "updateStrategy", Map.of(
                        "rollingUpdate", Map.of("maxUnavailable", 1),
                        "type", "RollingUpdate"
                )
        )));
    }
}
