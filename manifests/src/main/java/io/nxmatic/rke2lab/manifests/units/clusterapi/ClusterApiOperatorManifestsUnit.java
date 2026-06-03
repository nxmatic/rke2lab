package io.nxmatic.rke2lab.manifests.units.clusterapi;

import io.nxmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.nxmatic.rke2lab.manifests.upstream.UpstreamYamlInclusion;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class ClusterApiOperatorManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CLUSTER_API + "/operator";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cluster-api", "cluster-api-operator");

  public ClusterApiOperatorManifestsUnit(Construct scope, String id) {
    super(scope, id, MANIFEST_UNIT_ID, List.of());

    final String operatorVersion = componentVersions().clusterApiOperator();
    final String coreVersion = componentVersions().capiCore();
    final String incusProviderVersion = componentVersions().capiIncusProvider();
    final String rke2ProviderVersion = componentVersions().capiRke2Provider();

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
