package io.nxmatic.rke2lab.manifests.units.networking;

import io.nxmatic.rke2lab.manifests.contract.node.NodeEnvContext;
import io.nxmatic.rke2lab.manifests.contract.node.NodeEnvContributor;
import io.nxmatic.rke2lab.manifests.contract.profiles.NetworkTopology;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/**
 * Networking domain node-env contributor. Contributes: cilium, network-cluster, network-node,
 * network-lan-wan
 */
@Component(service = NodeEnvContributor.class)
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
    final NetworkTopology net = context.networkTopology();
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
              net.clusterCidr(),
              "RKE2LAB_NETWORK_CLUSTER_LB_CIDR",
              net.clusterLoadBalancerCidr(),
              "RKE2LAB_NETWORK_CLUSTER_LB_GATEWAY_INETADDR",
              net.clusterLoadBalancerGatewayAddr(),
              "RKE2LAB_NETWORK_CLUSTER_POD_CIDR",
              net.clusterPodCidr(),
              "RKE2LAB_NETWORK_CLUSTER_SERVICE_CIDR",
              net.clusterServiceCidr(),
              "RKE2LAB_NETWORK_CLUSTER_GATEWAY_INETADDR",
              net.nodeNetworkGatewayAddr());
      case "network-node" ->
          Map.of(
              "RKE2LAB_NETWORK_NODE_HOST_INETADDR", net.nodeHostInetAddr(),
              "RKE2LAB_NETWORK_NODE_CIDR", net.nodeNetworkCidr(),
              "RKE2LAB_NETWORK_NODE_GATEWAY_INETADDR", net.nodeNetworkGatewayAddr());
      case "network-lan-wan" ->
          Map.of(
              "RKE2LAB_NETWORK_LAN_INTERFACE", net.lanInterface(),
              "RKE2LAB_NETWORK_LAN_HOST_INETADDR", net.lanHostInetAddr(),
              "RKE2LAB_NETWORK_LAN_LB_CIDR", net.lanLoadBalancerCidr(),
              "RKE2LAB_NETWORK_WAN_INTERFACE", net.wanInterface());
      default -> Map.of();
    };
  }
}
