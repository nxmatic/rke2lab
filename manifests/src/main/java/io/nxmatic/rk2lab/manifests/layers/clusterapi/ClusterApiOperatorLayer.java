package io.nxmatic.rk2lab.manifests.layers.clusterapi;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import io.nxmatic.rk2lab.manifests.layers.common.upstream.UpstreamYamlInclusion;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class ClusterApiOperatorLayer extends Construct {

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cluster-api", "cluster-api-operator");

  public ClusterApiOperatorLayer(
      final Construct scope,
      final String id,
      final String operatorVersion,
      final String coreVersion,
      final String incusProviderVersion,
      final String rke2ProviderVersion) {
    super(scope, id);

    final String operatorReleaseResource =
        "/upstream/clusterapi/operator/release-" + operatorVersion + ".yaml";
    new UpstreamYamlInclusion(this, operatorReleaseResource, packageProfile);

    createProviderNamespaces();
    createCoreProvider(coreVersion);
    createInfrastructureProvider(incusProviderVersion);
    createControlPlaneProvider(rke2ProviderVersion);
  }

  private void createProviderNamespaces() {
    for (String namespace : new String[] {"capi-system", "capn-system", "caprke2-system"}) {
      new ApiObject(
          this,
          "namespace-" + namespace,
          ApiObjectProps.builder()
              .apiVersion("v1")
              .kind("Namespace")
              .metadata(
                  ApiObjectMetadata.builder()
                      .name(namespace)
                      .annotations(packageProfile.packageAnnotations(namespace))
                      .build())
              .build());
    }
  }

  private void createCoreProvider(final String version) {
    ApiObject provider =
        new ApiObject(
            this,
            "coreprovider-cluster-api",
            ApiObjectProps.builder()
                .apiVersion("operator.cluster.x-k8s.io/v1alpha2")
                .kind("CoreProvider")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("cluster-api")
                        .namespace("capi-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "operator.cluster.x-k8s.io|CoreProvider|capi-system|cluster-api"))
                        .build())
                .build());

    provider.addJsonPatch(JsonPatch.add("/spec", Map.of("version", version)));
  }

  private void createInfrastructureProvider(final String version) {
    ApiObject provider =
        new ApiObject(
            this,
            "infrastructureprovider-incus",
            ApiObjectProps.builder()
                .apiVersion("operator.cluster.x-k8s.io/v1alpha2")
                .kind("InfrastructureProvider")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("incus")
                        .namespace("capn-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "operator.cluster.x-k8s.io|InfrastructureProvider|capn-system|incus"))
                        .build())
                .build());

    provider.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "version",
                version,
                "fetchConfig",
                Map.of("url", "https://github.com/nxmatic/cluster-api-provider-incus/releases"))));
  }

  private void createControlPlaneProvider(final String version) {
    ApiObject provider =
        new ApiObject(
            this,
            "controlplaneprovider-rke2",
            ApiObjectProps.builder()
                .apiVersion("operator.cluster.x-k8s.io/v1alpha2")
                .kind("ControlPlaneProvider")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("rke2")
                        .namespace("caprke2-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "operator.cluster.x-k8s.io|ControlPlaneProvider|caprke2-system|rke2"))
                        .build())
                .build());

    provider.addJsonPatch(
        JsonPatch.add(
            "/spec", Map.of("version", version, "configSecret", Map.of("name", "rke2-config"))));
  }
}
