// @codebase
package io.nxmatic.rk2lab.manifests.components.networking;

import io.nxmatic.rk2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class CiliumAdvancedComponent extends Construct {

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("networking", "cilium-advanced");

  public CiliumAdvancedComponent(final Construct scope, final String id) {
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
    // BGP Configuration Strategy for Multi-Host Cluster Mesh
    //
    // See docs/cilium-bgp-multi-host-topology.adoc for comprehensive documentation
    // covering:
    // - Multi-host topology with Incus containers
    // - BGP Router ID allocation (automatic from InternalIP)
    // - iBGP mesh configuration and peer discovery
    // - Multi-homed node peering (Router ID vs Peering Address)
    // - Implementation guide and troubleshooting
    //
    // Quick Summary:
    // - Nodes multi-homed: InternalIP (10.80.x) + LAN (192.168.1.x)
    // - Router ID: Auto-assigned from InternalIP
    // - Current: eBGP to external gateway (AS 65020)
    // - Multi-host: Switch to iBGP mesh (same AS, peerSelector, LAN interface)

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
                Map.of(
                    "matchExpressions",
                    List.of(
                        Map.of(
                            "key",
                            "node-role.kubernetes.io/control-plane",
                            "operator",
                            "Exists"))))));
  }
}
