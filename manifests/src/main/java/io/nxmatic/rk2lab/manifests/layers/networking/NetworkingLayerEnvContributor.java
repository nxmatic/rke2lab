package io.nxmatic.rk2lab.manifests.layers.networking;

import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContext;
import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContributor;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Networking layer environment variable contributor. Contributes: cilium, network-cluster,
 * network-node, network-lan-wan
 */
public class NetworkingLayerEnvContributor implements LayerEnvContributor {

  @Override
  public String layerId() {
    return "networking";
  }

  @Override
  public List<String> contributedSections() {
    return List.of("cilium", "network-cluster", "network-node", "network-lan-wan");
  }

  @Override
  public Map<String, String> contributeVariables(String sectionName, LayerEnvContext context)
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
              "RKE2LAB_NETWORK_CLUSTER_CIDR", context.clusterCidr(),
              "RKE2LAB_NETWORK_CLUSTER_LB_CIDR", "10.80.0.64/26",
              "RKE2LAB_NETWORK_CLUSTER_LB_GATEWAY_INETADDR", "10.80.0.65",
              "RKE2LAB_NETWORK_CLUSTER_POD_CIDR", context.clusterPodCidr(),
              "RKE2LAB_NETWORK_CLUSTER_SERVICE_CIDR", context.clusterServiceCidr(),
              "RKE2LAB_NETWORK_CLUSTER_GATEWAY_INETADDR", context.nodeNetworkGatewayAddr());
      case "network-node" ->
          Map.of(
              "RKE2LAB_NETWORK_NODE_HOST_INETADDR", context.nodeHostInetAddr(),
              "RKE2LAB_NETWORK_NODE_CIDR", context.nodeNetworkCidr(),
              "RKE2LAB_NETWORK_NODE_GATEWAY_INETADDR", context.nodeNetworkGatewayAddr());
      case "network-lan-wan" ->
          Map.of(
              "RKE2LAB_NETWORK_LAN_INTERFACE", "master-lan0",
              "RKE2LAB_NETWORK_LAN_HOST_INETADDR", "192.168.1.131",
              "RKE2LAB_NETWORK_LAN_LB_CIDR", "192.168.1.192/27",
              "RKE2LAB_NETWORK_WAN_INTERFACE", "master-vmnet0");
      default -> Map.of();
    };
  }
}
