package io.nxmatic.rk2lab.manifests.layers.certmanager;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class CertManagerLayer extends Construct {

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cert-manager", "cert-manager");

  public CertManagerLayer(final Construct scope, final String id, final String version) {
    super(scope, id);

    ApiObject namespace =
        new ApiObject(
            this,
            "namespace-cert-manager",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Namespace")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("cert-manager")
                        .annotations(packageProfile.packageAnnotations("|Namespace||cert-manager"))
                        .labels(
                            Map.of(
                                "app.kubernetes.io/name", "cert-manager",
                                "app.kubernetes.io/managed-by", "rke2lab"))
                        .build())
                .build());

    // RKE2 helm-controller watches HelmChart CRs in kube-system and installs the chart into
    // targetNamespace. cert-manager runs cluster-wide from its own namespace and provisions the
    // webhook-serving certs (e.g. capi-operator-webhook-service-cert) that the CAPI and Tekton
    // operators mount — without it those operator pods hang on FailedMount.
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

    helmChart.addDependency(namespace);

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
                "cert-manager",
                "valuesContent",
                """
                crds:
                  enabled: true
                """)));
  }
}
