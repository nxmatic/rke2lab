package io.seedmatic.rke2lab.netplan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
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
  void deriveRecipeModel_forNikopolMgmtMaster_producesDeterministicAddressing() {
    ClusterNetworkBlueprint blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster("nikopol-mgmt")
            .node("master")
            .deriveRecipeModel()
            .build();

    // nikopol-mgmt packs clusterId (host<<1)|role = (1<<1)|0 = 2 (see the cluster addressing plan).
    assertEquals(2, blueprint.cluster().id());
    assertEquals("10.80.16.0/21", blueprint.host().clusterCidr().toString());
    assertEquals("10.80.16.1", blueprint.host().clusterGatewayInetaddr().getHostAddress());
    assertEquals("10.80.16.10", blueprint.nodeNetwork().nodeHostInetaddr().getHostAddress());
    assertEquals("52:54:00:02:00:00", blueprint.wan().hostMacaddr().value());
  }

  @Test
  void deriveRecipeModel_forNikopolMgmtMaster_producesDeterministicIPv6Mirror() throws Exception {
    ClusterNetworkBlueprint blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster("nikopol-mgmt")
            .node("master")
            .deriveRecipeModel()
            .build();

    // /48 super ⊃ /56 cluster (CC=02) ⊃ /64 per role — byte-boundary mirror of the IPv4 hierarchy.
    assertEquals(48, blueprint.host().superNetworkCidr6().prefixLength());
    assertEquals(
        InetAddress.getByName("fd96:6924:3693::"),
        blueprint.host().superNetworkCidr6().networkAddress());
    assertEquals(56, blueprint.host().clusterCidr6().prefixLength());
    assertEquals(
        InetAddress.getByName("fd96:6924:3693:200::"),
        blueprint.host().clusterCidr6().networkAddress());
    assertEquals(64, blueprint.nodeNetwork().nodeCidr6().prefixLength());
    assertEquals(
        InetAddress.getByName("fd96:6924:3693:220::"),
        blueprint.nodeNetwork().nodeCidr6().networkAddress());

    // Each host embeds its IPv4 verbatim in the low 32 bits, so v4↔v6 is inferable by inspection.
    assertEquals(
        InetAddress.getByName("fd96:6924:3693:220::10.80.16.10"),
        blueprint.nodeNetwork().nodeHostInetaddr6());
    assertEquals(
        InetAddress.getByName("fd96:6924:3693:250::192.168.1.179"),
        blueprint.lan().hostInetaddr6());
  }

  @Test
  void deriveRecipeModel_forNikopolMgmtMaster_carvesTheMgmtLanSlice() {
    ClusterNetworkBlueprint blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster("nikopol-mgmt")
            .node("master")
            .deriveRecipeModel()
            .build();

    // nikopol hostId 1, mgmt roleId 0 -> LAN base 128 + 1*48 + 0*16 = 176; mgmt takes node /29 + lb
    // /29. master nodeId 0 -> host(3) = .179; headscale/tailscale VIPs from the lb /29 at .184.
    assertEquals("192.168.1.176/29", blueprint.lan().nodeCidr().toString());
    assertEquals("192.168.1.184/29", blueprint.lan().lbCidr().toString());
    assertEquals("192.168.1.179", blueprint.lan().hostInetaddr().getHostAddress());
    assertEquals("192.168.1.185", blueprint.lan().headscaleInetaddr().getHostAddress());
    assertEquals("192.168.1.186", blueprint.lan().tailscaleInetaddr().getHostAddress());
  }

  @Test
  void deriveRecipeModel_forTheReservedTestCluster_carvesTheDot224Slice() {
    // The blank/unknown cluster identity falls back to the reserved "test-mgmt" cluster
    // (DefaultNodeEnvContext), which must derive a valid, distinct slice — NOT overflow the octet.
    // test hostId 2, mgmt roleId 0 -> LAN base 128 + 2*48 = 224; clusterId (2<<1)|0 = 4.
    ClusterNetworkBlueprint blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster("test-mgmt")
            .node("master")
            .deriveRecipeModel()
            .build();

    assertEquals(4, blueprint.cluster().id());
    assertEquals("192.168.1.224/29", blueprint.lan().nodeCidr().toString());
    assertEquals("192.168.1.232/29", blueprint.lan().lbCidr().toString());
    assertEquals("192.168.1.227", blueprint.lan().hostInetaddr().getHostAddress());
  }
}
