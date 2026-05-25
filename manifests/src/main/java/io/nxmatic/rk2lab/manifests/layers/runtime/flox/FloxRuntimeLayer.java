// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime.flox;

import io.nxmatic.rk2lab.manifests.layers.cluster.ClusterLayerRefs;
import io.nxmatic.rk2lab.manifests.layers.common.KptMetadata;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestSynthesisContext;
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
    //
    // Installer assets used to ride a single ConfigMap, but the aggregate
    // payload (NRI archive + scripts + locks + env trees) is well over
    // Kubernetes' per-object 1 MiB limit. The DaemonSet now mounts the
    // assets via hostPath; seed-bootstrap materializes the same content tree
    // to /srv/host/k8s-daemonset.d/runtime/flox-runtime/installer-assets/
    // before the DaemonSet starts (FloxRuntimeAssets.writeInstallerAssetTree).
    ApiObject envConfigMap = createFloxEnvConfigMap();
    ApiObject serviceAccount = createServiceAccount();
    createInstallerDaemonSet(envConfigMap, serviceAccount);
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
      final ApiObject envConfigMap, final ApiObject serviceAccount) {
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
                                        true),
                                    Map.of(
                                        "mountPath", "/nix", "name", "host-nix", "readOnly", true),
                                    Map.of("mountPath", "/var/run/nri", "name", "nri-socket"),
                                    Map.of(
                                        "mountPath",
                                        "/srv/host/k8s-daemonset.d",
                                        "name",
                                        "flox-runtime-assets",
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
                                    // Copy build inputs to the per-node mutable
                                    // workspace, preserving any locks the master
                                    // already wrote (`cp -n`). Then run the
                                    // installer from the workspace — that's where
                                    // `nix build` and `flox activate` will write
                                    // their lock files.
                                    "apk add --no-cache bash coreutils"
                                        + " && mkdir -p /runtime-workspace"
                                        + " && cp -an /.sh/. /runtime-workspace/"
                                        + " && /runtime-workspace/bin/runtime-installer.sh"
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
                                        "/var/run/k8s-daemonset.d/runtime/flox-runtime"),
                                    Map.of(
                                        "name",
                                        "CONTAINERD_ADDRESS",
                                        "value",
                                        "/run/k3s/containerd/containerd.sock"),
                                    Map.of(
                                        "name", "SCRIPT_MOUNT_DIR", "value", "/runtime-workspace"),
                                    Map.of(
                                        "name",
                                        "SCRIPT_POLICY_ROOT",
                                        "value",
                                        "/runtime-daemonset"),
                                    Map.of(
                                        "name",
                                        "BUILD_ASSETS_DIR",
                                        "value",
                                        "/runtime-workspace/build-assets"),
                                    Map.of("name", "HOST_ROOT", "value", "/host-root"),
                                    Map.of(
                                        "name",
                                        "RKE2LAB_POLICY_DEBUG_NRI_PLUGINS_FLOX_ENABLED",
                                        "value",
                                        ManifestSynthesisContext.current()
                                                .floxDebugPolicy()
                                                .enabled()
                                            ? "true"
                                            : "false")
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
                                    // /.sh = build-derived inputs from seed-bootstrap.
                                    // Read-only: seed-bootstrap owns this path
                                    // and the init container only reads from it.
                                    Map.of(
                                        "mountPath",
                                        "/.sh",
                                        "name",
                                        "runtime-installer-assets",
                                        "readOnly",
                                        true),
                                    // /runtime-workspace = per-node mutable workspace
                                    // at /var/run/k8s-daemonset.d/... on the host.
                                    // Init container copies /.sh/* here, then `nix
                                    // build` and `flox activate` write flake.lock
                                    // and per-env manifest.lock here. Per-node, not
                                    // shared across nodes; survives pod restarts;
                                    // wiped on host reboot.
                                    Map.of(
                                        "mountPath",
                                        "/runtime-workspace",
                                        "name",
                                        "runtime-installer-workspace"),
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
                              // Build-derived inputs from seed-bootstrap.
                              // Read-only on the pod side: this path is owned
                              // by the apply-time materializer
                              // (FloxRuntimeAssets.writeInstallerAssetTree).
                              Map.of(
                                  "hostPath",
                                  Map.of(
                                      "path",
                                      "/srv/host/k8s-daemonset.d/runtime/flox-runtime",
                                      "type",
                                      "Directory"),
                                  "name",
                                  "runtime-installer-assets"),
                              // Per-node mutable workspace at /var/run/... on
                              // the host. The init container copies the
                              // build-derived inputs here, then `nix build` and
                              // `flox activate` write flake.lock and per-env
                              // manifest.lock here. Survives pod restarts,
                              // wiped on host reboot. Cross-node lock sharing
                              // is a future concern (single-master bootstrap
                              // doesn't need it).
                              Map.of(
                                  "hostPath",
                                  Map.of(
                                      "path",
                                      "/var/run/k8s-daemonset.d/runtime/flox-runtime",
                                      "type",
                                      "DirectoryOrCreate"),
                                  "name",
                                  "runtime-installer-workspace"),
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
                                  "host-root"),
                              Map.of(
                                  "hostPath",
                                  Map.of("path", "/nix", "type", "Directory"),
                                  "name",
                                  "host-nix"),
                              Map.of(
                                  "hostPath",
                                  Map.of("path", "/var/run/nri", "type", "DirectoryOrCreate"),
                                  "name",
                                  "nri-socket"),
                              Map.of(
                                  "hostPath",
                                  Map.of("path", "/srv/host/k8s-daemonset.d", "type", "Directory"),
                                  "name",
                                  "flox-runtime-assets")
                            }))),
                "updateStrategy",
                Map.of("rollingUpdate", Map.of("maxUnavailable", 1), "type", "RollingUpdate"))));
  }
}
