package io.nxmatic.rk2lab.netplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
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
 *   alcide: 1
 *   nikopol: 2
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
public class BlueprintExporter {

  public static void main(String[] args) throws Exception {
    Map<String, Object> blueprint = new LinkedHashMap<>();

    // Cluster ID mappings
    Map<String, Integer> clusters = new LinkedHashMap<>();
    clusters.put("bioskop", 0);
    clusters.put("alcide", 1);
    clusters.put("nikopol", 2);
    blueprint.put("clusters", clusters);

    // Node ID mappings
    Map<String, Integer> nodes = new LinkedHashMap<>();
    nodes.put("master", 0);
    nodes.put("peer1", 1);
    nodes.put("peer2", 2);
    nodes.put("peer3", 3);
    nodes.put("worker1", 10);
    nodes.put("worker2", 11);
    blueprint.put("nodes", nodes);

    // Add examples showing the derivation for a few cluster/node pairs
    Map<String, Map<String, String>> examples = new LinkedHashMap<>();

    // bioskop-master
    ClusterNetworkBlueprint bioskopMaster =
        ClusterNetworkBlueprint.builder()
            .cluster("bioskop")
            .node("master")
            .deriveRecipeModel()
            .build();
    Map<String, String> bioskopMasterMacs = new LinkedHashMap<>();
    bioskopMasterMacs.put("lan", bioskopMaster.lan().hostMacaddr().value());
    bioskopMasterMacs.put("wan", bioskopMaster.wan().hostMacaddr().value());
    examples.put("bioskop-master", bioskopMasterMacs);

    // nikopol-peer2
    ClusterNetworkBlueprint nikopolPeer2 =
        ClusterNetworkBlueprint.builder()
            .cluster("nikopol")
            .node("peer2")
            .deriveRecipeModel()
            .build();
    Map<String, String> nikopolPeer2Macs = new LinkedHashMap<>();
    nikopolPeer2Macs.put("lan", nikopolPeer2.lan().hostMacaddr().value());
    nikopolPeer2Macs.put("wan", nikopolPeer2.wan().hostMacaddr().value());
    examples.put("nikopol-peer2", nikopolPeer2Macs);

    blueprint.put("examples", examples);

    // Write as YAML
    YAMLFactory yamlFactory =
        YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .build();

    ObjectMapper mapper = new ObjectMapper(yamlFactory);
    mapper.writerWithDefaultPrettyPrinter().writeValue(System.out, blueprint);
  }
}
