package io.nxmatic.rk2lab.manifests.units.platform;

import io.nxmatic.rk2lab.manifests.profiles.PackageMetadataProfile;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class CertManagerComponent extends Construct {

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("platform", "cert-manager");

  public CertManagerComponent(final Construct scope, final String id, final String version) {
    super(scope, id);

    // RKE2 helm-controller watches HelmChart CRs in kube-system and installs the chart into
    // targetNamespace. cert-manager is cluster-wide infrastructure deployed to kube-system and
    // provisions webhook-serving certs (e.g. capi-operator-webhook-service-cert) that the CAPI
    // and Tekton operators mount — without it those operator pods hang on FailedMount.
    ApiObject helmChart =
        new ApiObject(
            this,
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
