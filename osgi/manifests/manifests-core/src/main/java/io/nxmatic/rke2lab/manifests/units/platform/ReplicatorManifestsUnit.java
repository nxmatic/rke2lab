package io.nxmatic.rke2lab.manifests.units.platform;

import io.nxmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.nxmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.nxmatic.rke2lab.manifests.ManifestsUnitContext;
import io.nxmatic.rke2lab.manifests.bridge.ManifestAnnotations;
import io.nxmatic.rke2lab.manifests.bridge.ManifestDomainCatalog;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class ReplicatorManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.PLATFORM + "/replicator";

  private static final String DOMAIN_NAME = "platform";
  private static final String PACKAGE_NAME = "replicator";

  private final ManifestAnnotations manifestAnnotations = new ManifestAnnotations();

  private String replicatorVersion;

  public ReplicatorManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    this.replicatorVersion =
        ManifestSynthesisContext.current().componentVersions().kubernetesReplicator();

    createSourceNamespace(scope);
    ApiObject clusterRole = createClusterRole(scope);
    ApiObject serviceAccount = createServiceAccount(scope);
    createClusterRoleBinding(scope, clusterRole, serviceAccount);
    createDeployment(scope, serviceAccount);
  }

  private void createSourceNamespace(final Construct scope) {
    new ApiObject(
        scope,
        "namespace-rke2lab-replicator-source",
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name("rke2lab-replicator-source")
                    .annotations(manifestAnnotations.packageAnnotations(DOMAIN_NAME, PACKAGE_NAME))
                    .labels(
                        Map.of(
                            "app.kubernetes.io/name",
                            "rke2lab-replicator-source",
                            "app.kubernetes.io/managed-by",
                            "rke2lab"))
                    .build())
            .build());
  }

  private ApiObject createClusterRole(final Construct scope) {
    ApiObject clusterRole =
        new ApiObject(
            scope,
            "clusterrole-kubernetes-replicator",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRole")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kubernetes-replicator")
                        .annotations(
                            manifestAnnotations.packageAnnotations(DOMAIN_NAME, PACKAGE_NAME))
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

  private ApiObject createServiceAccount(final Construct scope) {
    ApiObject serviceAccount =
        new ApiObject(
            scope,
            "serviceaccount-kubernetes-replicator",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ServiceAccount")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kubernetes-replicator")
                        .namespace("kube-system")
                        .annotations(
                            manifestAnnotations.packageAnnotations(DOMAIN_NAME, PACKAGE_NAME))
                        .labels(commonLabels())
                        .build())
                .build());

    serviceAccount.addJsonPatch(JsonPatch.add("/automountServiceAccountToken", true));
    return serviceAccount;
  }

  private void createClusterRoleBinding(
      final Construct scope, final ApiObject clusterRole, final ApiObject serviceAccount) {
    ApiObject clusterRoleBinding =
        new ApiObject(
            scope,
            "clusterrolebinding-kubernetes-replicator",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRoleBinding")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kubernetes-replicator")
                        .annotations(
                            manifestAnnotations.packageAnnotations(DOMAIN_NAME, PACKAGE_NAME))
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

  private void createDeployment(final Construct scope, final ApiObject serviceAccount) {
    ApiObject deployment =
        new ApiObject(
            scope,
            "deployment-kubernetes-replicator",
            ApiObjectProps.builder()
                .apiVersion("apps/v1")
                .kind("Deployment")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kubernetes-replicator")
                        .namespace("kube-system")
                        .annotations(
                            manifestAnnotations.packageAnnotations(DOMAIN_NAME, PACKAGE_NAME))
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
                        manifestAnnotations.packageAnnotations(DOMAIN_NAME, PACKAGE_NAME),
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
                              "quay.io/mittwald/kubernetes-replicator:" + replicatorVersion,
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
        replicatorVersion,
        "helm.sh/chart",
        "kubernetes-replicator-" + replicatorVersion.replaceFirst("^v", ""));
  }
}
