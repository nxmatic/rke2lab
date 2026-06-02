// @codebase
package io.nxmatic.rk2lab.manifests.components.gitops;

import io.nxmatic.rk2lab.manifests.ManifestAnnotations;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class FluxInstanceComponent extends Construct {

  private static final String LAYER_NAME = "gitops";
  private static final String PACKAGE_NAME = "flux-instance";

  private final ManifestAnnotations manifestAnnotations = new ManifestAnnotations();

  private final String fluxOperatorVersion;

  public FluxInstanceComponent(
      final Construct scope, final String id, final String fluxOperatorVersion) {
    super(scope, id);
    this.fluxOperatorVersion = fluxOperatorVersion;
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
                            manifestAnnotations.packageAnnotations(LAYER_NAME, PACKAGE_NAME))
                        .labels(
                            Map.of(
                                "app.kubernetes.io/instance",
                                "flux-instance",
                                "app.kubernetes.io/managed-by",
                                "Helm",
                                "app.kubernetes.io/name",
                                "flux-instance",
                                "app.kubernetes.io/version",
                                fluxOperatorVersion,
                                "helm.sh/chart",
                                "flux-instance-" + fluxOperatorVersion.replaceFirst("^v", "")))
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
