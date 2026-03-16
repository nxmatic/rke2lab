// @codebase
package io.nxmatic.rk2lab.manifests.layers.mesh;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

public final class TailscaleLayer extends Construct {

    public static final String LEGACY_PATH_PREFIX = "mesh/tailscale/";

    private final PackageMetadataProfile packageProfile = new PackageMetadataProfile("mesh", "tailscale");

    public TailscaleLayer(final Construct scope, final String id) {
        super(scope, id);

        ApiObject namespace = createNamespace();
        createSecret(namespace);
        ApiObject helmChart = createHelmChart();
        createConnector(helmChart);
    }

    private ApiObject createNamespace() {
        return new ApiObject(
                this,
                "namespace-tailscale-system",
                ApiObjectProps.builder()
                        .apiVersion("v1")
                        .kind("Namespace")
                        .metadata(ApiObjectMetadata.builder()
                                .name("tailscale-system")
                                .annotations(packageProfile.packageAnnotations("|Namespace|default|${tailscale-namespace}"))
                                .build())
                        .build()
        );
    }

    private ApiObject createHelmChart() {
        ApiObject helmChart = new ApiObject(
                this,
                "helmchart-tailscale-operator",
                ApiObjectProps.builder()
                        .apiVersion("helm.cattle.io/v1")
                        .kind("HelmChart")
                        .metadata(ApiObjectMetadata.builder()
                                .name("tailscale-operator")
                                .namespace("kube-system")
                                .annotations(packageProfile.packageAnnotations("helm.cattle.io|HelmChart|kube-system|tailscale-operator"))
                                .build())
                        .build()
        );

        helmChart.addJsonPatch(JsonPatch.add("/spec", Map.of(
                "chart", "tailscale-operator",
                "createNamespace", true,
                "repo", "https://pkgs.tailscale.com/helmcharts",
                "targetNamespace", "tailscale-system",
                "valuesContent",
                "operatorConfig:\n"
                        + "  hostname: name-tailscale-operator # kpt-set: name-tailscale-operator\n"
                        + "  debug: true",
                "version", "1.82.0"
        )));

        return helmChart;
    }

    private void createConnector(final ApiObject helmChart) {
        ApiObject connector = new ApiObject(
                this,
                "connector-controlplane",
                ApiObjectProps.builder()
                        .apiVersion("tailscale.com/v1alpha1")
                        .kind("Connector")
                        .metadata(ApiObjectMetadata.builder()
                                .name("controlplane")
                                .annotations(Map.of(
                                        "config.kubernetes.io/depends-on", "helm.cattle.io/namespaces/kube-system/HelmChart/tailscale-operator",
                                        "internal.kpt.dev/upstream-identifier", "tailscale.com|Connector|default|controlplane",
                                        "kpt.dev/package-layer", "mesh",
                                        "kpt.dev/package-name", "tailscale"
                                ))
                                .build())
                        .build()
        );
        connector.addDependency(helmChart);

        connector.addJsonPatch(JsonPatch.add("/spec", Map.of(
                "hostname", "bioskop-controlplane",
                "subnetRouter", Map.of(
                        "advertiseRoutes", List.of("10.80.7.10/32", "10.80.0.64/26")
                )
        )));
    }

    private void createSecret(final ApiObject namespace) {
        ApiObject secret = new ApiObject(
                this,
                "secret-operator-oauth",
                ApiObjectProps.builder()
                        .apiVersion("v1")
                        .kind("Secret")
                        .metadata(ApiObjectMetadata.builder()
                                .name("operator-oauth")
                                .namespace("tailscale-system")
                                .labels(Map.of("app.kubernetes.io/replicated", "true"))
                                .annotations(Map.of(
                                        "internal.kpt.dev/upstream-identifier", "|Secret|${tailscale-namespace}|operator-oauth",
                                        "kpt.dev/package-layer", "mesh",
                                        "kpt.dev/package-name", "tailscale",
                                        "replicator.v1.mittwald.de/replicate-from", "kube-system/operator-oauth"
                                ))
                                .build())
                        .build()
        );
        secret.addDependency(namespace);

        secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
    }
}
