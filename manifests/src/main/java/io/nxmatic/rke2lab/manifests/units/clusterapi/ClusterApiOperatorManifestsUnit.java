package io.nxmatic.rke2lab.manifests.units.clusterapi;

import io.nxmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.nxmatic.rke2lab.manifests.ManifestsUnitContext;
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

  /** Exploded package dir (relative to the cluster-api domain); diverges from the id segment. */
  public static final String OUTPUT_DIR = "cluster-api-operator";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(ManifestDomainCatalog.CLUSTER_API, OUTPUT_DIR);

  public ClusterApiOperatorManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public String outputDir() {
    return OUTPUT_DIR;
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String operatorVersion =
        ManifestSynthesisContext.current().componentVersions().clusterApiOperator();
    final String coreVersion = ManifestSynthesisContext.current().componentVersions().capiCore();
    final String incusProviderVersion =
        ManifestSynthesisContext.current().componentVersions().capiIncusProvider();
    final String rke2ProviderVersion =
        ManifestSynthesisContext.current().componentVersions().capiRke2Provider();

    final String operatorReleaseResource =
        "/upstream/clusterapi/operator/release-" + operatorVersion + ".yaml";
    new UpstreamYamlInclusion(scope, operatorReleaseResource, packageProfile);

    createProviderNamespaces(scope);
    createCoreProvider(scope, coreVersion);
    createInfrastructureProvider(scope, incusProviderVersion);
    createControlPlaneProvider(scope, rke2ProviderVersion);
  }

  private void createProviderNamespaces(final Construct scope) {
    for (String namespace : new String[] {"capi-system", "capn-system", "caprke2-system"}) {
      new ApiObject(
          scope,
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

  private void createCoreProvider(final Construct scope, final String version) {
    ApiObject provider =
        new ApiObject(
            scope,
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

  private void createInfrastructureProvider(final Construct scope, final String version) {
    ApiObject provider =
        new ApiObject(
            scope,
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

  private void createControlPlaneProvider(final Construct scope, final String version) {
    ApiObject provider =
        new ApiObject(
            scope,
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
