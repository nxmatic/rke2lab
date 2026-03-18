// @codebase
package io.nxmatic.rk2lab.manifests.layers.gitops;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class PorchResourcesLayer extends Construct {

  public static final String LEGACY_PATH_PREFIX = "gitops/porch-resources/";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("gitops", "porch-resources");

  public PorchResourcesLayer(final Construct scope, final String id) {
    super(scope, id);

    ApiObject catalogRepo = createCatalogRepository();
    ApiObject stateRepo = createStateRepository();

    createNetworkingCiliumPackageVariant(catalogRepo, stateRepo);
    createNetworkingEnvoyGatewayPackageVariant(catalogRepo, stateRepo);
    createHaKubeVipPackageVariant(catalogRepo, stateRepo);
    createMeshHeadscalePackageVariant(catalogRepo, stateRepo);
    createMeshTailscalePackageVariant(catalogRepo, stateRepo);
    createRuntimeFloxPackageVariant(catalogRepo, stateRepo);
    createStorageOpenebsZfsPackageVariant(catalogRepo, stateRepo);
  }

  private ApiObject createCatalogRepository() {
    ApiObject repository =
        new ApiObject(
            this,
            "repository-bioskop-catalog",
            ApiObjectProps.builder()
                .apiVersion("config.porch.kpt.dev/v1alpha1")
                .kind("Repository")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("bioskop-catalog")
                        .namespace("porch-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "config.porch.kpt.dev|Repository|porch-system|${cluster-name}-catalog"))
                        .build())
                .build());

    repository.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "deployment",
                true,
                "description",
                "Catalog of curated kpt packages tracked in rke2lab",
                "git",
                Map.of(
                    "branch",
                    "main",
                    "directory",
                    "catalog",
                    "repo",
                    "https://github.com/nxmatic/rke2lab.git",
                    "secretRef",
                    "${github-secret}"),
                "type",
                "git")));

    return repository;
  }

  private ApiObject createStateRepository() {
    ApiObject repository =
        new ApiObject(
            this,
            "repository-bioskop-state",
            ApiObjectProps.builder()
                .apiVersion("config.porch.kpt.dev/v1alpha1")
                .kind("Repository")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("bioskop-state")
                        .namespace("porch-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "config.porch.kpt.dev|Repository|porch-system|cluster-name-state"))
                        .build())
                .build());

    repository.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "deployment",
                true,
                "description",
                "Rendered Porch package state for bioskop cluster",
                "git",
                Map.of(
                    "branch",
                    "main",
                    "createBranch",
                    true,
                    "directory",
                    "rke2/bioskop/catalog",
                    "repo",
                    "https://github.com/nxmatic/rke2lab.git",
                    "secretRef",
                    "${github-secret}"),
                "type",
                "git")));

    return repository;
  }

  private void createNetworkingCiliumPackageVariant(
      final ApiObject catalogRepo, final ApiObject stateRepo) {
    createPackageVariant(
        "packagevariant-networking-cilium",
        "networking-cilium",
        "config.porch.kpt.dev|PackageVariant|porch-system|networking-cilium",
        "networking/cilium",
        Map.of(
            "cluster-name",
            "bioskop",
            "nxmatic.dev/app",
            "cilium",
            "nxmatic.dev/component",
            "networking"),
        Map.of(
            "bgp-local-asn",
            "65001",
            "bgp-peer-asn",
            "65000",
            "cluster-id",
            0,
            "cluster-lb-cidr",
            "10.80.0.64/26",
            "cluster-name",
            "bioskop-cluster",
            "cluster-node-gateway-inetaddr",
            "10.80.0.1",
            "cluster-vip-cidr",
            "10.80.7.0/24",
            "l2-interface-1",
            "vmnet0",
            "l2-interface-2",
            "lan0"),
        catalogRepo,
        stateRepo);
  }

  private void createNetworkingEnvoyGatewayPackageVariant(
      final ApiObject catalogRepo, final ApiObject stateRepo) {
    createPackageVariant(
        "packagevariant-networking-envoy-gateway",
        "networking-envoy-gateway",
        "config.porch.kpt.dev|PackageVariant|porch-system|networking-envoy-gateway",
        "networking/envoy-gateway",
        Map.of(
            "cluster-name",
            "bioskop",
            "nxmatic.dev/app",
            "envoy-gateway",
            "nxmatic.dev/component",
            "networking"),
        Map.of(
            "envoy-gateway-namespace", "envoy-gateway-system", "envoy-gateway-version", "v1.4.2"),
        catalogRepo,
        stateRepo);
  }

  private void createHaKubeVipPackageVariant(
      final ApiObject catalogRepo, final ApiObject stateRepo) {
    createPackageVariant(
        "packagevariant-ha-kube-vip",
        "ha-kube-vip",
        "config.porch.kpt.dev|PackageVariant|porch-system|ha-kube-vip",
        "ha/kube-vip",
        Map.of(
            "cluster-name",
            "bioskop",
            "nxmatic.dev/app",
            "kube-vip",
            "nxmatic.dev/component",
            "ha"),
        Map.of(
            "cluster-node-vip-inetaddr",
            "10.80.7.10",
            "cluster-vip-interface",
            "rke2-vip0",
            "kube-vip-namespace",
            "kube-vip",
            "kube-vip-version",
            "${kube-vip-version}"),
        catalogRepo,
        stateRepo);
  }

  private void createMeshHeadscalePackageVariant(
      final ApiObject catalogRepo, final ApiObject stateRepo) {
    createPackageVariant(
        "packagevariant-mesh-headscale",
        "mesh-headscale",
        "config.porch.kpt.dev|PackageVariant|porch-system|mesh-headscale",
        "mesh/headscale",
        Map.of(
            "cluster-name",
            "bioskop",
            "nxmatic.dev/app",
            "headscale",
            "nxmatic.dev/component",
            "mesh"),
        Map.of(
            "cluster-lan-headscale-inetaddr",
            "192.168.1.193",
            "cluster-lan-lb-cidr",
            "192.168.1.192/27",
            "cluster-name",
            "bioskop",
            "cluster-vip-cidr",
            "10.80.7.0/24",
            "darwin-host",
            "bioskop"),
        catalogRepo,
        stateRepo);
  }

  private void createMeshTailscalePackageVariant(
      final ApiObject catalogRepo, final ApiObject stateRepo) {
    createPackageVariant(
        "packagevariant-mesh-tailscale",
        "mesh-tailscale",
        "config.porch.kpt.dev|PackageVariant|porch-system|mesh-tailscale",
        "mesh/tailscale",
        Map.of(
            "cluster-name",
            "bioskop",
            "nxmatic.dev/app",
            "tailscale",
            "nxmatic.dev/component",
            "mesh"),
        Map.of(
            "cluster-lb-cidr",
            "10.80.0.64/26",
            "cluster-name",
            "bioskop-cluster",
            "cluster-node-vip-inetaddr",
            "10.80.7.10",
            "tailscale-namespace",
            "tailscale-system",
            "tailscale-version",
            "1.82.0"),
        catalogRepo,
        stateRepo);
  }

  private void createRuntimeFloxPackageVariant(
      final ApiObject catalogRepo, final ApiObject stateRepo) {
    createPackageVariant(
        "packagevariant-runtime-flox",
        "runtime-flox",
        "config.porch.kpt.dev|PackageVariant|porch-system|runtime-flox",
        "runtime/flox-containerd-shim",
        Map.of(
            "cluster-name",
            "bioskop",
            "nxmatic.dev/app",
            "flox-containerd-shim",
            "nxmatic.dev/component",
            "runtime"),
        Map.of(
            "apk-max-retries",
            "5",
            "containerd-address",
            "/run/k3s/containerd/containerd.sock",
            "containerd-config-file",
            "/var/lib/rancher/rke2/agent/etc/containerd/config.toml",
            "flox-namespace",
            "flox",
            "node-label-key",
            "flox.dev/enabled",
            "node-label-value",
            "true",
            "runtime-class-name",
            "flox"),
        catalogRepo,
        stateRepo);
  }

  private void createStorageOpenebsZfsPackageVariant(
      final ApiObject catalogRepo, final ApiObject stateRepo) {
    createPackageVariant(
        "packagevariant-storage-openebs-zfs",
        "storage-openebs-zfs",
        "config.porch.kpt.dev|PackageVariant|porch-system|storage-openebs-zfs",
        "storage-openebs-zfs",
        Map.of(
            "cluster-name",
            "bioskop",
            "nxmatic.dev/app",
            "openebs-zfs",
            "nxmatic.dev/component",
            "storage"),
        Map.of("kubelet-dir", "/var/lib/kubelet", "zfs-pool-name", "tank"),
        catalogRepo,
        stateRepo);
  }

  private void createPackageVariant(
      final String constructId,
      final String name,
      final String upstreamIdentifier,
      final String packageName,
      final Map<String, Object> labels,
      final Map<String, Object> mutatorConfig,
      final ApiObject catalogRepo,
      final ApiObject stateRepo) {
    ApiObject packageVariant =
        new ApiObject(
            this,
            constructId,
            ApiObjectProps.builder()
                .apiVersion("config.porch.kpt.dev/v1alpha1")
                .kind("PackageVariant")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace("porch-system")
                        .annotations(packageProfile.packageAnnotations(upstreamIdentifier))
                        .build())
                .build());
    packageVariant.addDependency(catalogRepo);
    packageVariant.addDependency(stateRepo);

    packageVariant.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "adoptionPolicy",
                "adoptNone",
                "deletionPolicy",
                "delete",
                "downstream",
                Map.of("package", packageName, "repo", "bioskop-state"),
                "labels",
                labels,
                "packageContext",
                Map.of("data", Map.of("cluster-env", "dave", "cluster-name", "bioskop")),
                "pipeline",
                Map.of(
                    "mutators",
                    List.of(
                        Map.of(
                            "configMap",
                            mutatorConfig,
                            "image",
                            "ghcr.io/kptdev/krm-functions-catalog/apply-setters:v0.2"))),
                "upstream",
                Map.of("package", packageName, "repo", "bioskop-catalog"))));
  }
}
