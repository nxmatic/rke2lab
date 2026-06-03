// @codebase
package io.nxmatic.rke2lab.manifests.units.networking;

import io.nxmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.nxmatic.rke2lab.manifests.profiles.DelveSidecarProfile;
import io.nxmatic.rke2lab.manifests.profiles.DelveSidecarToggleResolver;
import io.nxmatic.rke2lab.manifests.profiles.FloxDebugPolicy;
import io.nxmatic.rke2lab.manifests.profiles.FloxShellSidecarProfile;
import io.nxmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.nxmatic.rke2lab.manifests.profiles.RuntimePodProfile;
import io.nxmatic.rke2lab.manifests.units.cluster.ClusterRefs;
import io.nxmatic.rke2lab.manifests.units.cluster.ClusterRuntimeNamespaceManifestsUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class KdnsManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.NETWORKING + "/kdns";

  private static final NetworkingDependencyIntents NETWORKING_DEPENDENCY_INTENTS =
      NetworkingDependencyIntents.builder().build();

  private static final String LAYER_NAME = "networking";
  private static final String PACKAGE_NAME = "kdns";
  private static final String KDNS_NAMESPACE = ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name();

  private final DelveSidecarToggleResolver delveSidecarToggleResolver =
      DelveSidecarToggleResolver.builder().build();
  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(LAYER_NAME, PACKAGE_NAME);
  private final KdnsAssets kdnsAssets = KdnsAssets.builder().build();
  private final RuntimePodProfile runtimePodProfile = new RuntimePodProfile();
  private final DelveSidecarProfile delveSidecarProfile =
      new DelveSidecarProfile(
          delveSidecarToggleResolver.resolveByDomainLayer(LAYER_NAME, PACKAGE_NAME, false),
          "debug.kdns.lab42/enabled",
          "false",
          "GO_DEBUG_ENABLED",
          "KDNS_DEBUG_PORT",
          "40000");

  public KdnsManifestsUnit(final Construct scope, final String id) {
    super(
        scope,
        id,
        MANIFEST_UNIT_ID,
        Stream.concat(
                NETWORKING_DEPENDENCY_INTENTS
                    .resolve(List.of(NETWORKING_DEPENDENCY_INTENTS.requiresCiliumConfigIntent()))
                    .stream(),
                Stream.of(ClusterRuntimeNamespaceManifestsUnit.MANIFEST_UNIT_ID))
            .toList());

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

    // Debug mode is additive: prod container always runs the real workload in the prod carrier;
    // an opt-in shell sidecar (FloxShellSidecarProfile) carries the debug flox env so an operator
    // can `kubectl exec -c kdns-shell -- bash` and run `flox activate -- kdns ...` against the
    // same flox state the prod kdns container is using.
    final FloxDebugPolicy debugPolicy = ManifestSynthesisContext.current().floxDebugPolicy();
    final FloxShellSidecarProfile shellSidecar =
        new FloxShellSidecarProfile(
            debugPolicy,
            debugPolicy.networkingEnabled(),
            "kdns",
            "/root",
            "networking/kdns-debug",
            "0",
            "0");
    // When debug.networking is on, the prod container runs against the debug env so the binary
    // it executes has symbols + delve in PATH; the shell sidecar can then
    // `dlv attach $(pgrep kdns)` from the shared PID namespace.
    final String floxEnvironment =
        debugPolicy.resolveNetworkingEnvironment("networking/kdns", "networking/kdns-debug");

    LinkedHashMap<String, Object> kdnsContainer = new LinkedHashMap<>();
    kdnsContainer.put("name", "kdns");
    kdnsContainer.put("image", debugPolicy.prodImage());
    kdnsContainer.put("imagePullPolicy", "IfNotPresent");
    kdnsContainer.put("command", List.of("flox", "activate", "--dir", "/root", "--", "kdns"));
    kdnsContainer.put(
        "env",
        List.of(
            Map.of("name", "KUBERNETES_SERVICE_HOST", "value", "10.80.0.10"),
            Map.of("name", "KUBERNETES_SERVICE_PORT", "value", "6443"),
            Map.of(
                "name",
                "PATH",
                "valueFrom",
                Map.of(
                    "configMapKeyRef",
                    Map.of("name", "flox-env", "key", "NIX_DEFAULT_PROFILE_BIN_STORE_PATH"))),
            Map.of("name", "HOME", "value", "/root")));
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
    final List<Map<String, Object>> kdnsProdMounts = new ArrayList<>();
    if (!shellSidecar.enabled()) {
      // Prod-only flox state volumes; when the shell sidecar is enabled, it owns the matching
      // emptyDirs and shares them with prod via extraProdMounts() — these become duplicates.
      kdnsProdMounts.add(Map.of("mountPath", "/.config/flox", "name", "flox-config"));
      kdnsProdMounts.add(Map.of("mountPath", "/.cache/flox", "name", "flox-cache"));
    }
    kdnsProdMounts.addAll(shellSidecar.extraProdMounts());
    kdnsContainer.put("volumeMounts", List.copyOf(kdnsProdMounts));

    List<Object> containers = new ArrayList<>();
    containers.add(kdnsContainer);
    shellSidecar.sidecar(kdnsProdMounts).ifPresent(containers::add);
    delveSidecarProfile
        .delveSidecar("kdns-dlv", "kdns-dlv.sh", "kdns-dlv-script")
        .ifPresent(containers::add);

    final LinkedHashMap<String, String> floxAnnotations = new LinkedHashMap<>();
    floxAnnotations.put("flox.dev/environment.kdns", floxEnvironment);
    floxAnnotations.put("flox.dev/home.kdns", "/root");
    floxAnnotations.put("flox.dev/uid.kdns", "0");
    floxAnnotations.put("flox.dev/gid.kdns", "0");
    floxAnnotations.putAll(shellSidecar.sidecarAnnotations());

    final List<Object> volumes = new ArrayList<>();
    volumes.add(
        Map.of(
            "name",
            "kdns-dlv-script",
            "configMap",
            Map.of("defaultMode", 493, "name", "kdns-dlv-script")));
    if (!shellSidecar.enabled()) {
      // Prod-only emptyDirs; the shell sidecar provides the shared variants when enabled.
      volumes.add(Map.of("name", "flox-config", "emptyDir", Map.of()));
      volumes.add(Map.of("name", "flox-cache", "emptyDir", Map.of()));
    }
    volumes.addAll(shellSidecar.extraVolumes());

    LinkedHashMap<String, Object> deploymentSpec = new LinkedHashMap<>();
    // Scaled to 0 while the kdns OOM is investigated: the `kdns` busybox
    // container (flox activate -- kdns) OOMKills at both 256Mi and 512Mi with no
    // log output. The flox NRI plugin + env injection work; the runaway memory
    // is intrinsic to the kdns workload itself. Set at the source so RKE2's
    // AddOn auto-deploy can't revert a manual `kubectl scale 0` back to 1.
    // Restore to 1 once the OOM root cause is fixed.
    deploymentSpec.put("replicas", 0);
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
                    packageProfile.templateAnnotations(Map.copyOf(floxAnnotations))),
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
            runtimePodProfile.apply(containers, List.copyOf(volumes), "kdns", Map.of())));

    deployment.addJsonPatch(
        JsonPatch.add("/spec", deploymentSpec),
        JsonPatch.add("/spec/template/spec/containers/0/livenessProbe", null),
        JsonPatch.add("/spec/template/spec/containers/0/readinessProbe", null));
  }
}
