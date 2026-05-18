// @codebase
package io.nxmatic.rk2lab.manifests.layers.cicd;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class TektonDashboardLayer extends Construct {

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cicd", "tekton-dashboard");

  public TektonDashboardLayer(final Construct scope, final String id) {
    super(scope, id);

    createMiddleware();
    createIngress();
  }

  private ApiObject createMiddleware() {
    ApiObject middleware =
        new ApiObject(
            this,
            "middleware-tekton-dashboard-strip",
            ApiObjectProps.builder()
                .apiVersion("traefik.containo.us/v1alpha1")
                .kind("Middleware")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("tekton-dashboard-strip")
                        .namespace("tekton-pipelines")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "traefik.containo.us|Middleware|tekton-pipelines|tekton-dashboard-strip"))
                        .build())
                .build());

    middleware.addJsonPatch(
        JsonPatch.add("/spec", Map.of("stripPrefix", Map.of("prefixes", List.of("/tekton")))));
    return middleware;
  }

  private ApiObject createIngress() {
    ApiObject ingress =
        new ApiObject(
            this,
            "ingress-tekton-dashboard",
            ApiObjectProps.builder()
                .apiVersion("networking.k8s.io/v1")
                .kind("Ingress")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("tekton-dashboard")
                        .namespace("tekton-pipelines")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "networking.k8s.io|Ingress|tekton-pipelines|tekton-dashboard",
                                Map.of(
                                    "traefik.ingress.kubernetes.io/router.entrypoints",
                                    "web",
                                    "traefik.ingress.kubernetes.io/router.middlewares",
                                    "tekton-dashboard-strip@kubernetescrd",
                                    "kube-vip.io/address-pool",
                                    "lan-lb")))
                        .build())
                .build());

    ingress.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "ingressClassName",
                "traefik",
                "rules",
                List.of(
                    Map.of(
                        "host",
                        "bioskop-web-proxy.lan",
                        "http",
                        Map.of(
                            "paths",
                            List.of(
                                Map.of(
                                    "path",
                                    "/tekton",
                                    "pathType",
                                    "Prefix",
                                    "backend",
                                    Map.of(
                                        "service",
                                        Map.of(
                                            "name",
                                            "tekton-dashboard",
                                            "port",
                                            Map.of("number", 9097)))))))))));
    return ingress;
  }
}
