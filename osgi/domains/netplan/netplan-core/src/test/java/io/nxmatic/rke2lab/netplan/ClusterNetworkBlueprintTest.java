package io.nxmatic.rke2lab.netplan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.nxmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class ClusterNetworkBlueprintTest {

  @Test
  void topology_isCanonical_withOnemaster_threeControlNodes_twoWorkers() {
    ClusterNetworkBlueprint.ClusterTopology topology =
        ClusterNetworkBlueprint.ClusterTopology.CANONICAL;

    assertEquals(1, topology.masterCount());
    assertEquals(3, topology.controlNodeCount());
    assertEquals(2, topology.workerNodeCount());
    assertEquals(6, topology.totalNodeCount());
  }

  @Test
  void deriveRecipeModel_forNikopolMaster_producesDeterministicAddressing() {
    ClusterNetworkBlueprint blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster("nikopol")
            .node("master")
            .deriveRecipeModel()
            .build();

    assertEquals(1, blueprint.cluster().id());
    assertEquals("10.80.8.0/21", blueprint.host().clusterCidr().toString());
    assertEquals("10.80.8.1", blueprint.host().clusterGatewayInetaddr().getHostAddress());
    assertEquals("10.80.8.10", blueprint.nodeNetwork().nodeHostInetaddr().getHostAddress());
    assertEquals("52:54:00:01:00:00", blueprint.wan().hostMacaddr().value());
  }

  @Test
  void deriveRecipeModel_forNikopolMaster_producesDeterministicIPv6Mirror() throws Exception {
    ClusterNetworkBlueprint blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster("nikopol")
            .node("master")
            .deriveRecipeModel()
            .build();

    // /48 super ⊃ /56 cluster (CC=01) ⊃ /64 per role — byte-boundary mirror of the IPv4 hierarchy.
    assertEquals(48, blueprint.host().superNetworkCidr6().prefixLength());
    assertEquals(
        InetAddress.getByName("fd96:6924:3693::"),
        blueprint.host().superNetworkCidr6().networkAddress());
    assertEquals(56, blueprint.host().clusterCidr6().prefixLength());
    assertEquals(
        InetAddress.getByName("fd96:6924:3693:100::"),
        blueprint.host().clusterCidr6().networkAddress());
    assertEquals(64, blueprint.nodeNetwork().nodeCidr6().prefixLength());
    assertEquals(
        InetAddress.getByName("fd96:6924:3693:120::"),
        blueprint.nodeNetwork().nodeCidr6().networkAddress());

    // Each host embeds its IPv4 verbatim in the low 32 bits, so v4↔v6 is inferable by inspection.
    assertEquals(
        InetAddress.getByName("fd96:6924:3693:120::10.80.8.10"),
        blueprint.nodeNetwork().nodeHostInetaddr6());
    assertEquals(
        InetAddress.getByName("fd96:6924:3693:150::192.168.1.99"), blueprint.lan().hostInetaddr6());
  }
}
