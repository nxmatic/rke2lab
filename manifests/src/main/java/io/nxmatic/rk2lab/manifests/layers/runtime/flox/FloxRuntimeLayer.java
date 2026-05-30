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
    // payload (scripts + nri-plugin source tree + env trees + flake) is well
    // over Kubernetes' per-object 1 MiB limit. seed-master materializes
    // the build-derived inputs to /srv/host/k8s-daemonset.d/runtime/flox/
    // (FloxRuntimeAssets.writeInstallerAssetTree); the init container then
    // copies that tree into the per-node mutable workspace at
    // /var/run/k8s-daemonset.d/runtime/flox/ where nix build and flox
    // activate write locks (overlayfs lower=NFS isn't supported, so the
    // workspace must live on local fs).
    ApiObject envConfigMap = createFloxEnvConfigMap();
    ApiObject dynamicPluginConfigMap = createDynamicPluginConfigMap();
    ApiObject serviceAccount = createServiceAccount();
    createInstallerDaemonSet(envConfigMap, dynamicPluginConfigMap, serviceAccount);
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

  private ApiObject createDynamicPluginConfigMap() {
    // Dynamic hot-reload ConfigMap for NRI plugin updates.
    // Initially empty; populated at runtime via kubectl apply by the dev tool.
    // Expected keys when populated:
    //   nri-plugin.tar.b64 — base64-encoded tar archive of plugin binary + hooks
    //   nri-plugin.manifest.json — JSON manifest with archive/entry checksums
    ApiObject configMap =
        new ApiObject(
            this,
            "configmap-flox-nri-plugin-dyn",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("flox-nri-plugin-dyn")
                        .namespace(RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.namespaceName())
                        .annotations(
                            kptMetadata.packageAnnotations(
                                LAYER_NAME,
                                PACKAGE_NAME,
                                "|ConfigMap|"
                                    + RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.namespaceName()
                                    + "|flox-nri-plugin-dyn"))
                        .build())
                .build());

    if (registry != null) {
      configMap.addDependency(registry.require(ClusterLayerRefs.RUNTIME_SYSTEM_NAMESPACE));
    }

    configMap.addJsonPatch(JsonPatch.add("/data", Map.of()));
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
      final ApiObject dynamicPluginConfigMap,
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
    daemonSet.addDependency(dynamicPluginConfigMap);
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
                                  new Object[] {"/bin/sh", "/.sh/bin/flox-nri-plugin-run.sh"},
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
                                    // The NRI plugin running here resolves
                                    // env directories under
                                    // /var/run/k8s-daemonset.d/.../environment.d/
                                    // (the per-node workspace path) before
                                    // emitting the overlay hook. The hook
                                    // resolves the same path on the host;
                                    // mounting the workspace here at the
                                    // exact host path keeps the existence
                                    // check meaningful.
                                    Map.of(
                                        "mountPath",
                                        "/var/run/k8s-daemonset.d/runtime/flox",
                                        "name",
                                        "runtime-installer-workspace",
                                        "readOnly",
                                        true,
                                        "subPath",
                                        "runtime/flox"),
                                    Map.of(
                                        "mountPath", "/nix", "name", "host-nix", "readOnly", true),
                                    Map.of("mountPath", "/var/run/nri", "name", "nri-socket")
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
                                          "64Mi"))),
                              // Reconciler sidecar: watches dynamic ConfigMap for hot-reload
                              Map.of(
                                  "command",
                                  new Object[] {
                                    "/bin/sh",
                                    "-c",
                                    // Source flox-runtime shared library and call
                                    // flox_runtime::reconcile. The library handles both
                                    // bootstrap-complete verification and the watch loop.
                                    "apk add --no-cache bash inotify-tools"
                                        + " && export SCRIPT_MOUNT_DIR=/var/run/k8s-daemonset.d/runtime/flox"
                                        + " && export DAEMONSET_POLICY_SCRIPT_MOUNT_DIR=/.sh-daemonset"
                                        + " && export DYNAMIC_PLUGIN_MOUNT_DIR=/dynamic-plugin"
                                        + " && bash -c '. ${SCRIPT_MOUNT_DIR}/bin/flox-runtime-lib.sh && flox_runtime::reconcile'"
                                  },
                                  "env",
                                  new Object[] {
                                    Map.of("name", "DAEMONSET_EXEC_MODE", "value", "pod"),
                                    Map.of(
                                        "name",
                                        "DAEMONSET_HOST_SCRIPT_ROOT",
                                        "value",
                                        "/var/run/k8s-daemonset.d/runtime/flox")
                                  },
                                  "image",
                                  "alpine:3.20",
                                  "imagePullPolicy",
                                  "IfNotPresent",
                                  "name",
                                  "reconciler",
                                  "securityContext",
                                  Map.of("privileged", true, "runAsGroup", 0, "runAsUser", 0),
                                  "volumeMounts",
                                  new Object[] {
                                    Map.of(
                                        "mountPath",
                                        "/dynamic-plugin",
                                        "name",
                                        "dynamic-plugin-configmap",
                                        "readOnly",
                                        true),
                                    Map.of(
                                        "mountPath",
                                        "/.sh-daemonset",
                                        "name",
                                        "runtime-daemonset-script-policy",
                                        "readOnly",
                                        true),
                                    Map.of(
                                        "mountPath",
                                        "/var/run/k8s-daemonset.d/runtime/flox",
                                        "name",
                                        "runtime-installer-workspace",
                                        "subPath",
                                        "runtime/flox"),
                                    Map.of("mountPath", "/host-root", "name", "host-root")
                                  },
                                  "resources",
                                  Map.of(
                                      "limits",
                                      Map.of(
                                          "cpu",
                                          "50m",
                                          "ephemeral-storage",
                                          "64Mi",
                                          "memory",
                                          "64Mi"),
                                      "requests",
                                      Map.of(
                                          "cpu",
                                          "10m",
                                          "ephemeral-storage",
                                          "32Mi",
                                          "memory",
                                          "32Mi")))
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
                                    // Overwrite the workspace's build-derived
                                    // inputs from /.sh/ each pod start so a
                                    // newly-deployed flox-nri-plugin-installer.sh
                                    // (or any other build asset) actually replaces
                                    // a stale copy from a prior run.
                                    //
                                    // For each top-level build path, wipe and
                                    // re-copy. Sibling paths the runtime owns
                                    // (flake.lock, .sh.d/, log/, locks under
                                    // environment.d/<env>/.flox/env/) survive because
                                    // we only touch paths that exist in /.sh/.
                                    //
                                    // environment.d/ is special: it contains both
                                    // build inputs (manifest.toml) and runtime
                                    // state (manifest.lock). We rsync-style
                                    // copy via `cp -af`, which overwrites
                                    // matching files but leaves untouched
                                    // anything not present in /.sh/ — so locks
                                    // survive while manifest.toml updates.
                                    // git is required because nix's path-flake
                                    // fetcher uses git's index/tree to hash
                                    // the flake source. Without a git tree
                                    // here, nix walks every file (including
                                    // logs, locks, and kube-cache writes) and
                                    // the path narHash drifts on every
                                    // activation — `flake.cc:37` assertion
                                    // failures during realise.
                                    "apk add --no-cache bash coreutils git"
                                        + " && mkdir -p /var/run/k8s-daemonset.d/runtime/flox"
                                        + " && cp -af --no-preserve=ownership /.sh/."
                                        + "       /var/run/k8s-daemonset.d/runtime/flox/"
                                        + " && cd /var/run/k8s-daemonset.d/runtime/flox"
                                        + " && git init -q -b main"
                                        + " && git config user.email installer@rke2lab"
                                        + " && git config user.name rke2lab-installer"
                                        + " && git add -A"
                                        + " && git commit -q -m 'flox-runtime baseline' --allow-empty"
                                        + " && /var/run/k8s-daemonset.d/runtime/flox/bin/flox-nri-plugin-installer.sh"
                                  },
                                  "env",
                                  new Object[] {
                                    Map.of(
                                        "name",
                                        "CONTAINERD_CONFIG_FILE",
                                        "value",
                                        "/var/lib/rancher/rke2/agent/etc/containerd/config.toml"),
                                    Map.of("name", "DAEMONSET_EXEC_MODE", "value", "pod"),
                                    Map.of(
                                        "name",
                                        "DAEMONSET_HOST_SCRIPT_ROOT",
                                        "value",
                                        "/var/run/k8s-daemonset.d/runtime/flox"),
                                    Map.of(
                                        "name",
                                        "CONTAINERD_ADDRESS",
                                        "value",
                                        "/run/k3s/containerd/containerd.sock"),
                                    Map.of(
                                        "name",
                                        "SCRIPT_MOUNT_DIR",
                                        "value",
                                        "/var/run/k8s-daemonset.d/runtime/flox"),
                                    Map.of("name", "SCRIPT_POLICY_ROOT", "value", "/.sh-daemonset"),
                                    Map.of(
                                        "name",
                                        "BUILD_ASSETS_DIR",
                                        "value",
                                        "/var/run/k8s-daemonset.d/runtime/flox/build-assets"),
                                    Map.of("name", "HOST_ROOT", "value", "/host-root"),
                                    Map.of(
                                        "name",
                                        "RKE2LAB_POLICY_DEBUG_NRI_PLUGINS_FLOX_ENABLED",
                                        "value",
                                        ManifestSynthesisContext.current()
                                                .floxDebugPolicy()
                                                .floxNriPluginEnabled()
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
                                    // /.sh = build-derived inputs from seed-master.
                                    // Read-only: seed-master owns this path
                                    // and the init container only reads from it.
                                    Map.of(
                                        "mountPath",
                                        "/.sh",
                                        "name",
                                        "runtime-installer-assets",
                                        "readOnly",
                                        true),
                                    // Per-node mutable workspace, mounted at the
                                    // exact host path so paths inside the pod and
                                    // outside it agree (nix build, flox activate,
                                    // and the host-mode trampoline all reference
                                    // /var/run/k8s-daemonset.d/runtime/flox).
                                    // Init container copies /.sh/* here, then nix
                                    // build and flox activate write flake.lock and
                                    // per-env manifest.lock here. Survives pod
                                    // restarts; wiped on host reboot.
                                    Map.of(
                                        "mountPath",
                                        "/var/run/k8s-daemonset.d/runtime/flox",
                                        "name",
                                        "runtime-installer-workspace",
                                        "subPath",
                                        "runtime/flox"),
                                    Map.of(
                                        "mountPath",
                                        "/.sh-daemonset",
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
                              // Build-derived inputs from seed-master.
                              // Read-only on the pod side: this path is owned
                              // by the apply-time materializer
                              // (FloxRuntimeAssets.writeInstallerAssetTree).
                              Map.of(
                                  "hostPath",
                                  Map.of(
                                      "path",
                                      "/srv/host/k8s-daemonset.d/runtime/flox",
                                      "type",
                                      "Directory"),
                                  "name",
                                  "runtime-installer-assets"),
                              // Per-node mutable workspace. We mount the parent
                              // /var/run/k8s-daemonset.d/ here and let each
                              // volumeMount pick the runtime/flox/ subPath
                              // — that keeps the in-pod path identical to the host
                              // path while ensuring this pod can't see sibling
                              // daemonset workspaces under the parent.
                              // The init container copies the build-derived inputs
                              // here, then `nix build` and `flox activate` write
                              // flake.lock and per-env manifest.lock here. Survives
                              // pod restarts, wiped on host reboot. Cross-node lock
                              // sharing is a future concern (single-master
                              // bootstrap doesn't need it).
                              Map.of(
                                  "hostPath",
                                  Map.of(
                                      "path",
                                      "/var/run/k8s-daemonset.d",
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
                                  "configMap",
                                  Map.of(
                                      "defaultMode",
                                      420,
                                      "name",
                                      "flox-nri-plugin-dyn",
                                      "optional",
                                      true),
                                  "name",
                                  "dynamic-plugin-configmap"),
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
                                  "nri-socket")
                            }))),
                "updateStrategy",
                Map.of("rollingUpdate", Map.of("maxUnavailable", 1), "type", "RollingUpdate"))));
  }
}
