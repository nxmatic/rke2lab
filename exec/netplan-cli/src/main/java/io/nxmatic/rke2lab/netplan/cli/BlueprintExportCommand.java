package io.nxmatic.rke2lab.netplan.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.nxmatic.rke2lab.netplan.port.ClusterNetworkBlueprint;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exports ClusterNetworkBlueprint metadata as YAML for consumption by Nix flake.
 *
 * <p>Output format:
 *
 * <pre>
 * # Network blueprint metadata (source of truth)
 * clusters:
 *   bioskop: 0
 *   nikopol: 1
 *
 * nodes:
 *   master: 0
 *   peer1: 1
 *   peer2: 2
 *   peer3: 3
 *   worker1: 10
 *   worker2: 11
 *
 * # MAC address derivation examples
 * # LAN:  10:66:6a:4c:{clusterId:02x}:{nodeId:02x}
 * # WAN:  52:54:00:{clusterId:02x}:{nodeType:02x}:{nodeId:02x}
 * examples:
 *   bioskop-master:
 *     lan: "10:66:6a:4c:00:00"
 *     wan: "52:54:00:00:00:00"
 *   nikopol-peer2:
 *     lan: "10:66:6a:4c:02:02"
 *     wan: "52:54:00:02:00:02"
 * </pre>
 */
public class BlueprintExportCommand implements NetplanCli.Command {

  // Metadata record for export
  record NetworkBlueprintMetadata(
      Map<String, Integer> clusters,
      Map<String, Integer> nodes,
      Map<String, String> macPatterns,
      Map<String, Integer> nodeTypes,
      Map<String, Map<String, NodeAddressing>> addressing) {}

  record NodeAddressing(NodeMacs macs, NodeIPs ips, NodeLeases leases) {}

  record NodeMacs(String lan, String wan, String lanBridge) {}

  record NodeIPs(String lanHost, String nodeHost, String lanGateway, String nodeGateway) {}

  record NodeLeases(LanLease lan, WanLease wan) {}

  record LanLease(String mac, String ip, String cidr) {}

  record WanLease(String mac, String dhcpRange) {}

  @Override
  public void execute(String[] args) throws Exception {
    // Cluster ID mappings (nikopol was renamed from alcide, keeping cluster ID 1)
    Map<String, Integer> clusters = new LinkedHashMap<>();
    clusters.put("bioskop", 0);
    clusters.put("nikopol", 1);

    // Node ID mappings
    Map<String, Integer> nodes = new LinkedHashMap<>();
    nodes.put("master", 0);
    nodes.put("peer1", 1);
    nodes.put("peer2", 2);
    nodes.put("peer3", 3);
    nodes.put("worker1", 10);
    nodes.put("worker2", 11);

    // MAC address derivation patterns
    Map<String, String> macPatterns = new LinkedHashMap<>();
    macPatterns.put("lan", "10:66:6a:4c:{clusterId:02x}:{nodeId:02x}");
    macPatterns.put("wan", "52:54:00:{clusterId:02x}:{nodeType:02x}:{nodeId:02x}");
    macPatterns.put("lanBridge", "02:00:00:bb:{clusterId:02x}:{nodeId:02x}");

    // Node type codes (for WAN MAC derivation)
    Map<String, Integer> nodeTypes = new LinkedHashMap<>();
    nodeTypes.put("SERVER", 0); // master, peer1-3
    nodeTypes.put("AGENT", 1); // worker1-2

    // Generate complete addressing information from the blueprint POJOs
    Map<String, Map<String, NodeAddressing>> allAddressing = new LinkedHashMap<>();

    for (String cluster : clusters.keySet()) {
      Map<String, NodeAddressing> clusterAddressing = new LinkedHashMap<>();
      for (String node : nodes.keySet()) {
        ClusterNetworkBlueprint bp =
            ClusterNetworkBlueprint.builder()
                .cluster(cluster)
                .node(node)
                .deriveRecipeModel()
                .build();

        // MACs
        NodeMacs macs =
            new NodeMacs(
                bp.lan().hostMacaddr().value(),
                bp.wan().hostMacaddr().value(),
                bp.lan().bridgeMacaddr().value());

        // IPs
        NodeIPs ips =
            new NodeIPs(
                bp.lan().hostInetaddr().getHostAddress(),
                bp.nodeNetwork().nodeHostInetaddr().getHostAddress(),
                bp.lan().gatewayInetaddr().getHostAddress(),
                bp.nodeNetwork().nodeGatewayInetaddr().getHostAddress());

        // Leases (MAC → IP mappings for DHCP)
        LanLease lanLease =
            new LanLease(
                bp.lan().hostMacaddr().value(),
                bp.lan().hostInetaddr().getHostAddress(),
                bp.lan().nodeCidr().toString());

        WanLease wanLease = new WanLease(bp.wan().hostMacaddr().value(), bp.wan().dhcpRange());

        NodeLeases leases = new NodeLeases(lanLease, wanLease);

        clusterAddressing.put(node, new NodeAddressing(macs, ips, leases));
      }
      allAddressing.put(cluster, clusterAddressing);
    }

    NetworkBlueprintMetadata blueprint =
        new NetworkBlueprintMetadata(clusters, nodes, macPatterns, nodeTypes, allAddressing);

    // Write as YAML using Jackson serialization
    YAMLFactory yamlFactory =
        YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .build();

    ObjectMapper mapper = new ObjectMapper(yamlFactory);
    mapper.writerWithDefaultPrettyPrinter().writeValue(System.out, blueprint);
  }
}
