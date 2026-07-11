package io.nxmatic.rke2lab.manifests.units.platform;

import io.nxmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.nxmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.nxmatic.rke2lab.manifests.ManifestsUnitContext;
import io.nxmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class CertManagerManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.PLATFORM + "/cert-manager";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("platform", "cert-manager");

  public CertManagerManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String version = ManifestSynthesisContext.current().componentVersions().certManager();

    // RKE2 helm-controller watches HelmChart CRs in kube-system and installs the chart into
    // targetNamespace. cert-manager is cluster-wide infrastructure deployed to kube-system and
    // provisions webhook-serving certs (e.g. capi-operator-webhook-service-cert) that the CAPI
    // and Tekton operators mount — without it those operator pods hang on FailedMount.
    ApiObject helmChart =
        new ApiObject(
            scope,
            "helmchart-cert-manager",
            ApiObjectProps.builder()
                .apiVersion("helm.cattle.io/v1")
                .kind("HelmChart")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("cert-manager")
                        .namespace("kube-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "helm.cattle.io|HelmChart|kube-system|cert-manager"))
                        .build())
                .build());

    helmChart.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "repo",
                "https://charts.jetstack.io",
                "chart",
                "cert-manager",
                "version",
                version,
                "targetNamespace",
                "kube-system",
                "valuesContent",
                """
                crds:
                  enabled: true
                """)));
  }
}
