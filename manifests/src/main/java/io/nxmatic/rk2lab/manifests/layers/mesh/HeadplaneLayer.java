// @codebase
package io.nxmatic.rk2lab.manifests.layers.mesh;

import io.nxmatic.rk2lab.manifests.layers.common.ManifestSynthesisContext;
import io.nxmatic.rk2lab.manifests.layers.common.profiles.FloxDebugPolicy;
import io.nxmatic.rk2lab.manifests.layers.common.profiles.FloxShellSidecarProfile;
import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import io.nxmatic.rk2lab.manifests.layers.common.registry.ManifestUnitReferenceRegistry;
import io.nxmatic.rk2lab.manifests.layers.runtime.RuntimeLayerRefs;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class HeadplaneLayer extends Construct {

  private static final String HEADSCALE_NAMESPACE = MeshLayerRefs.MESH_SYSTEM_NAMESPACE.name();

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("mesh", "headplane");

  private final ManifestUnitReferenceRegistry registry;
  private final String floxImage;

  public HeadplaneLayer(final Construct scope, final String id) {
    this(scope, id, null);
  }

  public HeadplaneLayer(
      final Construct scope, final String id, final ManifestUnitReferenceRegistry registry) {
    super(scope, id);
    this.registry = registry;
    // Prod carrier image is always busybox/alpine baseline; debug shell lives in the
    // FloxShellSidecarProfile sidecar wired into createDeployment.
    this.floxImage = ManifestSynthesisContext.current().floxDebugPolicy().prodImage();

    ApiObject serviceAccount = createServiceAccount();
    ApiObject envConfigMap = createEnvConfigMap();
    ApiObject syncScriptConfigMap = createSyncScriptConfigMap();
    ApiObject configTemplateSecret = createConfigTemplateSecret();
    ApiObject secretsSecret = createSecretsSecret();
    ApiObject agentAuthSecret = createAgentAuthSecret();
    ApiObject pvc = createDataPvc();
    ApiObject agentSyncRole = createAgentSyncRole();
    ApiObject agentSyncRoleBinding = createAgentSyncRoleBinding(serviceAccount, agentSyncRole);
    ApiObject k8sIntegrationRole = createK8sIntegrationRole();
    ApiObject k8sIntegrationRoleBinding =
        createK8sIntegrationRoleBinding(serviceAccount, k8sIntegrationRole);
    ApiObject syncJob =
        createAgentSyncJob(
            serviceAccount,
            envConfigMap,
            syncScriptConfigMap,
            configTemplateSecret,
            agentSyncRoleBinding);
    ApiObject deployment =
        createDeployment(
            serviceAccount,
            envConfigMap,
            secretsSecret,
            agentAuthSecret,
            pvc,
            k8sIntegrationRoleBinding,
            syncJob);
    ApiObject service = createService(deployment);
    createIngress(service);
  }

  private ApiObject createServiceAccount() {
    return new ApiObject(
        this,
        "serviceaccount-headplane",
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("ServiceAccount")
            .metadata(
                ApiObjectMetadata.builder()
                    .name("headplane")
                    .namespace(HEADSCALE_NAMESPACE)
                    .annotations(
                        packageProfile.packageAnnotations(
                            "|ServiceAccount|${headscale-namespace}|headplane"))
                    .build())
            .build());
  }

  private ApiObject createEnvConfigMap() {
    ApiObject configMap =
        new ApiObject(
            this,
            "configmap-" + MeshLayerRefs.HEADPLANE_ENV_CONFIGMAP.name(),
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(MeshLayerRefs.HEADPLANE_ENV_CONFIGMAP.name())
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ConfigMap|${headscale-namespace}|"
                                    + MeshLayerRefs.HEADPLANE_ENV_CONFIGMAP.name()))
                        .build())
                .build());

    configMap.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "HEADPLANE_BASE_URL",
                "http://headplane." + HEADSCALE_NAMESPACE + ".svc.cluster.local:3000",
                "HEADPLANE_COOKIE_SECURE",
                "false",
                "HEADPLANE_NAMESPACE",
                HEADSCALE_NAMESPACE,
                "HEADSCALE_NAMESPACE",
                HEADSCALE_NAMESPACE,
                "HEADSCALE_SERVICE_URL",
                "http://headscale." + HEADSCALE_NAMESPACE + ".svc.cluster.local:8080")));

    if (registry != null) {
      registry.publish(MeshLayerRefs.HEADPLANE_ENV_CONFIGMAP, configMap);
    }

    return configMap;
  }

  private ApiObject createSyncScriptConfigMap() {
    ApiObject configMap =
        new ApiObject(
            this,
            "configmap-headplane-agent-sync-script",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-agent-sync-script")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ConfigMap|${headscale-namespace}|headplane-agent-sync-script"))
                        .build())
                .build());

    configMap.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "agent-sync.sh",
                "#!/bin/sh\n"
                    + "set -exuo pipefail\n\n"
                    + "# Read authkey from mounted secret volume (base64 encoded)\n"
                    + "AUTHKEY=$(cat /secrets/headscale/authkey | base64 -d)\n"
                    + "kubectl create secret generic headplane-agent-auth \\\n"
                    + "  -n \"${HEADPLANE_NAMESPACE}\" \\\n"
                    + "  --from-literal=preauthkey=\"${AUTHKEY}\" \\\n"
                    + "  --dry-run=client -o yaml | kubectl apply -f -\n\n"
                    + "yq eval '.server.base_url = strenv(HEADPLANE_BASE_URL) |\n"
                    + "  .server.cookie_secure = (strenv(HEADPLANE_COOKIE_SECURE) | downcase == \"true\") |\n"
                    + "  .headscale.url = strenv(HEADSCALE_SERVICE_URL)' \\\n"
                    + "  /etc/headplane-template/config.yaml > /tmp/config.$$.yaml\n"
                    + "kubectl create secret generic headplane-config \\\n"
                    + "  -n \"${HEADPLANE_NAMESPACE}\" \\\n"
                    + "  --from-file=config.yaml=/tmp/config.$$.yaml \\\n"
                    + "  --dry-run=client -o yaml | kubectl apply -f -")));

    return configMap;
  }

  private ApiObject createConfigTemplateSecret() {
    ApiObject secret =
        new ApiObject(
            this,
            "secret-headplane-config-tmpl",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-config-tmpl")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|${headscale-namespace}|headplane-config-tmpl"))
                        .build())
                .build());

    secret.addJsonPatch(
        JsonPatch.add(
            "/stringData",
            Map.of(
                "config.yaml",
                "server:\n"
                    + "  host: \"0.0.0.0\"\n"
                    + "  port: 3000\n"
                    + "  base_url: \"${HEADPLANE_BASE_URL}\"\n"
                    + "  cookie_secret_path: \"/etc/headplane/secrets/cookie_secret\"\n"
                    + "  cookie_secure: ${HEADPLANE_COOKIE_SECURE}\n"
                    + "  data_path: \"/var/lib/headplane\"\n\n"
                    + "headscale:\n"
                    + "  url: \"${HEADSCALE_SERVICE_URL}\"\n"
                    + "  config_path: \"/etc/headscale/config.yaml\"\n"
                    + "  dns_records_path: \"/var/lib/headscale/extra_records.json\"\n"
                    + "  config_strict: false\n\n"
                    + "integration:\n"
                    + "  agent:\n"
                    + "    enabled: true\n"
                    + "    pre_authkey_path: \"/etc/headplane/agent/preauthkey\"\n"
                    + "  kubernetes:\n"
                    + "    enabled: false\n"
                    + "    validate_manifest: false\n"
                    + "    pod_name: \"headscale\"")),
        JsonPatch.add("/type", "Opaque"));

    return secret;
  }

  private ApiObject createSecretsSecret() {
    ApiObject secret =
        new ApiObject(
            this,
            "secret-" + MeshLayerRefs.HEADPLANE_SECRETS_SECRET.name(),
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(MeshLayerRefs.HEADPLANE_SECRETS_SECRET.name())
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|${headscale-namespace}|"
                                    + MeshLayerRefs.HEADPLANE_SECRETS_SECRET.name()))
                        .build())
                .build());

    secret.addJsonPatch(
        JsonPatch.add("/stringData", Map.of("cookie_secret", "0123456789abcdef0123456789abcdef")),
        JsonPatch.add("/type", "Opaque"));

    if (registry != null) {
      registry.publish(MeshLayerRefs.HEADPLANE_SECRETS_SECRET, secret);
    }

    return secret;
  }

  private ApiObject createAgentAuthSecret() {
    ApiObject secret =
        new ApiObject(
            this,
            "secret-headplane-agent-auth",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-agent-auth")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|${headscale-namespace}|headplane-agent-auth"))
                        .build())
                .build());

    secret.addJsonPatch(
        JsonPatch.add("/stringData", Map.of("preauthkey", "REPLACE_WITH_HEADSCALE_PREAUTH_KEY")),
        JsonPatch.add("/type", "Opaque"));

    return secret;
  }

  private ApiObject createDataPvc() {
    ApiObject pvc =
        new ApiObject(
            this,
            "persistentvolumeclaim-headplane-data",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("PersistentVolumeClaim")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-data")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|PersistentVolumeClaim|${headscale-namespace}|headplane-data"))
                        .build())
                .build());

    pvc.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "accessModes",
                List.of("ReadWriteOnce"),
                "resources",
                Map.of("requests", Map.of("storage", "128Mi")))));

    return pvc;
  }

  private ApiObject createAgentSyncRole() {
    ApiObject role =
        new ApiObject(
            this,
            "role-headplane-agent-sync",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("Role")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-agent-sync")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|Role|${headscale-namespace}|headplane-agent-sync"))
                        .build())
                .build());

    role.addJsonPatch(
        JsonPatch.add(
            "/rules",
            List.of(
                Map.of(
                    "apiGroups",
                    List.of(""),
                    "resources",
                    List.of("secrets"),
                    "verbs",
                    List.of("get", "create", "update", "patch")))));

    return role;
  }

  private ApiObject createAgentSyncRoleBinding(
      final ApiObject serviceAccount, final ApiObject role) {
    ApiObject roleBinding =
        new ApiObject(
            this,
            "rolebinding-headplane-agent-sync",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("RoleBinding")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-agent-sync")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|RoleBinding|${headscale-namespace}|headplane-agent-sync"))
                        .build())
                .build());

    roleBinding.addDependency(serviceAccount);
    roleBinding.addDependency(role);

    roleBinding.addJsonPatch(
        JsonPatch.add(
            "/roleRef",
            Map.of(
                "apiGroup",
                "rbac.authorization.k8s.io",
                "kind",
                "Role",
                "name",
                "headplane-agent-sync")),
        JsonPatch.add(
            "/subjects",
            List.of(
                Map.of(
                    "kind",
                    "ServiceAccount",
                    "name",
                    "headplane",
                    "namespace",
                    HEADSCALE_NAMESPACE))));

    return roleBinding;
  }

  private ApiObject createK8sIntegrationRole() {
    ApiObject role =
        new ApiObject(
            this,
            "role-headplane-k8s-integration",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("Role")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-k8s-integration")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|Role|${headscale-namespace}|headplane-k8s-integration"))
                        .build())
                .build());

    role.addJsonPatch(
        JsonPatch.add(
            "/rules",
            List.of(
                Map.of(
                    "apiGroups",
                    List.of(""),
                    "resources",
                    List.of("pods", "pods/exec"),
                    "verbs",
                    List.of("get", "list", "create")),
                Map.of(
                    "apiGroups",
                    List.of("apps"),
                    "resources",
                    List.of("deployments"),
                    "verbs",
                    List.of("get", "list")),
                Map.of(
                    "apiGroups",
                    List.of(""),
                    "resources",
                    List.of("secrets"),
                    "verbs",
                    List.of("get")))));

    return role;
  }

  private ApiObject createK8sIntegrationRoleBinding(
      final ApiObject serviceAccount, final ApiObject role) {
    ApiObject roleBinding =
        new ApiObject(
            this,
            "rolebinding-headplane-k8s-integration",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("RoleBinding")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-k8s-integration")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|RoleBinding|${headscale-namespace}|headplane-k8s-integration"))
                        .build())
                .build());

    roleBinding.addDependency(serviceAccount);
    roleBinding.addDependency(role);

    roleBinding.addJsonPatch(
        JsonPatch.add(
            "/roleRef",
            Map.of(
                "apiGroup",
                "rbac.authorization.k8s.io",
                "kind",
                "Role",
                "name",
                "headplane-k8s-integration")),
        JsonPatch.add(
            "/subjects",
            List.of(
                Map.of(
                    "kind",
                    "ServiceAccount",
                    "name",
                    "headplane",
                    "namespace",
                    HEADSCALE_NAMESPACE))));

    return roleBinding;
  }

  private ApiObject createAgentSyncJob(
      final ApiObject serviceAccount,
      final ApiObject envConfigMap,
      final ApiObject syncScriptConfigMap,
      final ApiObject configTemplateSecret,
      final ApiObject roleBinding) {
    ApiObject job =
        new ApiObject(
            this,
            "job-headplane-agent-sync",
            ApiObjectProps.builder()
                .apiVersion("batch/v1")
                .kind("Job")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-agent-sync")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "batch|Job|${headscale-namespace}|headplane-agent-sync"))
                        .build())
                .build());

    job.addDependency(serviceAccount);
    job.addDependency(envConfigMap);
    job.addDependency(syncScriptConfigMap);
    job.addDependency(configTemplateSecret);
    job.addDependency(roleBinding);

    job.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "template",
                Map.of(
                    "metadata",
                    Map.of(
                        "annotations",
                        packageProfile.templateAnnotations(
                            Map.of("flox.dev/environment.sync", "mesh/headplane"))),
                    "spec",
                    Map.of(
                        "containers",
                        List.of(
                            Map.of(
                                "name",
                                "sync",
                                "image",
                                "alpine:3.20",
                                "command",
                                List.of(
                                    "/bin/sh",
                                    "-c",
                                    "apk add --no-cache yq kubectl && /scripts/agent-sync.sh"),
                                "envFrom",
                                List.of(
                                    Map.of(
                                        "configMapRef",
                                        Map.of(
                                            "name", MeshLayerRefs.HEADPLANE_ENV_CONFIGMAP.name())),
                                    Map.of(
                                        "configMapRef",
                                        Map.of(
                                            "name", RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.name()))),
                                "resources",
                                Map.of(
                                    "limits",
                                    Map.of(
                                        "cpu",
                                        "200m",
                                        "ephemeral-storage",
                                        "256Mi",
                                        "memory",
                                        "128Mi"),
                                    "requests",
                                    Map.of(
                                        "cpu",
                                        "50m",
                                        "ephemeral-storage",
                                        "128Mi",
                                        "memory",
                                        "64Mi")),
                                "volumeMounts",
                                List.of(
                                    Map.of(
                                        "mountPath",
                                        "/scripts",
                                        "name",
                                        "agent-sync-script",
                                        "readOnly",
                                        true),
                                    Map.of(
                                        "mountPath",
                                        "/etc/headplane-template/config.yaml",
                                        "name",
                                        "config-template",
                                        "readOnly",
                                        true,
                                        "subPath",
                                        "config.yaml"),
                                    Map.of(
                                        "mountPath",
                                        "/secrets/headscale",
                                        "name",
                                        "headscale-auth",
                                        "readOnly",
                                        true)))),
                        "restartPolicy",
                        "OnFailure",
                        "serviceAccountName",
                        "headplane",
                        "volumes",
                        List.of(
                            Map.of(
                                "name",
                                "agent-sync-script",
                                "configMap",
                                Map.of("defaultMode", 493, "name", "headplane-agent-sync-script")),
                            Map.of(
                                "name",
                                "config-template",
                                "secret",
                                Map.of("secretName", "headplane-config-tmpl")),
                            Map.of(
                                "name",
                                "headscale-auth",
                                "secret",
                                Map.of(
                                    "optional",
                                    false,
                                    "secretName",
                                    MeshLayerRefs.HEADSCALE_CLIENT_AUTH_SECRET.name()))))),
                "ttlSecondsAfterFinished",
                300)));

    return job;
  }

  private ApiObject createDeployment(
      final ApiObject serviceAccount,
      final ApiObject envConfigMap,
      final ApiObject secretsSecret,
      final ApiObject agentAuthSecret,
      final ApiObject pvc,
      final ApiObject roleBinding,
      final ApiObject syncJob) {
    ApiObject deployment =
        new ApiObject(
            this,
            "deployment-headplane",
            ApiObjectProps.builder()
                .apiVersion("apps/v1")
                .kind("Deployment")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane")
                        .namespace(HEADSCALE_NAMESPACE)
                        .labels(Map.of("app", "headplane"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "apps|Deployment|${headscale-namespace}|headplane"))
                        .build())
                .build());

    deployment.addDependency(serviceAccount);
    deployment.addDependency(envConfigMap);
    deployment.addDependency(secretsSecret);
    deployment.addDependency(agentAuthSecret);
    deployment.addDependency(pvc);
    deployment.addDependency(roleBinding);
    deployment.addDependency(syncJob);
    if (registry != null) {
      deployment.addDependency(registry.require(MeshLayerRefs.HEADSCALE_CONFIG_CONFIGMAP));
    }

    final FloxDebugPolicy debugPolicy = ManifestSynthesisContext.current().floxDebugPolicy();
    final FloxShellSidecarProfile shellSidecar =
        new FloxShellSidecarProfile(
            debugPolicy,
            debugPolicy.meshEnabled(),
            "headplane",
            "/root",
            "mesh/headplane-debug",
            "0",
            "0");

    final List<Map<String, Object>> headplaneMounts = new ArrayList<>();
    headplaneMounts.add(
        Map.of(
            "mountPath", "/etc/headplane/config.yaml",
            "name", "config",
            "subPath", "config.yaml"));
    headplaneMounts.add(
        Map.of(
            "mountPath", "/etc/headplane/secrets",
            "name", "secrets",
            "readOnly", true));
    headplaneMounts.add(
        Map.of(
            "mountPath", "/etc/headplane/agent",
            "name", "agent",
            "readOnly", true));
    headplaneMounts.add(Map.of("mountPath", "/var/lib/headplane", "name", "data"));
    headplaneMounts.add(
        Map.of(
            "mountPath", "/etc/headscale",
            "name", "headscale-config",
            "readOnly", true));
    headplaneMounts.add(Map.of("mountPath", "/usr/libexec/headplane", "name", "shared-bin"));
    headplaneMounts.addAll(shellSidecar.extraProdMounts());

    final LinkedHashMap<String, Object> headplaneContainer = new LinkedHashMap<>();
    headplaneContainer.put("name", "headplane");
    headplaneContainer.put("image", floxImage);
    headplaneContainer.put("command", List.of("headplane"));
    headplaneContainer.put("args", List.of("serve"));
    headplaneContainer.put(
        "env",
        List.of(
            Map.of("name", "HEADPLANE_LOAD_ENV_OVERRIDES", "value", "true"),
            Map.of(
                "name",
                "HEADPLANE_INTEGRATION__KUBERNETES__POD_NAME",
                "valueFrom",
                Map.of("fieldRef", Map.of("fieldPath", "metadata.name")))));
    headplaneContainer.put(
        "envFrom",
        List.of(
            Map.of("configMapRef", Map.of("name", MeshLayerRefs.HEADPLANE_ENV_CONFIGMAP.name())),
            Map.of("configMapRef", Map.of("name", RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.name()))));
    headplaneContainer.put(
        "livenessProbe",
        Map.of(
            "exec",
            Map.of("command", List.of("/usr/libexec/headplane/healthcheck")),
            "failureThreshold",
            3,
            "initialDelaySeconds",
            30,
            "periodSeconds",
            10,
            "timeoutSeconds",
            5));
    headplaneContainer.put(
        "readinessProbe",
        Map.of(
            "exec",
            Map.of("command", List.of("/usr/libexec/headplane/healthcheck")),
            "failureThreshold",
            2,
            "initialDelaySeconds",
            10,
            "periodSeconds",
            5,
            "timeoutSeconds",
            3));
    headplaneContainer.put("ports", List.of(Map.of("containerPort", 3000, "name", "http")));
    headplaneContainer.put(
        "resources",
        Map.of(
            "limits",
            Map.of(
                "cpu", "500m",
                "ephemeral-storage", "512Mi",
                "memory", "512Mi"),
            "requests",
            Map.of(
                "cpu", "100m",
                "ephemeral-storage", "256Mi",
                "memory", "128Mi")));
    headplaneContainer.put("volumeMounts", List.copyOf(headplaneMounts));

    final List<Object> containers = new ArrayList<>();
    containers.add(headplaneContainer);
    shellSidecar.sidecar(headplaneMounts).ifPresent(containers::add);

    final List<Object> volumes = new ArrayList<>();
    volumes.add(Map.of("name", "config", "secret", Map.of("secretName", "headplane-config")));
    volumes.add(
        Map.of(
            "name",
            "secrets",
            "secret",
            Map.of("secretName", MeshLayerRefs.HEADPLANE_SECRETS_SECRET.name())));
    volumes.add(Map.of("name", "agent", "secret", Map.of("secretName", "headplane-agent-auth")));
    volumes.add(
        Map.of("name", "data", "persistentVolumeClaim", Map.of("claimName", "headplane-data")));
    volumes.add(
        Map.of(
            "name",
            "headscale-config",
            "configMap",
            Map.of("name", MeshLayerRefs.HEADSCALE_CONFIG_CONFIGMAP.name())));
    volumes.add(Map.of("name", "shared-bin", "emptyDir", Map.of()));
    volumes.addAll(shellSidecar.extraVolumes());

    // When debug is on, the prod container runs the debug env so its node has --enable-source-maps
    // + --inspect; the shell sidecar (with SYS_PTRACE + shareProcessNamespace) can attach to the
    // running node process from the shared PID namespace.
    final LinkedHashMap<String, String> annotations = new LinkedHashMap<>();
    annotations.put(
        "flox.dev/environment.headplane",
        debugPolicy.resolveMeshEnvironment("mesh/headplane", "mesh/headplane-debug"));
    annotations.putAll(shellSidecar.sidecarAnnotations());

    deployment.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "replicas",
                1,
                "selector",
                Map.of("matchLabels", Map.of("app", "headplane")),
                "template",
                Map.of(
                    "metadata",
                    Map.of(
                        "annotations",
                        packageProfile.templateAnnotations(Map.copyOf(annotations)),
                        "labels",
                        Map.of("app", "headplane")),
                    "spec",
                    Map.of(
                        "containers",
                        List.copyOf(containers),
                        "initContainers",
                        List.of(
                            Map.of(
                                "name",
                                "setup-agent",
                                "image",
                                floxImage,
                                "command",
                                List.of(
                                    "sh",
                                    "-c",
                                    "mkdir -p /usr/libexec/headplane\n"
                                        + "ln -sf /.flox/run/aarch64-linux.headplane.run/bin/hp_agent /usr/libexec/headplane/agent\n"
                                        + "ln -sf /.flox/run/aarch64-linux.headplane.run/bin/hp_healthcheck /usr/libexec/headplane/healthcheck\n"),
                                "volumeMounts",
                                List.of(
                                    Map.of(
                                        "mountPath",
                                        "/usr/libexec/headplane",
                                        "name",
                                        "shared-bin")))),
                        "serviceAccountName",
                        "headplane",
                        "shareProcessNamespace",
                        true,
                        "volumes",
                        List.copyOf(volumes))))));

    return deployment;
  }

  private ApiObject createService(final ApiObject deployment) {
    ApiObject service =
        new ApiObject(
            this,
            "service-headplane",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Service")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane")
                        .namespace(HEADSCALE_NAMESPACE)
                        .labels(Map.of("app", "headplane"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Service|${headscale-namespace}|headplane"))
                        .build())
                .build());
    service.addDependency(deployment);

    service.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "ports",
                List.of(Map.of("name", "http", "port", 3000, "targetPort", "http")),
                "selector",
                Map.of("app", "headplane"),
                "type",
                "ClusterIP")));

    return service;
  }

  private void createIngress(final ApiObject service) {
    ApiObject ingress =
        new ApiObject(
            this,
            "ingress-headplane",
            ApiObjectProps.builder()
                .apiVersion("networking.k8s.io/v1")
                .kind("Ingress")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "networking.k8s.io|Ingress|${headscale-namespace}|headplane",
                                Map.of(
                                    "io.cilium/lb-ipam-ips",
                                    "${cluster-lan-headplane-inetaddr}",
                                    "kubernetes.io/ingress.class",
                                    "cilium",
                                    "lab42.io/mdns.enabled",
                                    "true",
                                    "lab42.io/mdns.host",
                                    "headplane",
                                    "lab42.io/mdns.name",
                                    "headplane")))
                        .build())
                .build());
    ingress.addDependency(service);

    ingress.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "ingressClassName",
                "cilium",
                "rules",
                List.of(
                    Map.of(
                        "host",
                        "headplane.local",
                        "http",
                        Map.of(
                            "paths",
                            List.of(
                                Map.of(
                                    "backend",
                                    Map.of(
                                        "service",
                                        Map.of(
                                            "name", "headplane", "port", Map.of("number", 3000))),
                                    "path",
                                    "/admin",
                                    "pathType",
                                    "Prefix"))))))));
  }
}
