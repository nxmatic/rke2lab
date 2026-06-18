package io.nxmatic.rke2lab.manifests.units.networking;

import io.nxmatic.rke2lab.manifests.node.NodeEnvContext;
import io.nxmatic.rke2lab.manifests.node.NodeEnvContributor;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Networking domain node-env contributor. Contributes: cilium, network-cluster, network-node,
 * network-lan-wan
 */
public class NetworkingNodeEnvContributor implements NodeEnvContributor {

  @Override
  public String domainId() {
    return "networking";
  }

  @Override
  public List<String> contributedSections() {
    return List.of("cilium", "network-cluster", "network-node", "network-lan-wan");
  }

  @Override
  public Map<String, String> contributeVariables(String sectionName, NodeEnvContext context)
      throws IOException {
    return switch (sectionName) {
      case "cilium" ->
          Map.of(
              "CILIUM_CLI_MODE", "kubernetes",
              "CILIUM_CLI_CONTEXT", "default",
              "HUBBLE_SERVER", "localhost:4245",
              "HUBBLE_TLS", "false");
      case "network-cluster" ->
          Map.of(
              "RKE2LAB_NETWORK_CLUSTER_CIDR",
              context.clusterCidr(),
              "RKE2LAB_NETWORK_CLUSTER_LB_CIDR",
              context.clusterLoadBalancerCidr(),
              "RKE2LAB_NETWORK_CLUSTER_LB_GATEWAY_INETADDR",
              context.clusterLoadBalancerGatewayAddr(),
              "RKE2LAB_NETWORK_CLUSTER_POD_CIDR",
              context.clusterPodCidr(),
              "RKE2LAB_NETWORK_CLUSTER_SERVICE_CIDR",
              context.clusterServiceCidr(),
              "RKE2LAB_NETWORK_CLUSTER_GATEWAY_INETADDR",
              context.nodeNetworkGatewayAddr());
      case "network-node" ->
          Map.of(
              "RKE2LAB_NETWORK_NODE_HOST_INETADDR", context.nodeHostInetAddr(),
              "RKE2LAB_NETWORK_NODE_CIDR", context.nodeNetworkCidr(),
              "RKE2LAB_NETWORK_NODE_GATEWAY_INETADDR", context.nodeNetworkGatewayAddr());
      case "network-lan-wan" ->
          Map.of(
              "RKE2LAB_NETWORK_LAN_INTERFACE", context.lanInterface(),
              "RKE2LAB_NETWORK_LAN_HOST_INETADDR", context.lanHostInetAddr(),
              "RKE2LAB_NETWORK_LAN_LB_CIDR", context.lanLoadBalancerCidr(),
              "RKE2LAB_NETWORK_WAN_INTERFACE", context.wanInterface());
      default -> Map.of();
    };
  }
}
