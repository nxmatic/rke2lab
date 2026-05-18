// @codebase
package io.nxmatic.rk2lab.manifests.layers.networking;

import io.nxmatic.rk2lab.manifests.layers.cluster.ClusterLayerRefs;
import io.nxmatic.rk2lab.manifests.layers.common.profiles.DelveSidecarProfile;
import io.nxmatic.rk2lab.manifests.layers.common.profiles.DelveSidecarToggleResolver;
import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import io.nxmatic.rk2lab.manifests.layers.common.profiles.RuntimePodProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class KdnsLayer extends Construct {

  public static final String LEGACY_PATH_PREFIX = "networking/kdns/";

  private static final String LAYER_NAME = "networking";
  private static final String PACKAGE_NAME = "kdns";
  private static final String KDNS_NAMESPACE = ClusterLayerRefs.RUNTIME_SYSTEM_NAMESPACE.name();

  private final DelveSidecarToggleResolver delveSidecarToggleResolver =
      DelveSidecarToggleResolver.builder().build();
  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(LAYER_NAME, PACKAGE_NAME);
  private final KdnsAssets kdnsAssets = KdnsAssets.builder().build();
  private final RuntimePodProfile runtimePodProfile = new RuntimePodProfile("flox");
  private final DelveSidecarProfile delveSidecarProfile =
      new DelveSidecarProfile(
          delveSidecarToggleResolver.resolveByDomainLayer(LAYER_NAME, PACKAGE_NAME, false),
          "debug.kdns.lab42/enabled",
          "false",
          "GO_DEBUG_ENABLED",
          "KDNS_DEBUG_PORT",
          "40000");

  public KdnsLayer(final Construct scope, final String id) {
    super(scope, id);

    ApiObject clusterRole = createClusterRole();
    ApiObject serviceAccount = createServiceAccount();
    ApiObject clusterRoleBinding = createClusterRoleBinding(clusterRole, serviceAccount);
    ApiObject dlvScriptConfigMap = createDlvScriptConfigMap();
    createDeployment(serviceAccount, dlvScriptConfigMap, clusterRoleBinding);
  }

  private ApiObject createClusterRole() {
    ApiObject clusterRole =
        new ApiObject(
            this,
            "clusterrole-kdns-ingress-reader",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRole")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kdns-ingress-reader")
                        .annotations(packageProfile.packageAnnotationsWithoutUpstream())
                        .build())
                .build());

    List<Map<String, Object>> rules = new ArrayList<>();
    rules.add(
        new LinkedHashMap<>(
            Map.of(
                "apiGroups",
                List.of("networking.k8s.io"),
                "resources",
                List.of("ingresses"),
                "verbs",
                List.of("get", "list", "watch"))));
    rules.add(
        new LinkedHashMap<>(
            Map.of(
                "apiGroups",
                List.of(""),
                "resources",
                List.of("services", "endpoints"),
                "verbs",
                List.of("get", "list", "watch"))));

    clusterRole.addJsonPatch(JsonPatch.add("/rules", rules));
    return clusterRole;
  }

  private ApiObject createServiceAccount() {
    ApiObject serviceAccount =
        new ApiObject(
            this,
            "serviceaccount-kdns",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ServiceAccount")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kdns")
                        .namespace(KDNS_NAMESPACE)
                        .annotations(packageProfile.packageAnnotationsWithoutUpstream())
                        .labels(
                            Map.of(
                                "app.kubernetes.io/instance",
                                "kdns",
                                "app.kubernetes.io/managed-by",
                                "Helm",
                                "app.kubernetes.io/name",
                                "kdns",
                                "helm.sh/chart",
                                "kdns-0.2.3"))
                        .build())
                .build());

    serviceAccount.addJsonPatch(JsonPatch.add("/automountServiceAccountToken", true));
    return serviceAccount;
  }

  private ApiObject createClusterRoleBinding(
      final ApiObject clusterRole, final ApiObject serviceAccount) {
    ApiObject clusterRoleBinding =
        new ApiObject(
            this,
            "clusterrolebinding-kdns-ingress-reader-binding",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRoleBinding")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kdns-ingress-reader-binding")
                        .namespace(KDNS_NAMESPACE)
                        .annotations(packageProfile.packageAnnotationsWithoutUpstream())
                        .build())
                .build());

    clusterRoleBinding.addDependency(clusterRole);
    clusterRoleBinding.addDependency(serviceAccount);

    clusterRoleBinding.addJsonPatch(
        JsonPatch.add(
            "/roleRef",
            Map.of(
                "apiGroup",
                "rbac.authorization.k8s.io",
                "kind",
                "ClusterRole",
                "name",
                "kdns-ingress-reader")),
        JsonPatch.add(
            "/subjects",
            List.of(
                Map.of("kind", "ServiceAccount", "name", "kdns", "namespace", KDNS_NAMESPACE))));
    return clusterRoleBinding;
  }

  private ApiObject createDlvScriptConfigMap() {
    ApiObject configMap =
        new ApiObject(
            this,
            "configmap-kdns-dlv-script",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kdns-dlv-script")
                        .namespace(KDNS_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ConfigMap|${target-namespace}|kdns-dlv-script"))
                        .build())
                .build());

    configMap.addJsonPatch(JsonPatch.add("/data", kdnsAssets.dlvScriptConfigMapData()));

    return configMap;
  }

  private void createDeployment(
      final ApiObject serviceAccount,
      final ApiObject dlvScriptConfigMap,
      final ApiObject clusterRoleBinding) {
    ApiObject deployment =
        new ApiObject(
            this,
            "deployment-kdns",
            ApiObjectProps.builder()
                .apiVersion("apps/v1")
                .kind("Deployment")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kdns")
                        .namespace(KDNS_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "apps|Deployment|${target-namespace}|kdns"))
                        .labels(
                            Map.of(
                                "app.kubernetes.io/instance",
                                "kdns",
                                "app.kubernetes.io/managed-by",
                                "Helm",
                                "app.kubernetes.io/name",
                                "kdns",
                                "helm.sh/chart",
                                "kdns-0.2.3"))
                        .build())
                .build());

    deployment.addDependency(serviceAccount);
    deployment.addDependency(dlvScriptConfigMap);
    deployment.addDependency(clusterRoleBinding);

    LinkedHashMap<String, Object> kdnsContainer = new LinkedHashMap<>();
    kdnsContainer.put("name", "kdns");
    kdnsContainer.put("image", "flox/empty:1.0.0");
    kdnsContainer.put("imagePullPolicy", "IfNotPresent");
    kdnsContainer.put("command", List.of("kdns"));
    kdnsContainer.put(
        "env",
        List.of(
            Map.of("name", "KUBERNETES_SERVICE_HOST", "value", "10.80.0.10"),
            Map.of("name", "KUBERNETES_SERVICE_PORT", "value", "6443")));
    kdnsContainer.put("livenessProbe", null);
    kdnsContainer.put("readinessProbe", null);
    kdnsContainer.put(
        "ports",
        List.of(
            Map.of("containerPort", 5353, "hostPort", 5353, "name", "mdns", "protocol", "UDP"),
            Map.of("containerPort", 5353, "hostPort", 5353, "name", "http", "protocol", "TCP")));
    kdnsContainer.put(
        "resources",
        Map.of(
            "limits",
            Map.of("cpu", "200m", "memory", "256Mi"),
            "requests",
            Map.of("cpu", "100m", "memory", "128Mi")));
    kdnsContainer.put(
        "securityContext",
        Map.of(
            "capabilities",
            Map.of("drop", List.of("ALL")),
            "readOnlyRootFilesystem",
            false,
            "runAsNonRoot",
            false,
            "runAsUser",
            0));
    kdnsContainer.put(
        "volumeMounts",
        List.of(
            Map.of("mountPath", "/.config/flox", "name", "flox-config"),
            Map.of("mountPath", "/.cache/flox", "name", "flox-cache")));

    List<Object> containers = new ArrayList<>();
    containers.add(kdnsContainer);
    delveSidecarProfile
        .delveSidecar("kdns-dlv", "kdns-dlv.sh", "kdns-dlv-script")
        .ifPresent(containers::add);

    LinkedHashMap<String, Object> deploymentSpec = new LinkedHashMap<>();
    deploymentSpec.put("replicas", 1);
    deploymentSpec.put(
        "selector",
        Map.of(
            "matchLabels",
            Map.of("app.kubernetes.io/instance", "kdns", "app.kubernetes.io/name", "kdns")));
    deploymentSpec.put(
        "template",
        Map.of(
            "metadata",
            Map.of(
                "annotations",
                delveSidecarProfile.workloadAnnotations(
                    packageProfile.templateAnnotations(
                        Map.of("flox.dev/environment", "nxmatic/kdns"))),
                "labels",
                Map.of(
                    "app.kubernetes.io/instance",
                    "kdns",
                    "app.kubernetes.io/managed-by",
                    "Helm",
                    "app.kubernetes.io/name",
                    "kdns",
                    "helm.sh/chart",
                    "kdns-0.2.3")),
            "spec",
            runtimePodProfile.apply(
                containers,
                List.of(
                    Map.of(
                        "name",
                        "kdns-dlv-script",
                        "configMap",
                        Map.of("defaultMode", 493, "name", "kdns-dlv-script")),
                    Map.of("name", "flox-config", "emptyDir", Map.of()),
                    Map.of("name", "flox-cache", "emptyDir", Map.of())),
                "kdns",
                Map.of())));

    deployment.addJsonPatch(
        JsonPatch.add("/spec", deploymentSpec),
        JsonPatch.add("/spec/template/spec/containers/0/livenessProbe", null),
        JsonPatch.add("/spec/template/spec/containers/0/readinessProbe", null));
  }
}
