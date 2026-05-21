// @codebase
package io.nxmatic.rk2lab.manifests.layers.networking;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class CiliumAdvancedLayer extends Construct {

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("networking", "cilium-advanced");

  public CiliumAdvancedLayer(final Construct scope, final String id) {
    super(scope, id);
    createLoadBalancerPools();
    createBgpAdvertisement();
    createL2AnnouncementPolicy();
    createBgpClusterConfig();
  }

  private void createLoadBalancerPools() {
    ApiObject cluster =
        new ApiObject(
            this,
            "ciliumloadbalancerippool-cluster",
            ApiObjectProps.builder()
                .apiVersion("cilium.io/v2alpha1")
                .kind("CiliumLoadBalancerIPPool")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("cluster")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cilium.io|CiliumLoadBalancerIPPool|default|cluster"))
                        .build())
                .build());
    cluster.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "blocks",
                List.of(Map.of("cidr", "10.80.0.64/26")),
                "serviceSelector",
                Map.of(
                    "matchExpressions",
                    List.of(
                        Map.of("key", "io.cilium/lb-ipam-pool", "operator", "DoesNotExist"))))));

    ApiObject lan =
        new ApiObject(
            this,
            "ciliumloadbalancerippool-lan",
            ApiObjectProps.builder()
                .apiVersion("cilium.io/v2alpha1")
                .kind("CiliumLoadBalancerIPPool")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("lan")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cilium.io|CiliumLoadBalancerIPPool|default|lan"))
                        .build())
                .build());
    lan.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "blocks",
                List.of(Map.of("cidr", "192.168.1.192/27")),
                "serviceSelector",
                Map.of("matchLabels", Map.of("io.cilium/lb-ipam-pool", "lan")))));

    ApiObject vip =
        new ApiObject(
            this,
            "ciliumloadbalancerippool-vip",
            ApiObjectProps.builder()
                .apiVersion("cilium.io/v2alpha1")
                .kind("CiliumLoadBalancerIPPool")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("vip")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cilium.io|CiliumLoadBalancerIPPool|default|vip"))
                        .build())
                .build());
    vip.addJsonPatch(
        JsonPatch.add("/spec", Map.of("blocks", List.of(Map.of("cidr", "10.80.7.0/24")))));
  }

  private void createBgpAdvertisement() {
    ApiObject advertisement =
        new ApiObject(
            this,
            "ciliumbgpadvertisement-control-plane-advertisement",
            ApiObjectProps.builder()
                .apiVersion("cilium.io/v2")
                .kind("CiliumBGPAdvertisement")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("control-plane-advertisement")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cilium.io|CiliumBGPAdvertisement|default|control-plane-advertisement"))
                        .build())
                .build());

    advertisement.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "advertisements",
                List.of(
                    Map.of(
                        "advertisementType",
                        "Service",
                        "selector",
                        Map.of("matchLabels", Map.of("io.cilium/lb-ipam-pool", "cluster")),
                        "service",
                        Map.of("addresses", List.of("ExternalIP", "LoadBalancerIP"))),
                    Map.of("advertisementType", "PodCIDR")))));
  }

  private void createL2AnnouncementPolicy() {
    ApiObject policy =
        new ApiObject(
            this,
            "ciliuml2announcementpolicy-host",
            ApiObjectProps.builder()
                .apiVersion("cilium.io/v2alpha1")
                .kind("CiliumL2AnnouncementPolicy")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("host")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cilium.io|CiliumL2AnnouncementPolicy|default|host"))
                        .build())
                .build());

    policy.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "interfaces",
                List.of("eth0", "lan0"),
                "loadBalancerIPs",
                true,
                "nodeSelector",
                Map.of(
                    "matchExpressions",
                    List.of(
                        Map.of(
                            "key", "node-role.kubernetes.io/control-plane", "operator", "Exists"))),
                "serviceSelector",
                Map.of("matchLabels", Map.of()))));
  }

  private void createBgpClusterConfig() {
    // BGP Configuration Strategy:
    //
    // Option 1 (current): eBGP peering with external gateway (10.80.0.1, AS 65020)
    // - Advertises pod CIDRs and services to upstream router
    // - Useful for integrating with physical network infrastructure
    //
    // Option 2 (recommended for multi-host): iBGP mesh between cluster nodes
    // - Nodes peer with each other using same AS (e.g., 65010)
    // - Exchange pod routes dynamically within cluster
    // - Isolated from external network - cluster manages its own routing
    // - Works across different subnets/bare metal hosts
    // - To enable: Set peerASN to 65010 (same as localASN) and configure
    //   peer selectors to create full mesh between all nodes
    //
    // For iBGP mesh, replace static peerAddress with dynamic peer discovery:
    // - Use peerSelector to match all nodes (e.g., matchLabels: {})
    // - Cilium automatically discovers and peers with matching nodes
    // - Scales automatically as nodes are added/removed
    //
    // Current configuration uses eBGP to external gateway. For same-LAN setup
    // with native routing and autoDirectNodeRoutes, BGP is optional. When adding
    // bare metal hosts on different subnets, switch to iBGP mesh or ensure
    // node IPs are on the same L2 network (192.168.1.0/24 via lan0 interface).

    ApiObject bgpClusterConfig =
        new ApiObject(
            this,
            "ciliumbgpclusterconfig-bgp-cluster-config",
            ApiObjectProps.builder()
                .apiVersion("cilium.io/v2")
                .kind("CiliumBGPClusterConfig")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("bgp-cluster-config")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cilium.io|CiliumBGPClusterConfig|default|bgp-cluster-config"))
                        .build())
                .build());

    bgpClusterConfig.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "bgpInstances",
                List.of(
                    Map.of(
                        "localASN",
                        65010,
                        "name",
                        "control-plane-bgp",
                        "peers",
                        List.of(
                            Map.of(
                                "name",
                                "gateway-peer",
                                "peerASN",
                                65020,
                                "peerAddress",
                                "10.80.0.1",
                                "peerConfigRef",
                                Map.of("name", "cilium-peer"))))),
                "nodeSelector",
                Map.of("matchLabels", Map.of("node-role.kubernetes.io/control-plane", "")))));
  }
}
