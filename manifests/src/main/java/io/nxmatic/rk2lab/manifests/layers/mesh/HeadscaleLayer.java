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

public final class HeadscaleLayer extends Construct {

  private static final String HEADSCALE_NAMESPACE = MeshLayerRefs.MESH_SYSTEM_NAMESPACE.name();
  // Prod carrier image is always busybox/alpine baseline; debug shell lives in the
  // FloxShellSidecarProfile sidecar attached to each long-lived pod below.
  private final String floxImage = ManifestSynthesisContext.current().floxDebugPolicy().prodImage();

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("mesh", "headscale");

  private final ManifestUnitReferenceRegistry registry;

  public HeadscaleLayer(final Construct scope, final String id) {
    this(scope, id, null);
  }

  public HeadscaleLayer(
      final Construct scope, final String id, final ManifestUnitReferenceRegistry registry) {
    super(scope, id);
    this.registry = registry;

    ApiObject namespace = resolveNamespace();
    ApiObject saClient = createServiceAccount("headscale-client", namespace);
    ApiObject saBootstrap = createServiceAccount("headscale-bootstrap", namespace);
    ApiObject saGateway = createServiceAccount("headscale-gateway", namespace);

    ApiObject clusterRoleClient = createClusterRoleClient();
    createClusterRoleBindingClient(clusterRoleClient, saClient);

    ApiObject roleBootstrap = createRoleBootstrap(namespace);
    createRoleBindingBootstrap(saBootstrap, roleBootstrap, namespace);

    ApiObject cmFloxEnv = createConfigMapFloxEnv(namespace);
    ApiObject cmHeadscaleConfig = createConfigMapHeadscaleConfig(namespace);
    ApiObject cmConfigInitScript = createConfigMapConfigInitScript(namespace);
    ApiObject cmHeadscaleEnv = createConfigMapHeadscaleEnv(namespace);
    ApiObject cmBootstrapScript = createConfigMapBootstrapScript(namespace);
    ApiObject cmClientScripts = createConfigMapClientScripts(namespace);
    ApiObject cmAcl = createConfigMapAcl(namespace);
    ApiObject cmDerp = createConfigMapDerp(namespace);
    ApiObject cmExtraRecords = createConfigMapExtraRecords(namespace);
    ApiObject cmGatewayScript = createConfigMapGatewayScript(namespace);

    ApiObject l2Policy = createLanPolicy();
    ApiObject deploymentHeadscale =
        createDeploymentHeadscale(
            namespace,
            cmFloxEnv,
            cmHeadscaleConfig,
            cmConfigInitScript,
            cmAcl,
            cmDerp,
            cmExtraRecords,
            l2Policy);
    ApiObject serviceHeadscale = createServiceHeadscale(namespace, deploymentHeadscale);
    ApiObject bootstrapJob =
        createBootstrapJob(
            namespace,
            saBootstrap,
            cmHeadscaleEnv,
            cmFloxEnv,
            cmBootstrapScript,
            deploymentHeadscale);
    createDeploymentGateway(
        namespace, saGateway, cmHeadscaleEnv, cmFloxEnv, cmGatewayScript, bootstrapJob);
    createDaemonsetClient(
        namespace,
        saClient,
        cmHeadscaleEnv,
        cmFloxEnv,
        cmClientScripts,
        bootstrapJob,
        serviceHeadscale);
  }

  private ApiObject resolveNamespace() {
    if (registry != null) {
      return registry.require(MeshLayerRefs.MESH_SYSTEM_NAMESPACE);
    }
    return createNamespace();
  }

