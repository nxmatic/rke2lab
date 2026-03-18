// @codebase
package io.nxmatic.rk2lab.manifests.layers.mesh;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import io.nxmatic.rk2lab.manifests.layers.runtime.RuntimeLayerRefs;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class HeadplaneLayer extends Construct {

  public static final String LEGACY_PATH_PREFIX = "mesh/headplane/";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("mesh", "headplane");

  public HeadplaneLayer(final Construct scope, final String id) {
    super(scope, id);

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
                    .namespace("headscale-system")
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
            "configmap-headplane-env",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-env")
                        .namespace("headscale-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ConfigMap|${headscale-namespace}|headplane-env"))
                        .build())
                .build());

    configMap.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "HEADPLANE_BASE_URL",
                "http://headplane.headscale-system.svc.cluster.local:3000",
                "HEADPLANE_COOKIE_SECURE",
                "false",
                "HEADPLANE_NAMESPACE",
                "headscale-system",
                "HEADSCALE_NAMESPACE",
                "headscale-system",
                "HEADSCALE_SERVICE_URL",
                "http://headscale.headscale-system.svc.cluster.local:8080")));

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
                        .namespace("headscale-system")
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
                        .namespace("headscale-system")
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
            "secret-headplane-secrets",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-secrets")
                        .namespace("headscale-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|${headscale-namespace}|headplane-secrets"))
                        .build())
                .build());

    secret.addJsonPatch(
        JsonPatch.add("/stringData", Map.of("cookie_secret", "0123456789abcdef0123456789abcdef")),
        JsonPatch.add("/type", "Opaque"));

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
                        .namespace("headscale-system")
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
                        .namespace("headscale-system")
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
                        .namespace("headscale-system")
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
                        .namespace("headscale-system")
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
                    "headscale-system"))));

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
                        .namespace("headscale-system")
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
                        .namespace("headscale-system")
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
                    "headscale-system"))));

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
                        .namespace("headscale-system")
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
                            Map.of("flox.dev/environment", "nxmatic/headplane"))),
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
                                    Map.of("configMapRef", Map.of("name", "headplane-env")),
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
                        "runtimeClassName",
                        "flox",
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
                                    "optional", false, "secretName", "headscale-client-auth"))))),
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
                        .namespace("headscale-system")
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
                        packageProfile.templateAnnotations(
                            Map.of("flox.dev/environment", "nxmatic/headplane")),
                        "labels",
                        Map.of("app", "headplane")),
                    "spec",
                    Map.of(
                        "containers",
                        List.of(
                            Map.ofEntries(
                                Map.entry("name", "headplane"),
                                Map.entry("image", "flox/empty:1.0.0"),
                                Map.entry("command", List.of("headplane")),
                                Map.entry("args", List.of("serve")),
                                Map.entry(
                                    "env",
                                    List.of(
                                        Map.of(
                                            "name",
                                            "HEADPLANE_LOAD_ENV_OVERRIDES",
                                            "value",
                                            "true"),
                                        Map.of(
                                            "name",
                                            "HEADPLANE_INTEGRATION__KUBERNETES__POD_NAME",
                                            "valueFrom",
                                            Map.of(
                                                "fieldRef",
                                                Map.of("fieldPath", "metadata.name"))))),
                                Map.entry(
                                    "envFrom",
                                    List.of(
                                        Map.of("configMapRef", Map.of("name", "headplane-env")),
                                        Map.of(
                                            "configMapRef",
                                            Map.of(
                                                "name",
                                                RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.name())))),
                                Map.entry(
                                    "livenessProbe",
                                    Map.of(
                                        "exec",
                                        Map.of(
                                            "command",
                                            List.of("/usr/libexec/headplane/healthcheck")),
                                        "failureThreshold",
                                        3,
                                        "initialDelaySeconds",
                                        30,
                                        "periodSeconds",
                                        10,
                                        "timeoutSeconds",
                                        5)),
                                Map.entry(
                                    "readinessProbe",
                                    Map.of(
                                        "exec",
                                        Map.of(
                                            "command",
                                            List.of("/usr/libexec/headplane/healthcheck")),
                                        "failureThreshold",
                                        2,
                                        "initialDelaySeconds",
                                        10,
                                        "periodSeconds",
                                        5,
                                        "timeoutSeconds",
                                        3)),
                                Map.entry(
                                    "ports",
                                    List.of(Map.of("containerPort", 3000, "name", "http"))),
                                Map.entry(
                                    "resources",
                                    Map.of(
                                        "limits",
                                        Map.of(
                                            "cpu",
                                            "500m",
                                            "ephemeral-storage",
                                            "512Mi",
                                            "memory",
                                            "512Mi"),
                                        "requests",
                                        Map.of(
                                            "cpu",
                                            "100m",
                                            "ephemeral-storage",
                                            "256Mi",
                                            "memory",
                                            "128Mi"))),
                                Map.entry(
                                    "volumeMounts",
                                    List.of(
                                        Map.of(
                                            "mountPath",
                                            "/etc/headplane/config.yaml",
                                            "name",
                                            "config",
                                            "subPath",
                                            "config.yaml"),
                                        Map.of(
                                            "mountPath",
                                            "/etc/headplane/secrets",
                                            "name",
                                            "secrets",
                                            "readOnly",
                                            true),
                                        Map.of(
                                            "mountPath",
                                            "/etc/headplane/agent",
                                            "name",
                                            "agent",
                                            "readOnly",
                                            true),
                                        Map.of("mountPath", "/var/lib/headplane", "name", "data"),
                                        Map.of(
                                            "mountPath",
                                            "/etc/headscale",
                                            "name",
                                            "headscale-config",
                                            "readOnly",
                                            true),
                                        Map.of(
                                            "mountPath",
                                            "/usr/libexec/headplane",
                                            "name",
                                            "shared-bin"))))),
                        "initContainers",
                        List.of(
                            Map.of(
                                "name",
                                "setup-agent",
                                "image",
                                "flox/empty:1.0.0",
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
                        "runtimeClassName",
                        "flox",
                        "serviceAccountName",
                        "headplane",
                        "shareProcessNamespace",
                        true,
                        "volumes",
                        List.of(
                            Map.of(
                                "name",
                                "config",
                                "secret",
                                Map.of("secretName", "headplane-config")),
                            Map.of(
                                "name",
                                "secrets",
                                "secret",
                                Map.of("secretName", "headplane-secrets")),
                            Map.of(
                                "name",
                                "agent",
                                "secret",
                                Map.of("secretName", "headplane-agent-auth")),
                            Map.of(
                                "name",
                                "data",
                                "persistentVolumeClaim",
                                Map.of("claimName", "headplane-data")),
                            Map.of(
                                "name",
                                "headscale-config",
                                "configMap",
                                Map.of("name", "headscale-config")),
                            Map.of("name", "shared-bin", "emptyDir", Map.of())))))));

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
                        .namespace("headscale-system")
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
                        .namespace("headscale-system")
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
