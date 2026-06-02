// @codebase
package io.nxmatic.rk2lab.manifests.components.mesh;

import io.nxmatic.rk2lab.manifests.profiles.PackageMetadataProfile;
import io.nxmatic.rk2lab.manifests.registry.ManifestUnitReferenceRegistry;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class TailscaleComponent extends Construct {

  private static final String TAILSCALE_NAMESPACE = MeshRefs.MESH_SYSTEM_NAMESPACE.name();

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("mesh", "tailscale");

  private final ManifestUnitReferenceRegistry registry;

  public TailscaleComponent(final Construct scope, final String id) {
    this(scope, id, null);
  }

  public TailscaleComponent(
      final Construct scope, final String id, final ManifestUnitReferenceRegistry registry) {
    super(scope, id);
    this.registry = registry;

    ApiObject namespace = resolveNamespace();
    createSecret(namespace);
    ApiObject helmChart = createHelmChart(namespace);
    createConnector(helmChart);
  }

  private ApiObject resolveNamespace() {
    if (registry != null) {
      return registry.require(MeshRefs.MESH_SYSTEM_NAMESPACE);
    }
    return createNamespace();
  }

  private ApiObject createNamespace() {
    return new ApiObject(
        this,
        "namespace-" + TAILSCALE_NAMESPACE,
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name(TAILSCALE_NAMESPACE)
                    .annotations(
                        packageProfile.packageAnnotations(
                            "|Namespace|default|${tailscale-namespace}"))
                    .build())
            .build());
  }

  private ApiObject createHelmChart(final ApiObject namespace) {
    ApiObject helmChart =
        new ApiObject(
            this,
            "helmchart-tailscale-operator",
            ApiObjectProps.builder()
                .apiVersion("helm.cattle.io/v1")
                .kind("HelmChart")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("tailscale-operator")
                        .namespace(TAILSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "helm.cattle.io|HelmChart|${tailscale-namespace}|tailscale-operator"))
                        .build())
                .build());

    helmChart.addDependency(namespace);

    helmChart.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "chart",
                "tailscale-operator",
                "createNamespace",
                true,
                "repo",
                "https://pkgs.tailscale.com/helmcharts",
                "targetNamespace",
                TAILSCALE_NAMESPACE,
                "valuesContent",
                "operatorConfig:\n"
                    + "  hostname: name-tailscale-operator # kpt-set: name-tailscale-operator\n"
                    + "  debug: true",
                "version",
                "1.82.0")));

    return helmChart;
  }

  private void createConnector(final ApiObject helmChart) {
    ApiObject connector =
        new ApiObject(
            this,
            "connector-controlplane",
            ApiObjectProps.builder()
                .apiVersion("tailscale.com/v1alpha1")
                .kind("Connector")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("controlplane")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "",
                                Map.of(
                                    "config.kubernetes.io/depends-on",
                                    "helm.cattle.io/namespaces/"
                                        + TAILSCALE_NAMESPACE
                                        + "/HelmChart/tailscale-operator")))
                        .build())
                .build());
    connector.addDependency(helmChart);

    connector.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "hostname",
                "bioskop-controlplane",
                "subnetRouter",
                Map.of("advertiseRoutes", List.of("10.80.7.10/32", "10.80.0.64/26")))));
  }

  private void createSecret(final ApiObject namespace) {
    ApiObject secret =
        new ApiObject(
            this,
            "secret-operator-oauth",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("operator-oauth")
                        .namespace(TAILSCALE_NAMESPACE)
                        .labels(Map.of("app.kubernetes.io/replicated", "true"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "",
                                Map.of(
                                    "replicator.v1.mittwald.de/replicate-from",
                                    "rke2lab-replicator-source/operator-oauth")))
                        .build())
                .build());
    secret.addDependency(namespace);

    secret.addJsonPatch(
        JsonPatch.add("/type", "Opaque"),
        JsonPatch.add("/stringData", Map.of("client_id", "", "client_secret", "")));
  }
}
