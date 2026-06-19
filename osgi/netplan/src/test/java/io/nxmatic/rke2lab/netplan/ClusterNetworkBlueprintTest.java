package io.nxmatic.rke2lab.netplan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.nxmatic.rke2lab.netplan.bridge.ClusterNetworkBlueprint;
import org.junit.jupiter.api.Test;

class ClusterNetworkBlueprintTest {

  @Test
  void topology_isCanonical_withOnemaster_threeControlNodes_twoWorkers() {
    ClusterNetworkBlueprint.ClusterTopology topology = ClusterNetworkBlueprint.topology();

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
}
