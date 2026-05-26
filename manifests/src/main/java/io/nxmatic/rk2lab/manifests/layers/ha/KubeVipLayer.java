// @codebase
package io.nxmatic.rk2lab.manifests.layers.ha;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class KubeVipLayer extends Construct {

  public static final String PATH_PREFIX = "high-availability/kube-vip/";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("high-availability", "kube-vip");

  public KubeVipLayer(final Construct scope, final String id) {
    super(scope, id);

    ApiObject namespace = createNamespace();
    ApiObject serviceAccount = createServiceAccount(namespace);
    ApiObject clusterRole = createClusterRole();
    ApiObject clusterRoleBinding = createClusterRoleBinding(clusterRole, serviceAccount);
    createDaemonSet(namespace, serviceAccount, clusterRoleBinding);
    createService();
  }

  private ApiObject createNamespace() {
    ApiObject namespace =
        new ApiObject(
            this,
            "namespace-kube-vip",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Namespace")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kube-vip")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Namespace|default|${kube-vip-namespace}"))
                        .labels(Map.of("name", "kube-vip"))
                        .build())
                .build());
    return namespace;
  }

  private ApiObject createServiceAccount(final ApiObject namespace) {
    ApiObject serviceAccount =
        new ApiObject(
            this,
            "serviceaccount-kube-vip",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ServiceAccount")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kube-vip")
                        .namespace("kube-vip")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ServiceAccount|${kube-vip-namespace}|kube-vip"))
                        .build())
                .build());
    serviceAccount.addDependency(namespace);
    return serviceAccount;
  }

  private ApiObject createClusterRole() {
    ApiObject clusterRole =
        new ApiObject(
            this,
            "clusterrole-system-kube-vip-role",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRole")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("system:kube-vip-role")
                        .annotations(
                            Map.of(
                                "internal.kpt.dev/upstream-identifier",
                                "rbac.authorization.k8s.io|ClusterRole|default|system:kube-vip-role",
                                "kpt.dev/package-layer",
                                "high-availability",
                                "kpt.dev/package-name",
                                "kube-vip",
                                "rbac.authorization.kubernetes.io/autoupdate",
                                "true"))
                        .build())
                .build());

    clusterRole.addJsonPatch(
        JsonPatch.add(
            "/rules",
            List.of(
                Map.of(
                    "apiGroups",
                    List.of(""),
                    "resources",
                    List.of("services", "services/status", "nodes", "endpoints"),
                    "verbs",
                    List.of("list", "get", "watch", "update")),
                Map.of(
                    "apiGroups",
                    List.of("coordination.k8s.io"),
                    "resources",
                    List.of("leases"),
                    "verbs",
                    List.of("list", "get", "watch", "update", "create")))));

    return clusterRole;
  }

  private ApiObject createClusterRoleBinding(
      final ApiObject clusterRole, final ApiObject serviceAccount) {
    ApiObject clusterRoleBinding =
        new ApiObject(
            this,
            "clusterrolebinding-system-kube-vip-binding",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRoleBinding")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("system:kube-vip-binding")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|ClusterRoleBinding|default|system:kube-vip-binding"))
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
                "system:kube-vip-role")),
        JsonPatch.add(
            "/subjects",
            List.of(
                Map.of("kind", "ServiceAccount", "name", "kube-vip", "namespace", "kube-vip"))));
    return clusterRoleBinding;
  }

  private void createDaemonSet(
      final ApiObject namespace,
      final ApiObject serviceAccount,
      final ApiObject clusterRoleBinding) {
    ApiObject daemonSet =
        new ApiObject(
            this,
            "daemonset-kube-vip-ds",
            ApiObjectProps.builder()
                .apiVersion("apps/v1")
                .kind("DaemonSet")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kube-vip-ds")
                        .namespace("kube-vip")
                        .labels(Map.of("app", "kube-vip"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "apps|DaemonSet|${kube-vip-namespace}|kube-vip-ds"))
                        .build())
                .build());

    daemonSet.addDependency(namespace);
    daemonSet.addDependency(serviceAccount);
    daemonSet.addDependency(clusterRoleBinding);

    daemonSet.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "selector",
                Map.of("matchLabels", Map.of("name", "kube-vip-ds")),
                "template",
                Map.of(
                    "metadata",
                    Map.of(
                        "annotations",
                        packageProfile.packageAnnotationsWithoutUpstream(),
                        "labels",
                        Map.of("app", "kube-vip", "name", "kube-vip-ds")),
                    "spec",
                    Map.of(
                        "affinity",
                        Map.of(
                            "nodeAffinity",
                            Map.of(
                                "requiredDuringSchedulingIgnoredDuringExecution",
                                Map.of(
                                    "nodeSelectorTerms",
                                    List.of(
                                        Map.of(
                                            "matchExpressions",
                                            List.of(
                                                Map.of(
                                                    "key",
                                                    "node-role.kubernetes.io/master",
                                                    "operator",
                                                    "Exists"))),
                                        Map.of(
                                            "matchExpressions",
                                            List.of(
                                                Map.of(
                                                    "key",
                                                    "node-role.kubernetes.io/control-plane",
                                                    "operator",
                                                    "Exists"))))))),
                        "containers",
                        List.of(
                            Map.of(
                                "name",
                                "kube-vip",
                                "image",
                                "ghcr.io/kube-vip/kube-vip:v0.8.7",
                                "imagePullPolicy",
                                "Always",
                                "args",
                                List.of("manager"),
                                "env",
                                List.of(
                                    Map.of("name", "vip_arp", "value", "true"),
                                    Map.of("name", "port", "value", "6443"),
                                    // kube-vip binds the VIP as a secondary IP on an existing
                                    // interface — there is no dedicated `rke2-vip0` link in the
                                    // netplan, despite the historical blueprint slot suggesting
                                    // one. The cluster-internal bridge is `vmnet0` (10.80.0.0/21
                                    // cluster CIDR), and the VIP 10.80.7.10 sits in the
                                    // cluster-vip-cidr 10.80.7.0/24 routed across that bridge —
                                    // so vmnet0 is the only correct binding target.
                                    Map.of("name", "vip_interface", "value", "vmnet0"),
                                    Map.of("name", "vip_cidr", "value", "32"),
                                    Map.of("name", "dns_mode", "value", "first"),
                                    Map.of("name", "cp_enable", "value", "true"),
                                    Map.of("name", "cp_namespace", "value", "kube-system"),
                                    Map.of("name", "svc_enable", "value", "false"),
                                    Map.of("name", "vip_leaderelection", "value", "true"),
                                    Map.of("name", "vip_leaseduration", "value", "5"),
                                    Map.of("name", "vip_renewdeadline", "value", "3"),
                                    Map.of("name", "vip_retryperiod", "value", "1"),
                                    Map.of("name", "address", "value", "10.80.7.10")),
                                "resources",
                                Map.of(
                                    "limits",
                                    Map.of("cpu", "100m", "memory", "128Mi"),
                                    "requests",
                                    Map.of("cpu", "50m", "memory", "64Mi")),
                                "securityContext",
                                Map.of(
                                    "capabilities",
                                    Map.of("add", List.of("NET_ADMIN", "NET_RAW", "SYS_TIME"))))),
                        "hostNetwork",
                        true,
                        "serviceAccountName",
                        "kube-vip",
                        "tolerations",
                        List.of(
                            Map.of("effect", "NoSchedule", "operator", "Exists"),
                            Map.of("effect", "NoExecute", "operator", "Exists")))))));
  }

  private void createService() {
    ApiObject service =
        new ApiObject(
            this,
            "service-control-plane-nodeport",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Service")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("control-plane-nodeport")
                        .namespace("kube-system")
                        .labels(Map.of("backup-service", "true"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Service|kube-system|control-plane-nodeport"))
                        .build())
                .build());

    service.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "externalTrafficPolicy",
                "Cluster",
                "ports",
                List.of(
                    Map.of(
                        "name",
                        "kube-apiserver",
                        "nodePort",
                        30443,
                        "port",
                        6443,
                        "protocol",
                        "TCP",
                        "targetPort",
                        6443)),
                "selector",
                Map.of("component", "kube-apiserver", "tier", "control-plane"),
                "type",
                "NodePort")));
  }
}
