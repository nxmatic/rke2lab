// @codebase
package io.seedmatic.rke2lab.manifests.units.cicd;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Tekton dashboard ingress + strip middleware. NOTE: renders its resources as {@code Map.of} blobs
 * across private {@code createXxx} helpers — a de-soup candidate (see
 * docs/architecture/manifests/manifests-unit-lifecycle.adoc § Known debt).
 */
public final class TektonDashboardManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CICD + "/tekton-dashboard";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cicd", "tekton-dashboard");

  public TektonDashboardManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of(TektonPipelinesManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    createMiddleware(scope);
    createIngress(scope, context.nodeEnvContext().bootstrapIdentity().clusterName());
  }

  private ApiObject createMiddleware(final Construct scope) {
    ApiObject middleware =
        new ApiObject(
            scope,
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

  private ApiObject createIngress(final Construct scope, final String clusterName) {
    ApiObject ingress =
        new ApiObject(
            scope,
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
                        clusterName + "-web-proxy.lan",
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
