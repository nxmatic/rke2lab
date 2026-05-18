// @codebase
package io.nxmatic.rk2lab.manifests.layers.gitops;

import io.nxmatic.rk2lab.manifests.layers.common.KptMetadata;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class FluxInstanceLayer extends Construct {

  private static final String LAYER_NAME = "gitops";
  private static final String PACKAGE_NAME = "flux-instance";

  private final KptMetadata kptMetadata = new KptMetadata();

  public FluxInstanceLayer(final Construct scope, final String id) {
    super(scope, id);
    createFluxInstance();
  }

  private void createFluxInstance() {
    ApiObject fluxInstance =
        new ApiObject(
            this,
            "fluxinstance-flux",
            ApiObjectProps.builder()
                .apiVersion("fluxcd.controlplane.io/v1")
                .kind("FluxInstance")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("flux")
                        .namespace("flux-system")
                        .annotations(
                            kptMetadata.packageAnnotations(
                                LAYER_NAME,
                                PACKAGE_NAME,
                                "fluxcd.controlplane.io|FluxInstance|flux-system|flux"))
                        .labels(
                            Map.of(
                                "app.kubernetes.io/instance",
                                "flux-instance",
                                "app.kubernetes.io/managed-by",
                                "Helm",
                                "app.kubernetes.io/name",
                                "flux-instance",
                                "app.kubernetes.io/version",
                                "v0.36.0",
                                "helm.sh/chart",
                                "flux-instance-0.36.0"))
                        .build())
                .build());

    fluxInstance.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "cluster",
                Map.of(
                    "domain",
                    "cluster.local",
                    "multitenant",
                    false,
                    "networkPolicy",
                    true,
                    "tenantDefaultServiceAccount",
                    "default",
                    "type",
                    "kubernetes"),
                "components",
                new Object[] {
                  "source-controller",
                  "kustomize-controller",
                  "helm-controller",
                  "notification-controller"
                },
                "distribution",
                Map.of(
                    "artifact",
                    "oci://ghcr.io/controlplaneio-fluxcd/flux-operator-manifests:latest",
                    "registry",
                    "ghcr.io/fluxcd",
                    "version",
                    "2.x"),
                "kustomize",
                Map.of("patches", new Object[] {}))));
  }
}
