// @codebase
package io.nxmatic.rk2lab.manifests.layers.replication;

import io.nxmatic.rk2lab.manifests.layers.common.KptMetadata;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class ReplicatorLayer extends Construct {

  public static final String LEGACY_PATH_PREFIX = "replication/replicator/";

  private static final String LAYER_NAME = "replication";
  private static final String PACKAGE_NAME = "replicator";

  private final KptMetadata kptMetadata = new KptMetadata();

  public ReplicatorLayer(final Construct scope, final String id) {
    super(scope, id);

    ApiObject clusterRole = createClusterRole();
    ApiObject serviceAccount = createServiceAccount();
    createClusterRoleBinding(clusterRole, serviceAccount);
    createDeployment(serviceAccount);
  }

  private ApiObject createClusterRole() {
    ApiObject clusterRole =
        new ApiObject(
            this,
            "clusterrole-kubernetes-replicator",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRole")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kubernetes-replicator")
                        .annotations(
                            kptMetadata.packageAnnotations(
                                LAYER_NAME,
                                PACKAGE_NAME,
                                "rbac.authorization.k8s.io|ClusterRole|default|kubernetes-replicator"))
                        .labels(commonLabels())
                        .build())
                .build());

    clusterRole.addJsonPatch(
        JsonPatch.add(
            "/rules",
            new Object[] {
              Map.of(
                  "apiGroups",
                  new Object[] {""},
                  "resources",
                  new Object[] {"namespaces"},
                  "verbs",
                  new Object[] {"get", "watch", "list"}),
              Map.of(
                  "apiGroups",
                  new Object[] {""},
                  "resources",
                  new Object[] {"secrets", "configmaps", "serviceaccounts"},
                  "verbs",
                  new Object[] {"get", "watch", "list", "create", "update", "patch", "delete"}),
              Map.of(
                  "apiGroups",
                  new Object[] {"rbac.authorization.k8s.io"},
                  "resources",
                  new Object[] {"roles", "rolebindings"},
                  "verbs",
                  new Object[] {"get", "watch", "list", "create", "update", "patch", "delete"})
            }));

    return clusterRole;
  }

  private ApiObject createServiceAccount() {
    ApiObject serviceAccount =
        new ApiObject(
            this,
            "serviceaccount-kubernetes-replicator",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ServiceAccount")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kubernetes-replicator")
                        .namespace("kube-system")
                        .annotations(
                            kptMetadata.packageAnnotations(
                                LAYER_NAME,
                                PACKAGE_NAME,
                                "|ServiceAccount|kube-system|kubernetes-replicator"))
                        .labels(commonLabels())
                        .build())
                .build());

    serviceAccount.addJsonPatch(JsonPatch.add("/automountServiceAccountToken", true));
    return serviceAccount;
  }

  private void createClusterRoleBinding(
      final ApiObject clusterRole, final ApiObject serviceAccount) {
    ApiObject clusterRoleBinding =
        new ApiObject(
            this,
            "clusterrolebinding-kubernetes-replicator",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRoleBinding")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kubernetes-replicator")
                        .annotations(
                            kptMetadata.packageAnnotations(
                                LAYER_NAME,
                                PACKAGE_NAME,
                                "rbac.authorization.k8s.io|ClusterRoleBinding|default|kubernetes-replicator"))
                        .labels(commonLabels())
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
                "kubernetes-replicator")),
        JsonPatch.add(
            "/subjects",
            new Object[] {
              Map.of(
                  "kind",
                  "ServiceAccount",
                  "name",
                  "kubernetes-replicator",
                  "namespace",
                  "kube-system")
            }));
  }

  private void createDeployment(final ApiObject serviceAccount) {
    ApiObject deployment =
        new ApiObject(
            this,
            "deployment-kubernetes-replicator",
            ApiObjectProps.builder()
                .apiVersion("apps/v1")
                .kind("Deployment")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kubernetes-replicator")
                        .namespace("kube-system")
                        .annotations(
                            kptMetadata.packageAnnotations(
                                LAYER_NAME,
                                PACKAGE_NAME,
                                "apps|Deployment|kube-system|kubernetes-replicator"))
                        .labels(commonLabels())
                        .build())
                .build());

    deployment.addDependency(serviceAccount);

    deployment.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "replicas",
                1,
                "revisionHistoryLimit",
                10,
                "selector",
                Map.of(
                    "matchLabels",
                    Map.of(
                        "app.kubernetes.io/instance",
                        "kubernetes-replicator",
                        "app.kubernetes.io/name",
                        "kubernetes-replicator")),
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
                            "app.kubernetes.io/instance",
                            "kubernetes-replicator",
                            "app.kubernetes.io/name",
                            "kubernetes-replicator")),
                    "spec",
                    Map.of(
                        "automountServiceAccountToken",
                        true,
                        "serviceAccountName",
                        "kubernetes-replicator",
                        "containers",
                        new Object[] {
                          Map.of(
                              "name",
                              "kubernetes-replicator",
                              "image",
                              "quay.io/mittwald/kubernetes-replicator:v2.12.2",
                              "imagePullPolicy",
                              "Always",
                              "args",
                              new Object[] {
                                "-replicate-secrets=true",
                                "-replicate-configmaps=true",
                                "-replicate-roles=true",
                                "-replicate-role-bindings=true",
                                "-replicate-service-accounts=true"
                              },
                              "ports",
                              new Object[] {
                                Map.of("containerPort", 9102, "name", "health", "protocol", "TCP")
                              },
                              "livenessProbe",
                              Map.of(
                                  "httpGet",
                                  Map.of("path", "/healthz", "port", "health"),
                                  "initialDelaySeconds",
                                  60,
                                  "periodSeconds",
                                  10,
                                  "timeoutSeconds",
                                  1,
                                  "successThreshold",
                                  1,
                                  "failureThreshold",
                                  3),
                              "readinessProbe",
                              Map.of(
                                  "httpGet",
                                  Map.of("path", "/readyz", "port", "health"),
                                  "initialDelaySeconds",
                                  60,
                                  "periodSeconds",
                                  10,
                                  "timeoutSeconds",
                                  1,
                                  "successThreshold",
                                  1,
                                  "failureThreshold",
                                  3),
                              "resources",
                              Map.of(),
                              "securityContext",
                              Map.of())
                        },
                        "securityContext",
                        Map.of())))));
  }

  private Map<String, String> commonLabels() {
    return Map.of(
        "app.kubernetes.io/instance",
        "kubernetes-replicator",
        "app.kubernetes.io/managed-by",
        "Helm",
        "app.kubernetes.io/name",
        "kubernetes-replicator",
        "app.kubernetes.io/version",
        "v2.12.2",
        "helm.sh/chart",
        "kubernetes-replicator-2.12.2");
  }
}
