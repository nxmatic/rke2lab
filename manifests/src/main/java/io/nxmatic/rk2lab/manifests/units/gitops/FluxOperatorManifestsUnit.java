// @codebase
package io.nxmatic.rk2lab.manifests.units.gitops;

import io.nxmatic.rk2lab.manifests.AbstractManifestsUnit;
import io.nxmatic.rk2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.profiles.PackageMetadataProfile;
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

  public FluxOperatorManifestsUnit(final Construct scope, final String id) {
    super(scope, id, MANIFEST_UNIT_ID, List.of());

    final String version = componentVersions().fluxOperator();

    // flux-system namespace
    ApiObject namespace =
        new ApiObject(
            this,
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
            this,
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