  private ApiObject createNamespace() {
    ApiObject namespace =
        new ApiObject(
            this,
            "namespace-" + MeshLayerRefs.MESH_SYSTEM_NAMESPACE.name(),
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Namespace")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Namespace|default|${headscale-namespace}"))
                        .build())
                .build());
    if (registry != null) {
      registry.publish(MeshLayerRefs.MESH_SYSTEM_NAMESPACE, namespace);
    }
    return namespace;
  }

  private ApiObject createServiceAccount(final String name, final ApiObject namespace) {
    ApiObject serviceAccount =
        new ApiObject(
            this,
            "serviceaccount-" + name,
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ServiceAccount")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ServiceAccount|${headscale-namespace}|" + name))
                        .build())
                .build());
    serviceAccount.addDependency(namespace);
    return serviceAccount;
  }

  private ApiObject createClusterRoleClient() {
    ApiObject role =
        new ApiObject(
            this,
            "clusterrole-headscale-client",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRole")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headscale-client")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|ClusterRole|default|headscale-client"))
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
                    List.of("nodes"),
                    "verbs",
                    List.of("get", "list")),
                Map.of(
                    "apiGroups",
                    List.of(""),
                    "resources",
                    List.of("secrets"),
                    "verbs",
                    List.of("get", "list", "watch")),
                Map.of(
                    "apiGroups",
                    List.of("apps"),
                    "resources",
                    List.of("deployments"),
                    "verbs",
                    List.of("get", "list", "watch")))));
    return role;
  }

  private void createClusterRoleBindingClient(
      final ApiObject role, final ApiObject serviceAccount) {
    ApiObject roleBinding =
        new ApiObject(
            this,
            "clusterrolebinding-headscale-client",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRoleBinding")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headscale-client")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|ClusterRoleBinding|default|headscale-client"))
                        .build())
                .build());
    roleBinding.addDependency(role);
    roleBinding.addDependency(serviceAccount);
    roleBinding.addJsonPatch(
        JsonPatch.add(
            "/roleRef",
            Map.of(
                "apiGroup",
                "rbac.authorization.k8s.io",
                "kind",
                "ClusterRole",
                "name",
                "headscale-client")),
        JsonPatch.add(
            "/subjects",
            List.of(
                Map.of(
                    "kind",
                    "ServiceAccount",
                    "name",
                    "headscale-client",
                    "namespace",
                    HEADSCALE_NAMESPACE))));
  }

  private ApiObject createRoleBootstrap(final ApiObject namespace) {
    ApiObject role =
        new ApiObject(
            this,
            "role-headscale-bootstrap",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("Role")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headscale-bootstrap")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|Role|${headscale-namespace}|headscale-bootstrap"))
                        .build())
                .build());
    role.addDependency(namespace);
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
                    List.of("get", "create", "update", "patch")),
                Map.of(
                    "apiGroups",
                    List.of(""),
                    "resources",
                    List.of("pods"),
                    "verbs",
                    List.of("get", "list", "watch")),
                Map.of(
                    "apiGroups",
                    List.of(""),
                    "resources",
                    List.of("pods/exec"),
                    "verbs",
                    List.of("create")),
                Map.of(
                    "apiGroups",
                    List.of("apps"),
                    "resources",
                    List.of("deployments"),
                    "verbs",
                    List.of("get", "list", "watch")))));
    return role;
  }

  private void createRoleBindingBootstrap(
      final ApiObject serviceAccount, final ApiObject role, final ApiObject namespace) {
    ApiObject roleBinding =
        new ApiObject(
            this,
            "rolebinding-headscale-bootstrap",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("RoleBinding")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headscale-bootstrap")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|RoleBinding|${headscale-namespace}|headscale-bootstrap"))
                        .build())
                .build());
    roleBinding.addDependency(namespace);
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
                "headscale-bootstrap")),
        JsonPatch.add(
            "/subjects",
            List.of(
                Map.of(
                    "kind",
                    "ServiceAccount",
                    "name",
                    "headscale-bootstrap",
                    "namespace",
                    HEADSCALE_NAMESPACE))));
  }

  private ApiObject createConfigMapFloxEnv(final ApiObject namespace) {
    ApiObject configMap =
        configMapWithData(
            RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.name(),
            "|ConfigMap|${headscale-namespace}|" + RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.name(),
            Map.of());
    configMap.addDependency(namespace);
    configMap.addJsonPatch(
        JsonPatch.add("/metadata/labels", Map.of("app.kubernetes.io/replicated", "true")),
        JsonPatch.add(
            "/metadata/annotations/replicator.v1.mittwald.de~1replicate-from",
            RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.qualifiedName()));
    return configMap;
  }

  private ApiObject createConfigMapHeadscaleConfig(final ApiObject namespace) {
    ApiObject configMap =
        configMapWithData(
            MeshLayerRefs.HEADSCALE_CONFIG_CONFIGMAP.name(),
            "|ConfigMap|${headscale-namespace}|" + MeshLayerRefs.HEADSCALE_CONFIG_CONFIGMAP.name(),
            Map.of(
                "config.yaml",
                "server_url: http://headscale.hs.net:8080\n"
                    + "listen_addr: 0.0.0.0:8080\n"
                    + "metrics_listen_addr: 0.0.0.0:9090\n"
                    + "grpc_listen_addr: 0.0.0.0:50443\n"
                    + "grpc_allow_insecure: true\n\n"
                    + "tls:\n"
                    + "  letsencrypt_hostname: \"\"\n"
                    + "  letsencrypt_cache_dir: \"\"\n"
                    + "  letsencrypt_challenge_type: \"\"\n"
                    + "  cert_path: \"\"\n"
                    + "  key_path: \"\"\n\n"
                    + "private_key_path: /var/lib/headscale/private.key\n"
                    + "noise:\n"
                    + "  private_key_path: /var/lib/headscale/noise_private.key\n\n"
                    + "prefixes:\n"
                    + "  v4: 100.64.0.0/10\n"
                    + "  v6: fd7a:115c:a1e0::/48\n\n"
                    + "derp:\n"
                    + "  server:\n"
                    + "    enabled: false\n"
                    + "  urls: []\n"
                    + "  paths:\n"
                    + "    - /etc/headscale/derp.yaml\n"
                    + "  auto_update_enabled: false\n"
                    + "  update_frequency: 24h\n\n"
                    + "disable_check_updates: true\n"
                    + "ephemeral_node_inactivity_timeout: 30m\n"
                    + "database:\n"
                    + "  type: sqlite\n"
                    + "  sqlite:\n"
                    + "    path: /var/lib/headscale/db.sqlite\n\n"
                    + "policy:\n"
                    + "  mode: file\n"
                    + "  path: /etc/headscale/acl.json\n\n"
                    + "dns:\n"
                    + "  base_domain: hs.net\n"
                    + "  nameservers:\n"
                    + "    global:\n"
                    + "      - 192.168.1.254\n"
                    + "      - 1.1.1.1\n"
                    + "      - 8.8.8.8\n"
                    + "  extra_records_path: /var/lib/headscale/extra_records.json\n"
                    + "  magic_dns: true\n\n"
                    + "log:\n"
                    + "  format: text\n"
                    + "  level: info"));
    configMap.addDependency(namespace);
    if (registry != null) {
      registry.publish(MeshLayerRefs.HEADSCALE_CONFIG_CONFIGMAP, configMap);
    }
    return configMap;
  }

  private ApiObject createConfigMapConfigInitScript(final ApiObject namespace) {
    ApiObject configMap =
        configMapWithData(
            "headscale-config-init-script",
            "|ConfigMap|${headscale-namespace}|headscale-config-init-script",
            Map.of(
                "config-init.sh",
                "#!/usr/bin/env -S bash -exuo pipefail\n"
                    + "mkdir -p /config\n"
                    + "cp /config-source/config.yaml /config/config.yaml\n"
                    + "mkdir -p /var/lib/headscale\n"
                    + "if [ ! -f /var/lib/headscale/extra_records.json ]; then \n"
                    + "  echo \"[]\" > /var/lib/headscale/extra_records.json; \n"
                    + "fi\n"
                    + "cp /extra-records-source/extra_records.json /var/lib/headscale/extra_records.json"));
    configMap.addDependency(namespace);
    configMap.addJsonPatch(JsonPatch.add("/metadata/labels", Map.of("app", "headscale")));
    return configMap;
  }

  private ApiObject createConfigMapHeadscaleEnv(final ApiObject namespace) {
    ApiObject configMap =
        configMapWithData(
            MeshLayerRefs.HEADSCALE_ENV_CONFIGMAP.name(),
            "|ConfigMap|${headscale-namespace}|" + MeshLayerRefs.HEADSCALE_ENV_CONFIGMAP.name(),
            Map.of(
                "CLUSTER_LAN_HEADSCALE_INETADDR",
                "192.168.1.193",
                "DARWIN_HOST",
                "bioskop",
                "HEADPLANE_NAMESPACE",
                "${headplane-namespace}",
                "HEADSCALE_NAMESPACE",
                HEADSCALE_NAMESPACE,
                "HEADSCALE_URL",
                "http://headscale." + HEADSCALE_NAMESPACE + ".svc.cluster.local:8080",
                "RKE2_CLUSTER_NAME",
                "bioskop",
                "VIP_NETWORK_CIDR",
                "10.80.7.0/24"));
    configMap.addDependency(namespace);
    if (registry != null) {
      registry.publish(MeshLayerRefs.HEADSCALE_ENV_CONFIGMAP, configMap);
    }
    return configMap;
  }

  private ApiObject createConfigMapBootstrapScript(final ApiObject namespace) {
    ApiObject configMap =
        configMapWithData(
            "headscale-bootstrap-script",
            "|ConfigMap|${headscale-namespace}|headscale-bootstrap-script",
            Map.of(
                "bootstrap.sh",
                "#!/usr/bin/env -S bash -exuo pipefail\n\n"
                    + ": \"Waiting for headscale deployment to be available...\"\n"
                    + "kubectl wait --for=condition=available deployment/headscale \\\n"
                    + "  -n \"$HEADSCALE_NAMESPACE\" --timeout=300s\n\n"
                    + ": \"Waiting for headscale pod to be Ready...\"\n"
                    + "kubectl wait --for=condition=Ready pod -l app=headscale \\\n"
                    + "  -n \"$HEADSCALE_NAMESPACE\" --timeout=300s\n\n"
                    + ": \"Creating admin user...\"\n"
                    + "kubectl exec -n \"$HEADSCALE_NAMESPACE\" deployment/headscale -- \\\n"
                    + "  headscale users create admin 2>/dev/null || echo \"User admin already exists\"\n\n"
                    + ": \"Getting admin user ID...\"\n"
                    + "USER_ID=$( kubectl exec -n \"$HEADSCALE_NAMESPACE\" deployment/headscale -c headscale -- \\\n"
                    + "             headscale users list -o yaml |\n"
                    + "             yq -r '.[] | select( .name == \"admin\" ) | .id' - )\n"
                    + "if [ -z \"$USER_ID\" ]; then\n"
                    + "  echo \"ERROR: Failed to get admin user ID\"\n"
                    + "  exit 1\n"
                    + "fi\n\n"
                    + ": \"Creating reusable preauth key... (10 years expiration)\"\n"
                    + "PREAUTH_KEY=$( kubectl exec -n \"$HEADSCALE_NAMESPACE\" deployment/headscale -c headscale -- \\\n"
                    + "  headscale preauthkeys --user \"$USER_ID\" \\\n"
                    + "    create --reusable --expiration 87600h -o yaml |\n"
                    + "  yq -r '.key' - )\n"
                    + "if [ -z \"$PREAUTH_KEY\" ]; then\n"
                    + "  echo \"ERROR: Failed to extract preauth key\"\n"
                    + "  exit 1\n"
                    + "fi\n\n"
                    + ": \"Storing preauth key in Secret...\"\n"
                    + "kubectl create secret generic "
                    + MeshLayerRefs.HEADSCALE_CLIENT_AUTH_SECRET.name()
                    + " \\\n"
                    + "  --from-literal=authkey=\"$PREAUTH_KEY\" \\\n"
                    + "  --dry-run=client -o yaml | kubectl apply -f -\n\n"
                    + ": \"Creating Headplane API key...\"\n"
                    + "HEADPLANE_API_KEY=$( kubectl exec -n \"$HEADSCALE_NAMESPACE\" deployment/headscale -- \\\n"
                    + "  headscale apikeys create )\n"
                    + "if [ -z \"$HEADPLANE_API_KEY\" ]; then\n"
                    + "  echo \"ERROR: Failed to create Headplane API key\"\n"
                    + "  exit 1\n"
                    + "fi\n\n"
                    + ": \"Updating headplane-secrets with API key...\"\n"
                    + "kubectl patch secret "
                    + MeshLayerRefs.HEADPLANE_SECRETS_SECRET.name()
                    + " \\\n"
                    + "  -n \"$HEADSCALE_NAMESPACE\" \\\n"
                    + "  --type merge \\\n"
                    + "  -p \"{\\\"stringData\\\":{\\\"api_key\\\":\\\"$HEADPLANE_API_KEY\\\"}}\" 2>/dev/null || \\\n"
                    + "  echo \"Note: "
                    + MeshLayerRefs.HEADPLANE_SECRETS_SECRET.name()
                    + " not yet created, will be updated when available\""));
    configMap.addDependency(namespace);
    configMap.addJsonPatch(JsonPatch.add("/metadata/labels", Map.of("app", "headscale")));
    return configMap;
  }

  private ApiObject createConfigMapClientScripts(final ApiObject namespace) {
    ApiObject configMap =
        configMapWithData(
            "headscale-client-scripts",
            "|ConfigMap|${headscale-namespace}|headscale-client-scripts",
            Map.of(
                "tailscale-client.sh",
                "#!/bin/sh\n"
                    + "set -exuo pipefail\n\n"
                    + ": \"[i] Starting tailscaled...\"\n"
                    + "tailscaled \\\n"
                    + "  --tun=userspace-networking \\\n"
                    + "  --state=/var/lib/tailscale/tailscaled.state \\\n"
                    + "  --socket=/var/run/tailscale/tailscaled.sock --verbose=1 &\n"
                    + "TAILSCALED_PID=$$!\n\n"
                    + ": \"[i] Waiting for tailscaled socket...\"\n"
                    + "until [ -S /var/run/tailscale/tailscaled.sock ]; do sleep 1; done\n\n"
                    + ": \"[i] Connecting to Headscale at $HEADSCALE_URL...\"\n"
                    + "tailscale up \\\n"
                    + "  --login-server=$HEADSCALE_URL \\\n"
                    + "  --authkey=file:/var/secrets/authkey \\\n"
                    + "  --hostname=${DARWIN_HOST}-${RKE2_NODENAME} \\\n"
                    + "  --advertise-tags=tag:rke2,tag:alcide \\\n"
                    + "  --accept-routes \\\n"
                    + "  --ssh \\\n"
                    + "  --reset\n\n"
                    + "wait $TAILSCALED_PID",
                "wait-for-headscale.sh",
                "#!/bin/sh\n"
                    + "set -exuo pipefail\n\n"
                    + ": \"[i] Waiting for headscale deployment to be available...\"\n"
                    + "kubectl wait --for=condition=available deployment/headscale \\\n"
                    + "  -n \"$HEADSCALE_NAMESPACE\" --timeout=300s\n\n"
                    + ": \"[i] Waiting for required ConfigMaps...\"\n"
                    + "kubectl wait --for=create configmap/headscale-client-scripts \\\n"
                    + "  -n \"$HEADSCALE_NAMESPACE\" --timeout=300s\n"
                    + "kubectl wait --for=create configmap/"
                    + MeshLayerRefs.HEADSCALE_ENV_CONFIGMAP.name()
                    + " \\\n"
                    + "  -n \"$HEADSCALE_NAMESPACE\" --timeout=300s\n"
                    + "kubectl wait --for=create configmap/"
                    + RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.name()
                    + " \\\n"
                    + "  -n \"$HEADSCALE_NAMESPACE\" --timeout=300s\n\n"
                    + ": \"[i] Waiting for required secrets...\"\n"
                    + "kubectl wait --for=create secret/"
                    + MeshLayerRefs.HEADSCALE_CLIENT_AUTH_SECRET.name()
                    + " \\\n"
                    + "  -n \"$HEADSCALE_NAMESPACE\" --timeout=300s"));
    configMap.addDependency(namespace);
    configMap.addJsonPatch(JsonPatch.add("/metadata/labels", Map.of("app", "headscale-client")));
    return configMap;
  }

  private ApiObject createConfigMapAcl(final ApiObject namespace) {
    ApiObject configMap =
        configMapWithData(
            "headscale-acl",
            "|ConfigMap|${headscale-namespace}|headscale-acl",
            Map.of(
                "acl.json",
                "{\n"
                    + "  \"groups\": {\n"
                    + "    \"group:admin\": []\n"
                    + "  },\n"
                    + "  \"tagOwners\": {\n"
                    + "    \"tag:darwin\": [\"group:admin\"],\n"
                    + "    \"tag:nixos\": [\"group:admin\"],\n"
                    + "    \"tag:rke2\": [\"group:admin\"]\n"
                    + "  },\n"
                    + "  \"acls\": [\n"
                    + "    {\n"
                    + "      \"action\": \"accept\",\n"
                    + "      \"src\": [\"*\"],\n"
                    + "      \"dst\": [\"*:*\"]\n"
                    + "    }\n"
                    + "  ],\n"
                    + "  \"ssh\": [\n"
                    + "    {\n"
                    + "      \"action\": \"accept\",\n"
                    + "      \"src\": [\"group:admin\", \"tag:darwin\", \"tag:nixos\"],\n"
                    + "      \"dst\": [\"*\"],\n"
                    + "      \"users\": [\"autogroup:nonroot\", \"root\"]\n"
                    + "    }\n"
                    + "  ]\n"
                    + "}"));
    configMap.addDependency(namespace);
    return configMap;
  }

  private ApiObject createConfigMapDerp(final ApiObject namespace) {
    ApiObject configMap =
        configMapWithData(
            "headscale-derp",
            "|ConfigMap|${headscale-namespace}|headscale-derp",
            Map.of(
                "derp.yaml",
                "regions:\n"
                    + "  900:\n"
                    + "    regionid: 900\n"
                    + "    regioncode: local\n"
                    + "    regionname: Local LAN\n"
                    + "    nodes: []"));
    configMap.addDependency(namespace);
    return configMap;
  }

  private ApiObject createConfigMapExtraRecords(final ApiObject namespace) {
    ApiObject configMap =
        configMapWithData(
            "headscale-extra-records",
            "|ConfigMap|${headscale-namespace}|headscale-extra-records",
            Map.of(
                "extra_records.json",
                "[\n"
                    + "  {\n"
                    + "    \"name\": \"headscale.hs.net\",\n"
                    + "    \"type\": \"A\",\n"
                    + "    \"value\": \"${cluster-lan-headscale-inetaddr}\"\n"
                    + "  },\n"
                    + "  {\n"
                    + "    \"name\": \"headplane.hs.net\",\n"
                    + "    \"type\": \"A\",\n"
                    + "    \"value\": \"${cluster-lan-headplane-inetaddr}\"\n"
                    + "  }\n"
                    + "]"));
    configMap.addDependency(namespace);
    return configMap;
  }

  private ApiObject createConfigMapGatewayScript(final ApiObject namespace) {
    ApiObject configMap =
        configMapWithData(
            "headscale-gateway-script",
            "|ConfigMap|${headscale-namespace}|headscale-gateway-script",
            Map.of(
                "gateway.sh",
                "#!/usr/bin/env -S bash -exuo pipefail\n\n"
                    + ": \"[i] Enabling IP forwarding...\"\n"
                    + "sysctl -w net.ipv4.ip_forward=1\n"
                    + "sysctl -w net.ipv6.conf.all.forwarding=1 || true\n\n"
                    + ": \"[i] Starting tailscaled router...\"\n"
                    + "tailscaled --state=/var/lib/tailscale/tailscaled.state --socket=/var/run/tailscale/tailscaled.sock &\n"
                    + "TAILSCALED_PID=$$!\n\n"
                    + "until [ -S /var/run/tailscale/tailscaled.sock ]; do sleep 1; done\n\n"
                    + "tailscale up \\\n"
                    + "  --login-server=$HEADSCALE_URL \\\n"
                    + "  --authkey=file:/var/secrets/authkey \\\n"
                    + "  --hostname=${DARWIN_HOST}-gateway \\\n"
                    + "  --advertise-routes=${VIP_NETWORK_CIDR} \\\n"
                    + "  --accept-dns=false \\\n"
                    + "  --ssh \\\n"
                    + "  --reset\n\n"
                    + "tailscale set --advertise-routes=${VIP_NETWORK_CIDR}\n\n"
                    + "wait $TAILSCALED_PID"));
    configMap.addDependency(namespace);
    configMap.addJsonPatch(JsonPatch.add("/metadata/labels", Map.of("app", "headscale-gateway")));
    return configMap;
  }

  private ApiObject createLanPolicy() {
    ApiObject policy =
        new ApiObject(
            this,
            "ciliuml2announcementpolicy-lan-policy",
            ApiObjectProps.builder()
                .apiVersion("cilium.io/v2alpha1")
                .kind("CiliumL2AnnouncementPolicy")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("lan-policy")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cilium.io|CiliumL2AnnouncementPolicy|default|lan-policy"))
                        .build())
                .build());
    policy.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "interfaces",
                List.of("^lan0$"),
                "loadBalancerIPs",
                true,
                "nodeSelector",
                Map.of(
                    "matchExpressions",
                    List.of(
                        Map.of(
                            "key", "node-role.kubernetes.io/control-plane", "operator", "Exists"))),
                "serviceSelector",
                Map.of("matchLabels", Map.of("io.cilium/lb-ipam-pool", "lan")))));
    return policy;
  }

  private ApiObject createDeploymentHeadscale(
      final ApiObject namespace,
      final ApiObject cmFloxEnv,
      final ApiObject cmHeadscaleConfig,
      final ApiObject cmConfigInitScript,
      final ApiObject cmAcl,
      final ApiObject cmDerp,
      final ApiObject cmExtraRecords,
      final ApiObject l2Policy) {
    ApiObject deployment =
        new ApiObject(
            this,
            "deployment-headscale",
            ApiObjectProps.builder()
                .apiVersion("apps/v1")
                .kind("Deployment")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headscale")
                        .namespace(HEADSCALE_NAMESPACE)
                        .labels(Map.of("app", "headscale"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "apps|Deployment|${headscale-namespace}|headscale"))
                        .build())
                .build());
    deployment.addDependency(namespace);
    deployment.addDependency(cmFloxEnv);
    deployment.addDependency(cmHeadscaleConfig);
    deployment.addDependency(cmConfigInitScript);
    deployment.addDependency(cmAcl);
    deployment.addDependency(cmDerp);
    deployment.addDependency(cmExtraRecords);
    deployment.addDependency(l2Policy);

    final FloxDebugPolicy debugPolicy = ManifestSynthesisContext.current().floxDebugPolicy();
    final FloxShellSidecarProfile shellSidecar =
        new FloxShellSidecarProfile(
            debugPolicy,
            debugPolicy.meshEnabled(),
            "headscale",
            "/root",
            "mesh/headscale-debug",
            "0",
            "0");

    final List<Map<String, Object>> headscaleMounts = new ArrayList<>();
    headscaleMounts.add(
        Map.of(
            "mountPath", "/etc/headscale/config.yaml",
            "name", "config",
            "subPath", "config.yaml"));
    headscaleMounts.add(
        Map.of(
            "mountPath", "/etc/headscale/acl.json",
            "name", "acl",
            "subPath", "acl.json"));
    headscaleMounts.add(
        Map.of(
            "mountPath", "/etc/headscale/derp.yaml",
            "name", "derp",
            "subPath", "derp.yaml"));
    headscaleMounts.add(Map.of("mountPath", "/var/lib/headscale", "name", "data"));
    headscaleMounts.add(Map.of("mountPath", "/var/run/headscale", "name", "run"));
    headscaleMounts.addAll(shellSidecar.extraProdMounts());

    final LinkedHashMap<String, Object> headscaleContainer = new LinkedHashMap<>();
    headscaleContainer.put("name", "headscale");
    headscaleContainer.put("image", floxImage);
    headscaleContainer.put("command", List.of("headscale", "serve"));
    headscaleContainer.put(
        "envFrom",
        List.of(
            Map.of("configMapRef", Map.of("name", RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.name()))));
    headscaleContainer.put(
        "livenessProbe",
        Map.of(
            "httpGet",
            Map.of("path", "/health", "port", "http"),
            "initialDelaySeconds",
            30,
            "periodSeconds",
            10));
    headscaleContainer.put(
        "readinessProbe",
        Map.of(
            "httpGet",
            Map.of("path", "/health", "port", "http"),
            "initialDelaySeconds",
            10,
            "periodSeconds",
            5));
    headscaleContainer.put(
        "ports",
        List.of(
            Map.of("containerPort", 8080, "name", "http", "protocol", "TCP"),
            Map.of("containerPort", 9090, "name", "metrics", "protocol", "TCP")));
    headscaleContainer.put(
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
    headscaleContainer.put("volumeMounts", List.copyOf(headscaleMounts));

    final List<Object> containers = new ArrayList<>();
    containers.add(headscaleContainer);
    shellSidecar.sidecar(headscaleMounts).ifPresent(containers::add);

    final List<Object> volumes = new ArrayList<>();
    volumes.add(
        Map.of(
            "name",
            "config-source",
            "configMap",
            Map.of("name", MeshLayerRefs.HEADSCALE_CONFIG_CONFIGMAP.name())));
    volumes.add(Map.of("name", "config", "emptyDir", Map.of()));
    volumes.add(
        Map.of(
            "name",
            "config-init-script",
            "configMap",
            Map.of("defaultMode", 493, "name", "headscale-config-init-script")));
    volumes.add(
        Map.of(
            "name",
            "extra-records-source",
            "configMap",
            Map.of("name", "headscale-extra-records")));
    volumes.add(Map.of("name", "acl", "configMap", Map.of("name", "headscale-acl")));
    volumes.add(Map.of("name", "derp", "configMap", Map.of("name", "headscale-derp")));
    volumes.add(Map.of("name", "data", "emptyDir", Map.of()));
    volumes.add(Map.of("name", "run", "emptyDir", Map.of()));
    volumes.addAll(shellSidecar.extraVolumes());

    // When debug is on, the prod container runs against the debug env so headscale ships with
    // delve in PATH and unstripped symbols; the shell sidecar can `dlv attach $(pgrep headscale)`
    // through the shared PID namespace.
    final LinkedHashMap<String, String> annotations = new LinkedHashMap<>();
    annotations.put(
        "flox.dev/environment.headscale",
        debugPolicy.resolveMeshEnvironment("mesh/headscale", "mesh/headscale-debug"));
    annotations.putAll(shellSidecar.sidecarAnnotations());

    final LinkedHashMap<String, Object> headscalePodSpec = new LinkedHashMap<>();
    headscalePodSpec.put("automountServiceAccountToken", false);
    headscalePodSpec.put("containers", List.copyOf(containers));
    headscalePodSpec.put(
        "initContainers",
        List.of(
            Map.of(
                "name",
                "config-init",
                "image",
                floxImage,
                "command",
                List.of("/scripts/config-init.sh"),
                "volumeMounts",
                List.of(
                    Map.of("mountPath", "/scripts", "name", "config-init-script"),
                    Map.of(
                        "mountPath", "/config-source", "name", "config-source", "readOnly", true),
                    Map.of("mountPath", "/config", "name", "config"),
                    Map.of(
                        "mountPath",
                        "/extra-records-source",
                        "name",
                        "extra-records-source",
                        "readOnly",
                        true),
                    Map.of("mountPath", "/var/lib/headscale", "name", "data")))));
    headscalePodSpec.put("nodeSelector", Map.of("node-role.kubernetes.io/control-plane", "true"));
    if (shellSidecar.shareProcessNamespace()) {
      headscalePodSpec.put("shareProcessNamespace", true);
    }
    headscalePodSpec.put("volumes", List.copyOf(volumes));

    deployment.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "replicas",
                1,
                "selector",
                Map.of("matchLabels", Map.of("app", "headscale")),
                "template",
                Map.of(
                    "metadata",
                    Map.of(
                        "annotations",
                        packageProfile.templateAnnotations(Map.copyOf(annotations)),
                        "labels",
                        Map.of("app", "headscale")),
                    "spec",
                    Map.copyOf(headscalePodSpec)))));
    return deployment;
  }

  private ApiObject createServiceHeadscale(final ApiObject namespace, final ApiObject deployment) {
    ApiObject service =
        new ApiObject(
            this,
            "service-headscale",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Service")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headscale")
                        .namespace(HEADSCALE_NAMESPACE)
                        .labels(Map.of("app", "headscale", "io.cilium/lb-ipam-pool", "lan"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Service|${headscale-namespace}|headscale",
                                Map.of(
                                    "io.cilium/lb-ipam-ips",
                                    "192.168.1.193",
                                    "tailscale.com/expose",
                                    "true",
                                    "tailscale.com/hostname",
                                    "bioskop-headscale")))
                        .build())
                .build());
    service.addDependency(namespace);
    service.addDependency(deployment);
    service.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "ports",
                List.of(
                    Map.of("name", "http", "port", 8080, "protocol", "TCP", "targetPort", 8080),
                    Map.of("name", "metrics", "port", 9090, "protocol", "TCP", "targetPort", 9090)),
                "selector",
                Map.of("app", "headscale"),
                "type",
                "LoadBalancer")));
    return service;
  }

  private ApiObject createBootstrapJob(
      final ApiObject namespace,
      final ApiObject serviceAccount,
      final ApiObject cmHeadscaleEnv,
      final ApiObject cmFloxEnv,
      final ApiObject cmScript,
      final ApiObject deployment) {
    ApiObject job =
        new ApiObject(
            this,
            "job-headscale-bootstrap",
            ApiObjectProps.builder()
                .apiVersion("batch/v1")
                .kind("Job")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headscale-bootstrap")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "batch|Job|${headscale-namespace}|headscale-bootstrap"))
                        .build())
                .build());
    job.addDependency(namespace);
    job.addDependency(serviceAccount);
    job.addDependency(cmHeadscaleEnv);
    job.addDependency(cmFloxEnv);
    job.addDependency(cmScript);
    job.addDependency(deployment);
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
                            Map.of("flox.dev/environment.bootstrap", "mesh/headscale"))),
                    "spec",
                    Map.of(
                        "containers",
                        List.of(
                            Map.of(
                                "name",
                                "bootstrap",
                                "image",
                                floxImage,
                                "command",
                                List.of("/scripts/bootstrap.sh"),
                                "envFrom",
                                List.of(
                                    Map.of(
                                        "configMapRef",
                                        Map.of(
                                            "name", MeshLayerRefs.HEADSCALE_ENV_CONFIGMAP.name())),
                                    Map.of(
                                        "configMapRef",
                                        Map.of(
                                            "name", RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.name()))),
                                "resources",
                                Map.of(
                                    "limits",
                                    Map.of(
                                        "cpu",
                                        "250m",
                                        "ephemeral-storage",
                                        "256Mi",
                                        "memory",
                                        "256Mi"),
                                    "requests",
                                    Map.of(
                                        "cpu",
                                        "50m",
                                        "ephemeral-storage",
                                        "64Mi",
                                        "memory",
                                        "64Mi")),
                                "volumeMounts",
                                List.of(
                                    Map.of(
                                        "mountPath",
                                        "/scripts",
                                        "name",
                                        "bootstrap-script",
                                        "readOnly",
                                        true)))),
                        "restartPolicy",
                        "OnFailure",
                        "serviceAccountName",
                        "headscale-bootstrap",
                        "volumes",
                        List.of(
                            Map.of(
                                "name",
                                "bootstrap-script",
                                "configMap",
                                Map.of(
                                    "defaultMode", 493, "name", "headscale-bootstrap-script"))))),
                "ttlSecondsAfterFinished",
                300)));
    return job;
  }

  private void createDeploymentGateway(
      final ApiObject namespace,
      final ApiObject serviceAccount,
      final ApiObject cmHeadscaleEnv,
      final ApiObject cmFloxEnv,
      final ApiObject cmScript,
      final ApiObject bootstrapJob) {
    ApiObject deployment =
        new ApiObject(
            this,
            "deployment-headscale-gateway",
            ApiObjectProps.builder()
                .apiVersion("apps/v1")
                .kind("Deployment")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headscale-gateway")
                        .namespace(HEADSCALE_NAMESPACE)
                        .labels(Map.of("app", "headscale-gateway"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "apps|Deployment|${headscale-namespace}|headscale-gateway"))
                        .build())
                .build());
    deployment.addDependency(namespace);
    deployment.addDependency(serviceAccount);
    deployment.addDependency(cmHeadscaleEnv);
    deployment.addDependency(cmFloxEnv);
    deployment.addDependency(cmScript);
    deployment.addDependency(bootstrapJob);

    final FloxDebugPolicy gatewayDebugPolicy = ManifestSynthesisContext.current().floxDebugPolicy();
    final FloxShellSidecarProfile gatewayShellSidecar =
        new FloxShellSidecarProfile(
            gatewayDebugPolicy,
            gatewayDebugPolicy.meshEnabled(),
            "tailscale-gateway",
            "/root",
            "mesh/tailscale-debug",
            "0",
            "0");

    final List<Map<String, Object>> gatewayMounts = new ArrayList<>();
    gatewayMounts.add(Map.of("mountPath", "/dev/net/tun", "name", "dev-net-tun"));
    gatewayMounts.add(Map.of("mountPath", "/var/lib/tailscale", "name", "tailscale-state"));
    gatewayMounts.add(Map.of("mountPath", "/var/run/tailscale", "name", "tailscale-socket"));
    gatewayMounts.add(
        Map.of(
            "mountPath", "/var/secrets",
            "name", "authkey",
            "readOnly", true));
    gatewayMounts.add(
        Map.of(
            "mountPath", "/scripts",
            "name", "gateway-script",
            "readOnly", true));
    gatewayMounts.addAll(gatewayShellSidecar.extraProdMounts());

    final LinkedHashMap<String, Object> gatewayContainer = new LinkedHashMap<>();
    gatewayContainer.put("name", "tailscale-gateway");
    gatewayContainer.put("image", floxImage);
    gatewayContainer.put("command", List.of("/scripts/gateway.sh"));
    gatewayContainer.put(
        "env",
        List.of(
            Map.of(
                "name",
                "TS_AUTHKEY",
                "valueFrom",
                Map.of(
                    "secretKeyRef",
                    Map.of(
                        "key",
                        "authkey",
                        "name",
                        MeshLayerRefs.HEADSCALE_CLIENT_AUTH_SECRET.name())))));
    gatewayContainer.put(
        "envFrom",
        List.of(
            Map.of("configMapRef", Map.of("name", MeshLayerRefs.HEADSCALE_ENV_CONFIGMAP.name())),
            Map.of("configMapRef", Map.of("name", RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.name()))));
    gatewayContainer.put(
        "resources",
        Map.of(
            "limits",
            Map.of(
                "cpu", "200m",
                "ephemeral-storage", "500Mi",
                "memory", "256Mi"),
            "requests",
            Map.of(
                "cpu", "50m",
                "ephemeral-storage", "100Mi",
                "memory", "64Mi")));
    gatewayContainer.put(
        "securityContext",
        Map.of("capabilities", Map.of("add", List.of("NET_ADMIN", "NET_RAW")), "privileged", true));
    gatewayContainer.put("volumeMounts", List.copyOf(gatewayMounts));

    final List<Object> gatewayContainers = new ArrayList<>();
    gatewayContainers.add(gatewayContainer);
    gatewayShellSidecar.sidecar(gatewayMounts).ifPresent(gatewayContainers::add);

    final List<Object> gatewayVolumes = new ArrayList<>();
    gatewayVolumes.add(
        Map.of(
            "name",
            "dev-net-tun",
            "hostPath",
            Map.of("path", "/dev/net/tun", "type", "CharDevice")));
    gatewayVolumes.add(
        Map.of(
            "name",
            "tailscale-state",
            "hostPath",
            Map.of("path", "/var/lib/tailscale", "type", "DirectoryOrCreate")));
    gatewayVolumes.add(Map.of("name", "tailscale-socket", "emptyDir", Map.of()));
    gatewayVolumes.add(
        Map.of(
            "name",
            "gateway-script",
            "configMap",
            Map.of("defaultMode", 493, "name", "headscale-gateway-script")));
    gatewayVolumes.add(
        Map.of(
            "name",
            "authkey",
            "secret",
            Map.of(
                "items",
                List.of(Map.of("key", "authkey", "path", "authkey")),
                "secretName",
                MeshLayerRefs.HEADSCALE_CLIENT_AUTH_SECRET.name())));
    gatewayVolumes.addAll(gatewayShellSidecar.extraVolumes());

    final LinkedHashMap<String, String> gatewayAnnotations = new LinkedHashMap<>();
    gatewayAnnotations.put(
        "flox.dev/environment.tailscale-gateway",
        gatewayDebugPolicy.resolveMeshEnvironment("mesh/tailscale", "mesh/tailscale-debug"));
    gatewayAnnotations.putAll(gatewayShellSidecar.sidecarAnnotations());

    final LinkedHashMap<String, Object> gatewayPodSpec = new LinkedHashMap<>();
    gatewayPodSpec.put("automountServiceAccountToken", false);
    gatewayPodSpec.put("containers", List.copyOf(gatewayContainers));
    gatewayPodSpec.put("dnsPolicy", "ClusterFirstWithHostNet");
    gatewayPodSpec.put("hostNetwork", true);
    gatewayPodSpec.put("nodeSelector", Map.of("node-role.kubernetes.io/control-plane", "true"));
    gatewayPodSpec.put("serviceAccountName", "headscale-gateway");
    if (gatewayShellSidecar.shareProcessNamespace()) {
      gatewayPodSpec.put("shareProcessNamespace", true);
    }
    gatewayPodSpec.put(
        "tolerations",
        List.of(
            Map.of(
                "effect",
                "NoSchedule",
                "key",
                "node-role.kubernetes.io/control-plane",
                "operator",
                "Exists")));
    gatewayPodSpec.put("volumes", List.copyOf(gatewayVolumes));

    deployment.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "replicas",
                1,
                "selector",
                Map.of("matchLabels", Map.of("app", "headscale-gateway")),
                "template",
                Map.of(
                    "metadata",
                    Map.of(
                        "annotations",
                        packageProfile.templateAnnotations(Map.copyOf(gatewayAnnotations)),
                        "labels",
                        Map.of("app", "headscale-gateway")),
                    "spec",
                    Map.copyOf(gatewayPodSpec)))));
  }

  private void createDaemonsetClient(
      final ApiObject namespace,
      final ApiObject serviceAccount,
      final ApiObject cmHeadscaleEnv,
      final ApiObject cmFloxEnv,
      final ApiObject cmScripts,
      final ApiObject bootstrapJob,
      final ApiObject serviceHeadscale) {
    ApiObject daemonSet =
        new ApiObject(
            this,
            "daemonset-headscale-client",
            ApiObjectProps.builder()
                .apiVersion("apps/v1")
                .kind("DaemonSet")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headscale-client")
                        .namespace(HEADSCALE_NAMESPACE)
                        .labels(Map.of("app", "headscale-client"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "apps|DaemonSet|${headscale-namespace}|headscale-client"))
                        .build())
                .build());
    daemonSet.addDependency(namespace);
    daemonSet.addDependency(serviceAccount);
    daemonSet.addDependency(cmHeadscaleEnv);
    daemonSet.addDependency(cmFloxEnv);
    daemonSet.addDependency(cmScripts);
    daemonSet.addDependency(bootstrapJob);
    daemonSet.addDependency(serviceHeadscale);

    final FloxDebugPolicy clientDebugPolicy = ManifestSynthesisContext.current().floxDebugPolicy();
    final FloxShellSidecarProfile clientShellSidecar =
        new FloxShellSidecarProfile(
            clientDebugPolicy,
            clientDebugPolicy.meshEnabled(),
            "tailscale",
            "/root",
            "mesh/tailscale-debug",
            "0",
            "0");

    final List<Map<String, Object>> clientMounts = new ArrayList<>();
    clientMounts.add(Map.of("mountPath", "/dev/net/tun", "name", "dev-net-tun"));
    clientMounts.add(Map.of("mountPath", "/var/lib/tailscale", "name", "tailscale-state"));
    clientMounts.add(Map.of("mountPath", "/var/run/tailscale", "name", "tailscale-socket"));
    clientMounts.add(
        Map.of(
            "mountPath", "/var/secrets",
            "name", "authkey",
            "readOnly", true));
    clientMounts.add(
        Map.of(
            "mountPath", "/scripts",
            "name", "client-scripts",
            "readOnly", true));
    clientMounts.addAll(clientShellSidecar.extraProdMounts());

    final LinkedHashMap<String, Object> clientContainer = new LinkedHashMap<>();
    clientContainer.put("name", "tailscale");
    clientContainer.put("image", floxImage);
    clientContainer.put("command", List.of("/scripts/tailscale-client.sh"));
    clientContainer.put(
        "env",
        List.of(
            Map.of(
                "name",
                "RKE2_NODENAME",
                "valueFrom",
                Map.of("fieldRef", Map.of("fieldPath", "spec.nodeName"))),
            Map.of(
                "name",
                "TS_AUTHKEY",
                "valueFrom",
                Map.of(
                    "secretKeyRef",
                    Map.of(
                        "key",
                        "authkey",
                        "name",
                        MeshLayerRefs.HEADSCALE_CLIENT_AUTH_SECRET.name()))),
            Map.of("name", "TS_STATE_DIR", "value", "/var/lib/tailscale"),
            Map.of("name", "TS_SOCKET", "value", "/var/run/tailscale/tailscaled.sock"),
            Map.of("name", "TS_USERSPACE", "value", "true"),
            Map.of("name", "TS_KUBE_SECRET", "value", "")));
    clientContainer.put(
        "envFrom",
        List.of(
            Map.of("configMapRef", Map.of("name", MeshLayerRefs.HEADSCALE_ENV_CONFIGMAP.name())),
            Map.of("configMapRef", Map.of("name", RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.name()))));
    clientContainer.put(
        "resources",
        Map.of(
            "limits",
            Map.of(
                "cpu", "200m",
                "ephemeral-storage", "256Mi",
                "memory", "256Mi"),
            "requests",
            Map.of(
                "cpu", "50m",
                "ephemeral-storage", "64Mi",
                "memory", "64Mi")));
    clientContainer.put(
        "securityContext",
        Map.of("capabilities", Map.of("add", List.of("NET_ADMIN", "NET_RAW")), "privileged", true));
    clientContainer.put("volumeMounts", List.copyOf(clientMounts));

    final List<Object> clientContainers = new ArrayList<>();
    clientContainers.add(clientContainer);
    clientShellSidecar.sidecar(clientMounts).ifPresent(clientContainers::add);

    final List<Object> clientVolumes = new ArrayList<>();
    clientVolumes.add(
        Map.of(
            "name",
            "dev-net-tun",
            "hostPath",
            Map.of("path", "/dev/net/tun", "type", "CharDevice")));
    clientVolumes.add(
        Map.of(
            "name",
            "tailscale-state",
            "hostPath",
            Map.of("path", "/var/lib/tailscale", "type", "DirectoryOrCreate")));
    clientVolumes.add(Map.of("name", "tailscale-socket", "emptyDir", Map.of()));
    clientVolumes.add(
        Map.of(
            "name",
            "client-scripts",
            "configMap",
            Map.of("defaultMode", 493, "name", "headscale-client-scripts")));
    clientVolumes.add(
        Map.of(
            "name",
            "authkey",
            "secret",
            Map.of(
                "items",
                List.of(Map.of("key", "authkey", "path", "authkey")),
                "secretName",
                MeshLayerRefs.HEADSCALE_CLIENT_AUTH_SECRET.name())));
    clientVolumes.addAll(clientShellSidecar.extraVolumes());

    // Pod has hostPID: true (mutually exclusive with shareProcessNamespace), so the sidecar
    // already sees the prod tailscale PID via the host's PID namespace and `dlv attach` works
    // as long as the prod container exposes its symbols (debug env mounts the unstripped binary
    // when debug is on).
    final LinkedHashMap<String, String> clientAnnotations = new LinkedHashMap<>();
    clientAnnotations.put(
        "flox.dev/environment.tailscale",
        clientDebugPolicy.resolveMeshEnvironment("mesh/tailscale", "mesh/tailscale-debug"));
    clientAnnotations.putAll(clientShellSidecar.sidecarAnnotations());

    daemonSet.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "selector",
                Map.of("matchLabels", Map.of("app", "headscale-client")),
                "template",
                Map.of(
                    "metadata",
                    Map.of(
                        "annotations",
                        packageProfile.templateAnnotations(Map.copyOf(clientAnnotations)),
                        "labels",
                        Map.of("app", "headscale-client")),
                    "spec",
                    Map.of(
                        "containers",
                        List.copyOf(clientContainers),
                        "dnsPolicy",
                        "ClusterFirstWithHostNet",
                        "hostNetwork",
                        true,
                        "hostPID",
                        true,
                        "initContainers",
                        List.of(
                            Map.of(
                                "name",
                                "wait-for-headscale",
                                "image",
                                floxImage,
                                "command",
                                List.of("/scripts/wait-for-headscale.sh"),
                                "envFrom",
                                List.of(
                                    Map.of(
                                        "configMapRef",
                                        Map.of(
                                            "name", MeshLayerRefs.HEADSCALE_ENV_CONFIGMAP.name())),
                                    Map.of(
                                        "configMapRef",
                                        Map.of(
                                            "name", RuntimeLayerRefs.FLOX_ENV_CONFIGMAP.name()))),
                                "volumeMounts",
                                List.of(
                                    Map.of(
                                        "mountPath",
                                        "/scripts",
                                        "name",
                                        "client-scripts",
                                        "readOnly",
                                        true)))),
                        "nodeSelector",
                        Map.of("node-role.kubernetes.io/control-plane", "true"),
                        "serviceAccountName",
                        "headscale-client",
                        "tolerations",
                        List.of(
                            Map.of(
                                "effect",
                                "NoSchedule",
                                "key",
                                "node-role.kubernetes.io/control-plane",
                                "operator",
                                "Exists")),
                        "volumes",
                        List.copyOf(clientVolumes))))));
  }

  private ApiObject configMapWithData(
      final String name, final String upstream, final Map<String, String> data) {
    ApiObject configMap =
        new ApiObject(
            this,
            "configmap-" + name,
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(packageProfile.packageAnnotations(upstream))
                        .build())
                .build());
    configMap.addJsonPatch(JsonPatch.add("/data", data));
    return configMap;
  }
}
