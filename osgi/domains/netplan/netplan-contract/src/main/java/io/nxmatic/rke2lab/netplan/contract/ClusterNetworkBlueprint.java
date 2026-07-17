package io.nxmatic.rke2lab.netplan.contract;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Stage B network blueprint for clusters provisioned by the management control-plane.
 *
 * <p>Derived from the same recipe model used in rke2lab network make rules (host/lan/lb/node/vip).
 */
public record ClusterNetworkBlueprint(
    ClusterRef cluster,
    NodeRef node,
    HostPlan host,
    NodeNetworkPlan nodeNetwork,
    VipPlan vip,
    LoadBalancerPlan loadBalancer,
    LanPlan lan,
    WanPlan wan,
    InterfacePlan interfaces,
    VlanPlan vlan) {

  /**
   * The canonical node names of every cluster, in topology order (1 master + 3 peers + 2 workers).
   * The single source of truth the netplan domain OWNS — it already derives each node's id/type and
   * validates names against this topology, so a consumer that must enumerate the cluster's nodes
   * (the incus grow's dnsmasq {@code dhcp-host} lines, the bbox reservation rows) reads THIS rather
   * than duplicating the list.
   */
  public static final List<String> CANONICAL_NODE_NAMES =
      List.of("master", "peer1", "peer2", "peer3", "worker1", "worker2");

  /** Create a fluent builder for blueprint derivation. */
  public static Builder builder() {
    return new Builder();
  }

  private static ClusterNetworkBlueprint derive(String clusterName, String nodeName) {
    validateNodeName(nodeName);

    final int clusterId = clusterId(clusterName);

    final int nodeId = nodeId(nodeName);
    final NodeType nodeType = nodeType(nodeName);

    final int hostThirdOctet = clusterId * 8;
    final int vipThirdOctet = hostThirdOctet + 7;

    final int lanSlice = lanSliceIndex(clusterName);
    final int lanLbSlice = lanLbSliceIndex(clusterName);

    final int lanSliceBase = lanSlice * 32;
    final int lanLbSliceBase = lanLbSlice * 32;

    final Cidr clusterCidr = Cidr.parse("10.80." + hostThirdOctet + ".0/21");
    final Cidr nodeCidr = Cidr.parse("10.80." + hostThirdOctet + ".0/23");
    final Cidr vipCidr = Cidr.parse("10.80." + vipThirdOctet + ".0/24");
    final Cidr lbCidr = Cidr.parse("10.80." + hostThirdOctet + ".64/26");

    final Cidr lanNodeCidr = Cidr.parse("192.168.1." + lanSliceBase + "/27");
    final Cidr lanLbCidr = Cidr.parse("192.168.1." + lanLbSliceBase + "/27");

    // Each host derives from the CIDR we already hold — ask the network for its host, instead of
    // rebuilding and re-parsing an address string that re-encodes the same octets.
    final InetAddress clusterGatewayInetaddr = clusterCidr.gateway();
    final InetAddress nodeGatewayInetaddr = clusterGatewayInetaddr;
    final InetAddress nodeHostInetaddr = nodeCidr.host(10 + nodeId);

    final InetAddress vipGatewayInetaddr = vipCidr.gateway();
    final InetAddress vipHostInetaddr = vipCidr.host(10);

    final InetAddress lanHostInetaddr = lanNodeCidr.host(3 + nodeId);
    // The fixed LAN gateway lies outside the allocated /27 slice — a foreign address, resolved by a
    // Cidr in whose 192.168.1.0 space it lives (address manipulation is part of the type's role).
    final InetAddress lanGatewayInetaddr = lanNodeCidr.address("192.168.1.254");
    final InetAddress lanHeadscaleInetaddr = lanLbCidr.host(1);
    final InetAddress lanTailscaleInetaddr = lanLbCidr.host(2);

    final String wanDhcpRange =
        "10.80."
            + hostThirdOctet
            + ".2-10.80."
            + hostThirdOctet
            + ".9,10.80."
            + hostThirdOctet
            + ".31-10.80."
            + (hostThirdOctet + 7)
            + ".254";

    final MacAddress wanHostMacaddr =
        MacAddress.parse(
            String.format("52:54:00:%02x:%02x:%02x", clusterId, nodeType.numericCode(), nodeId));
    final MacAddress lanHostMacaddr =
        MacAddress.parse(String.format("10:66:6a:4c:%02x:%02x", clusterId, nodeId));
    final MacAddress lanBridgeMacaddr =
        MacAddress.parse(String.format("02:00:00:bb:%02x:%02x", clusterId, nodeId));

    return new ClusterNetworkBlueprint(
        new ClusterRef(clusterName, clusterId),
        new NodeRef(nodeName, nodeId, nodeType),
        new HostPlan(Cidr.parse("10.80.0.0/18"), clusterCidr, clusterGatewayInetaddr),
        new NodeNetworkPlan(nodeCidr, nodeGatewayInetaddr, nodeHostInetaddr),
        new VipPlan(vipCidr, vipGatewayInetaddr, vipHostInetaddr),
        new LoadBalancerPlan(lbCidr),
        new LanPlan(
            lanNodeCidr,
            lanLbCidr,
            lanHostInetaddr,
            lanGatewayInetaddr,
            lanHeadscaleInetaddr,
            lanTailscaleInetaddr,
            lanHostMacaddr,
            lanBridgeMacaddr),
        new WanPlan(wanDhcpRange, wanHostMacaddr),
        new InterfacePlan(nodeName + "-lan0", nodeName + "-vmnet0", "vmnet0"),
        new VlanPlan(100, "rke2-vlan"));
  }

  /** Stable ref/id for contract exports. */
  public String ref() {
    return "cluster:"
        + cluster.name()
        + "("
        + cluster.id()
        + ")"
        + ",node:"
        + node.name()
        + "("
        + node.id()
        + ")"
        + ",host:"
        + host.superNetworkCidr()
        + ",cluster:"
        + host.clusterCidr()
        + ",node:"
        + nodeNetwork.nodeCidr()
        + ",vip:"
        + vip.vipCidr()
        + ",lb:"
        + loadBalancer.lbCidr()
        + ",lan-node:"
        + lan.nodeCidr()
        + ",lan-lb:"
        + lan.lbCidr();
  }

  private static int clusterId(String clusterName) {
    return switch (clusterName) {
      case "bioskop" -> 0;
      case "nikopol" -> 1; // renamed from alcide, keeping same cluster ID
      default -> 7;
    };
  }

  private static int nodeId(String nodeName) {
    return switch (nodeName) {
      case "master" -> 0;
      case "peer1" -> 1;
      case "peer2" -> 2;
      case "peer3" -> 3;
      case "worker1" -> 10;
      case "worker2" -> 11;
      default -> throw new IllegalArgumentException("Unsupported node.name: " + nodeName);
    };
  }

  private static NodeType nodeType(String nodeName) {
    return switch (nodeName) {
      case "master", "peer1", "peer2", "peer3" -> NodeType.SERVER;
      case "worker1", "worker2" -> NodeType.AGENT;
      default -> throw new IllegalArgumentException("Unsupported node.name: " + nodeName);
    };
  }

  private static void validateNodeName(String nodeName) {
    if (!CANONICAL_NODE_NAMES.contains(nodeName)) {
      throw new IllegalArgumentException(
          "Node name '"
              + nodeName
              + "' does not conform to canonical topology "
              + CANONICAL_NODE_NAMES);
    }
  }

  private static int lanSliceIndex(String clusterName) {
    return switch (clusterName) {
      case "bioskop" -> 4;
      case "nikopol" -> 3; // renamed from alcide
      default -> 7;
    };
  }

  private static int lanLbSliceIndex(String clusterName) {
    return switch (clusterName) {
      case "bioskop" -> 6;
      case "nikopol" -> 5; // renamed from alcide
      default -> 7;
    };
  }

  public enum NodeType {
    SERVER(0),
    AGENT(1);

    private final int numericCode;

    NodeType(int numericCode) {
      this.numericCode = numericCode;
    }

    public int numericCode() {
      return numericCode;
    }
  }

  public record ClusterRef(String name, int id) {}

  public record NodeRef(String name, int id, NodeType type) {}

  public record HostPlan(
      Cidr superNetworkCidr, Cidr clusterCidr, InetAddress clusterGatewayInetaddr) {}

  public record NodeNetworkPlan(
      Cidr nodeCidr, InetAddress nodeGatewayInetaddr, InetAddress nodeHostInetaddr) {}

  public record VipPlan(
      Cidr vipCidr, InetAddress vipGatewayInetaddr, InetAddress vipHostInetaddr) {}

  public record LoadBalancerPlan(Cidr lbCidr) {}

  public record LanPlan(
      Cidr nodeCidr,
      Cidr lbCidr,
      InetAddress hostInetaddr,
      InetAddress gatewayInetaddr,
      InetAddress headscaleInetaddr,
      InetAddress tailscaleInetaddr,
      MacAddress hostMacaddr,
      MacAddress bridgeMacaddr) {}

  public record WanPlan(String dhcpRange, MacAddress hostMacaddr) {}

  public record InterfacePlan(String lanInterface, String wanInterface, String vipInterface) {}

  public record VlanPlan(int id, String name) {}

  /**
   * Canonical cluster topology: 1 master, 3 control nodes (peers), 2 worker nodes.
   *
   * <p>This is the expected node composition for every cluster managed by the rke2lab
   * control-plane.
   */
  public record ClusterTopology(int masterCount, int controlNodeCount, int workerNodeCount) {
    public static final ClusterTopology CANONICAL = new ClusterTopology(1, 3, 2);

    public int totalNodeCount() {
      return masterCount + controlNodeCount + workerNodeCount;
    }
  }

  /** Fluent API for deriving blueprint from cluster/node identity. */
  public static final class Builder {
    private @Nullable String clusterName;
    private @Nullable String nodeName;
    private boolean deriveRecipeModel;

    public Builder cluster(String clusterName) {
      this.clusterName = clusterName;
      return this;
    }

    public Builder node(String nodeName) {
      this.nodeName = nodeName;
      return this;
    }

    public Builder deriveRecipeModel() {
      this.deriveRecipeModel = true;
      return this;
    }

    public ClusterNetworkBlueprint build() {
      if (!deriveRecipeModel) {
        throw new IllegalStateException("Builder requires deriveRecipeModel() before build()");
      }
      final String cluster = Objects.requireNonNull(clusterName, "clusterName must be set");
      final String node = Objects.requireNonNull(nodeName, "nodeName must be set");
      if (cluster.isBlank()) {
        throw new IllegalArgumentException("clusterName must be set");
      }
      if (node.isBlank()) {
        throw new IllegalArgumentException("nodeName must be set");
      }
      return derive(cluster, node);
    }
  }
}
