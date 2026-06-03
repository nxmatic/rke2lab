// @codebase
package io.nxmatic.rk2lab.manifests.units.networking;

import io.nxmatic.rk2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class EnvoyGatewayComponent extends Construct {

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("networking", "envoy-gateway");

  private final String envoyGatewayVersion;

  public EnvoyGatewayComponent(
      final Construct scope, final String id, final String envoyGatewayVersion) {
    super(scope, id);
    this.envoyGatewayVersion = envoyGatewayVersion;

    ApiObject namespace = createNamespace();
    ApiObject serviceAccount = createServiceAccount(namespace);
    ApiObject clusterRoleBinding = createClusterRoleBinding(serviceAccount);
    ApiObject configMap = createInstallerScriptConfigMap(namespace);
    createInstallerJob(namespace, serviceAccount, configMap, clusterRoleBinding);
    createGatewayClass();
  }

  private ApiObject createClusterRoleBinding(final ApiObject serviceAccount) {
    ApiObject clusterRoleBinding =
        new ApiObject(
            this,
            "clusterrolebinding-envoy-gateway-installer",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRoleBinding")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("envoy-gateway-installer")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|ClusterRoleBinding|default|envoy-gateway-installer"))
                        .build())
                .build());

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
                "cluster-admin")),
        JsonPatch.add(
            "/subjects",
            List.of(
                Map.of(
                    "kind",
                    "ServiceAccount",
                    "name",
                    "envoy-gateway-installer",
                    "namespace",
                    "envoy-gateway-system"))));
    return clusterRoleBinding;
  }

  private void createGatewayClass() {
    ApiObject gatewayClass =
        new ApiObject(
            this,
            "gatewayclass-envoy",
            ApiObjectProps.builder()
                .apiVersion("gateway.networking.k8s.io/v1")
                .kind("GatewayClass")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("envoy")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "gateway.networking.k8s.io|GatewayClass|default|envoy"))
                        .build())
                .build());

    gatewayClass.addJsonPatch(
        JsonPatch.add(
            "/spec", Map.of("controllerName", "gateway.envoyproxy.io/gatewayclass-controller")));
  }

  private ApiObject createNamespace() {
    return new ApiObject(
        this,
        "namespace-envoy-gateway-system",
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name("envoy-gateway-system")
                    .annotations(
                        packageProfile.packageAnnotations(
                            "|Namespace|default|${envoy-gateway-namespace}"))
                    .build())
            .build());
  }

  private ApiObject createInstallerScriptConfigMap(final ApiObject namespace) {
    ApiObject configMap =
        new ApiObject(
            this,
            "configmap-envoy-gateway-installer-script",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("envoy-gateway-installer-script")
                        .namespace("envoy-gateway-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ConfigMap|${envoy-gateway-namespace}|envoy-gateway-installer-script"))
                        .build())
                .build());
    configMap.addDependency(namespace);

    configMap.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "install.sh",
                "#!/usr/bin/env -S bash -exuo pipefail\n\n"
                    + ": \"[i] Installing Envoy Gateway ${ENVOY_GATEWAY_VERSION}...\"\n"
                    + "kubectl apply \\\n"
                    + "  --server-side \\\n"
                    + "  --force-conflicts \\\n"
                    + "  -f \"https://github.com/envoyproxy/gateway/releases/download/${ENVOY_GATEWAY_VERSION}/install.yaml\"\n\n"
                    + ": \"[i] Waiting for Envoy Gateway deployment...\"\n"
                    + "kubectl wait --timeout=300s \\\n"
                    + "  --namespace \"${ENVOY_GATEWAY_NAMESPACE}\" \\\n"
                    + "  --for=condition=available \\\n"
                    + "  deployment/envoy-gateway\n")));
    return configMap;
  }

  private ApiObject createServiceAccount(final ApiObject namespace) {
    ApiObject serviceAccount =
        new ApiObject(
            this,
            "serviceaccount-envoy-gateway-installer",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ServiceAccount")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("envoy-gateway-installer")
                        .namespace("envoy-gateway-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ServiceAccount|${envoy-gateway-namespace}|envoy-gateway-installer"))
                        .build())
                .build());
    serviceAccount.addDependency(namespace);
    return serviceAccount;
  }

  private void createInstallerJob(
      final ApiObject namespace,
      final ApiObject serviceAccount,
      final ApiObject configMap,
      final ApiObject clusterRoleBinding) {
    ApiObject job =
        new ApiObject(
            this,
            "job-envoy-gateway-installer",
            ApiObjectProps.builder()
                .apiVersion("batch/v1")
                .kind("Job")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("envoy-gateway-installer")
                        .namespace("envoy-gateway-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "batch|Job|${envoy-gateway-namespace}|envoy-gateway-installer"))
                        .build())
                .build());

    job.addDependency(namespace);
    job.addDependency(serviceAccount);
    job.addDependency(configMap);
    job.addDependency(clusterRoleBinding);

    job.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "backoffLimit",
                3,
                "template",
                Map.of(
                    "metadata",
                    Map.of("annotations", packageProfile.packageAnnotationsWithoutUpstream()),
                    "spec",
                    Map.of(
                        "containers",
                        List.of(
                            Map.of(
                                "name",
                                "installer",
                                "image",
                                "alpine/k8s:1.29.8",
                                "command",
                                List.of("sh", "/scripts/install.sh"),
                                "env",
                                List.of(
                                    Map.of(
                                        "name",
                                        "ENVOY_GATEWAY_VERSION",
                                        "value",
                                        envoyGatewayVersion),
                                    Map.of(
                                        "name",
                                        "ENVOY_GATEWAY_NAMESPACE",
                                        "value",
                                        "envoy-gateway-system")),
                                "resources",
                                Map.of(
                                    "limits",
                                    Map.of(
                                        "cpu",
                                        "200m",
                                        "ephemeral-storage",
                                        "256Mi",
                                        "memory",
                                        "256Mi"),
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
                                        "installer-script",
                                        "readOnly",
                                        true)))),
                        "restartPolicy",
                        "OnFailure",
                        "serviceAccountName",
                        "envoy-gateway-installer",
                        "volumes",
                        List.of(
                            Map.of(
                                "name",
                                "installer-script",
                                "configMap",
                                Map.of(
                                    "name",
                                    "envoy-gateway-installer-script",
                                    "defaultMode",
                                    493))))),
                "ttlSecondsAfterFinished",
                300)));
  }
}
