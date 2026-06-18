// @codebase
package io.nxmatic.rke2lab.manifests.units.gitops;

import io.nxmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.nxmatic.rke2lab.manifests.ManifestsUnitContext;
import io.nxmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class FluxOperatorManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.GITOPS + "/flux-operator";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("gitops", "flux-operator");

  public FluxOperatorManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    createManifests(scope);
  }

  private void createManifests(final Construct scope) {
    final String version = ManifestSynthesisContext.current().componentVersions().fluxOperator();

    // flux-system namespace
    ApiObject namespace =
        new ApiObject(
            scope,
            "namespace-flux-system",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Namespace")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("flux-system")
                        .annotations(packageProfile.packageAnnotations("|Namespace||flux-system"))
                        .labels(
                            Map.of(
                                "app.kubernetes.io/name", "flux-system",
                                "app.kubernetes.io/managed-by", "rke2lab"))
                        .build())
                .build());

    // Flux Operator HelmChart
    ApiObject helmChart =
        new ApiObject(
            scope,
            "helmchart-flux-operator",
            ApiObjectProps.builder()
                .apiVersion("helm.cattle.io/v1")
                .kind("HelmChart")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("flux-operator")
                        .namespace("kube-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "helm.cattle.io|HelmChart|kube-system|flux-operator"))
                        .build())
                .build());

    helmChart.addDependency(namespace);

    helmChart.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "chart",
                "oci://ghcr.io/controlplaneio-fluxcd/charts/flux-operator",
                "version",
                version,
                "targetNamespace",
                "flux-system",
                "valuesContent",
                """
                installCRDs: true
                logLevel: info
                priorityClassName: system-cluster-critical
                serviceMonitor:
                  create: false
                  interval: 60s
                  scrapeTimeout: 30s
                """)));
  }
}
