// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime;

import io.nxmatic.rk2lab.manifests.layers.common.KptMetadata;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

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
        ApiObject serviceAccount = createServiceAccount(namespace);
        createInstallerDaemonSet(namespace, envConfigMap, installerScriptConfigMap, serviceAccount);
    }

    private ApiObject createNamespace() {
        ApiObject namespace = new ApiObject(
                this,
                "namespace-flox-runtime",
                ApiObjectProps.builder()
                        .apiVersion("v1")
                        .kind("Namespace")
                        .metadata(ApiObjectMetadata.builder()
                                .name("flox-runtime")
                                .annotations(kptMetadata.packageAnnotations(
                                        LAYER_NAME,
                                        PACKAGE_NAME,
                                        "|Namespace|default|flox-runtime"
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
                                .namespace("flox-runtime")
                                .annotations(kptMetadata.packageAnnotations(
                                        LAYER_NAME,
                                        PACKAGE_NAME,
                                        "|ConfigMap|flox-runtime|flox-env",
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
                                .namespace("flox-runtime")
                                .annotations(kptMetadata.packageAnnotations(
                                        LAYER_NAME,
                                        PACKAGE_NAME,
                                        "|ConfigMap|flox-runtime|flox-runtime-installer-script"
                                ))
                                .build())
                        .build()
        );

        configMap.addDependency(namespace);
        configMap.addJsonPatch(JsonPatch.add("/data", Map.of(
                "shim-installer.sh", "#!/usr/bin/env bash\nset -exuo pipefail\n\ninstall_deps() {\n  local attempt=0\n  local max_attempts=${APK_MAX_RETRIES:-5}\n  while true; do\n    attempt=$((attempt + 1))\n    if apk update && apk add --no-cache util-linux >/tmp/apk.log; then\n      return 0\n    fi\n    if [[ ${attempt} -ge ${max_attempts} ]]; then\n      echo \"apk install failed after ${attempt} attempts\" >&2\n      sleep infinity\n    fi\n    sleep $((attempt * 2))\n  done\n}\n\ninstall_deps\n\nnsenter --target 1 --mount --uts --ipc --net --pid -- env \\\n  CONTAINERD_CONFIG_FILE=\"${CONTAINERD_CONFIG_FILE}\" \\\n  bash <<'HOSTSCRIPT'\nset -euxo pipefail\n\n: \"Ensure Nix is available in the host environment for the shim installer script\"\nsource /nix/var/nix/profiles/default/etc/profile.d/nix-daemon.sh\n\nFLOX_BIN=\"$(command -v flox || true)\"\n[[ -x \"${FLOX_BIN}\" ]] || {\n  echo \"Flox CLI not found in host environment\" >&2\n  exit 1\n}\n\nif [[ \"${FLOX_BIN}\" != \"/usr/bin/flox\" ]]; then\n  mkdir -p /usr/bin\n  ln -sf \"${FLOX_BIN}\" /usr/bin/flox\nfi\n\nexport PATH=\"/var/lib/rancher/rke2/bin:/var/lib/rancher/rke2/agent/bin:${PATH}\"\nexport FLOX_NO_TELEMETRY=1\nexport FLOX_NONINTERACTIVE=1\n\nCONFIG_FILE=\"${CONTAINERD_CONFIG_FILE}\"\nCONFIG_DIR=\"$(dirname \"${CONFIG_FILE}\")\"\nCONFIG_TEMPLATE=\"${CONFIG_DIR}/config.toml.tmpl\"\nFLOX_ENV_DIR=\"/var/lib/flox-runtime/containerd-shim\"\nARCH=\"$(uname -m)\"\n\nmkdir -p \"${FLOX_ENV_DIR}\"\nif [[ ! -d \"${FLOX_ENV_DIR}/.flox\" ]]; then\n  (cd \"${FLOX_ENV_DIR}\" && flox init)\nfi\n\nif command -v containerd >/dev/null 2>&1; then\n  CONTAINERD_BIN=\"$(command -v containerd)\"\nelif [[ -x /var/lib/rancher/rke2/bin/containerd ]]; then\n  CONTAINERD_BIN=\"/var/lib/rancher/rke2/bin/containerd\"\nelif [[ -x /var/lib/rancher/rke2/agent/bin/containerd ]]; then\n  CONTAINERD_BIN=\"/var/lib/rancher/rke2/agent/bin/containerd\"\nelse\n  echo \"containerd binary not found\" >&2\n  exit 1\nfi\n\nCONTAINERD_VERSION=\"$(${CONTAINERD_BIN} --version | awk '{print $3}')\"\nCONTAINERD_VERSION=\"${CONTAINERD_VERSION#v}\"\nCONTAINERD_MAJOR=\"${CONTAINERD_VERSION%%.*}\"\nif [[ -z \"${CONTAINERD_MAJOR}\" ]]; then\n  echo \"unable to determine containerd version\" >&2\n  exit 1\nfi\nif [[ \"${CONTAINERD_MAJOR}\" -ge 2 ]]; then\n  SHIM_PKG=\"flox/containerd-shim-flox-2x\"\nelse\n  SHIM_PKG=\"flox/containerd-shim-flox-17\"\nfi\n\nflox install --dir \"${FLOX_ENV_DIR}\" \"${SHIM_PKG}\"\n\n: \"Ensure flox gcroots directory exists\"\nGCROOTS_DIR=\"${NIX_GC_ROOTS:-/nix/var/nix/gcroots}/flox\"\nGCROOTS_LINK=\"${GCROOTS_DIR}/system-profile\"\nmkdir -p \"${GCROOTS_DIR}\"\nif [[ ! -e \"${GCROOTS_LINK}\" ]]; then\n  ln -s /nix/var/nix/profiles/default \"${GCROOTS_LINK}\"\nfi\n\nSHIM_RUN_DIR=\"$(find \"${FLOX_ENV_DIR}/.flox/run\" -maxdepth 1 -name \"${ARCH}-linux.containerd-shim*.run\" -print -quit || true)\"\nif [[ -z \"${SHIM_RUN_DIR}\" ]]; then\n  echo \"unable to locate Flox shim run directory\" >&2\n  exit 1\nfi\nSHIM_PATH=\"$(realpath \"${SHIM_RUN_DIR}\")/bin/containerd-shim-flox-v2\"\nif [[ ! -f \"${SHIM_PATH}\" ]]; then\n  echo \"shim binary missing at ${SHIM_PATH}\" >&2\n  exit 1\nfi\ninstall -D -m 0755 \"${SHIM_PATH}\" /usr/local/bin/containerd-shim-flox-v2\n\nif [[ ! -f \"${CONFIG_TEMPLATE}\" ]]; then\n  cp \"${CONFIG_FILE}\" \"${CONFIG_TEMPLATE}\"\nfi\n\nCONFIG_VERSION=\"$(grep -m1 '^version' \"${CONFIG_FILE}\" | awk -F '=' '{print $2}' | tr -d ' \"')\"\nif [[ \"${CONFIG_VERSION}\" == \"3\" ]]; then\n  RUNTIME_SECTION='plugins.\"io.containerd.cri.v1.runtime\".containerd.runtimes.flox'\nelse\n  RUNTIME_SECTION='plugins.\"io.containerd.grpc.v1.cri\".containerd.runtimes.flox'\nfi\n\nupdate_config() {\n  local target=\"$1\"\n  [[ -f \"${target}\" ]] || return 0\n  local tmp\n  tmp=\"$(mktemp)\"\n  awk '\n    BEGIN {skip=0}\n    /^## Flox runtime shim/ {skip=1; next}\n    /^\\[plugins\\..*containerd\\.runtimes\\.flox/ {skip=1; next}\n    skip && /^\\[/ && $0 !~ /containerd\\.runtimes\\.flox/ {skip=0}\n    skip {next}\n    {print}\n  ' \"${target}\" > \"${tmp}\"\n  mv \"${tmp}\" \"${target}\"\n  cat <<EOF_BLOCK | sed \"s|__RUNTIME_SECTION__|${RUNTIME_SECTION}|\" | sed 's/^          //' >> \"${target}\"\n## Flox runtime shim\n[__RUNTIME_SECTION__]\n  runtime_path = \"/usr/local/bin/containerd-shim-flox-v2\"\n  runtime_type = \"io.containerd.runc.v2\"\n  pod_annotations = [ \"flox.dev/*\" ]\n  container_annotations = [ \"flox.dev/*\" ]\n[__RUNTIME_SECTION__.options]\n  SystemdCgroup = true\nEOF_BLOCK\n}\n\nupdate_config \"${CONFIG_FILE}\"\nupdate_config \"${CONFIG_TEMPLATE}\"\n\nif systemctl is-active rke2-server >/dev/null; then\n  systemctl restart rke2-server\nelif systemctl is-active rke2-agent >/dev/null; then\n  systemctl restart rke2-agent\nelif systemctl is-active containerd >/dev/null; then\n  systemctl restart containerd\nelse\n  echo \"no known service to restart\" >&2\nfi\nHOSTSCRIPT\n"
        )));
        return configMap;
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
                                .namespace("flox-runtime")
                                .annotations(kptMetadata.packageAnnotations(
                                        LAYER_NAME,
                                        PACKAGE_NAME,
                                        "|ServiceAccount|flox-runtime|flox-runtime-installer"
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
                                .namespace("flox-runtime")
                                .annotations(kptMetadata.packageAnnotations(
                                        LAYER_NAME,
                                        PACKAGE_NAME,
                                        "apps|DaemonSet|flox-runtime|flox-runtime-installer"
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
                                                        Map.of("name", "CONTAINERD_CONFIG_FILE", "value", "/var/lib/rancher/rke2/agent/etc/containerd/config.toml"),
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
