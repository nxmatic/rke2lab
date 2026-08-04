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
    VlanPlan vlan,
    NamePlan names) {

  /**
   * The canonical node names of every cluster, in topology order (1 master + 3 peers + 2 workers).
   * The single source of truth the netplan domain OWNS — it already derives each node's id/type and
   * validates names against this topology, so a consumer that must enumerate the cluster's nodes
   * (the incus grow's dnsmasq {@code dhcp-host} lines, the bbox reservation rows) reads THIS rather
   * than duplicating the list.
   */
  public static final List<String> CANONICAL_NODE_NAMES =
      List.of("master", "peer1", "peer2", "peer3", "worker1", "worker2");

  /**
   * IPv6 ULA underlay — a deterministic mirror of the IPv4 plan. The /48 global ID is the first 40
   * bits of {@code sha256("mammoth-skate")} (fixed forever; random-looking for RFC 4193 collision
   * resistance). The hierarchy mirrors IPv4 on byte boundaries: {@code /48} super ⊃ {@code /56} per
   * cluster ({@code {clusterId}00}) ⊃ {@code /64} per role ({@code {clusterId}{role}}). Each host
   * embeds its IPv4 verbatim in the low 32 bits ({@code fd96:6924:3693:{CC}{RR}::a.b.c.d}) so v4↔v6
   * is inferable by inspection.
   */
  public static final String ULA_PREFIX = "fd96:6924:3693";

  private static final int ROLE_NODE = 0x20;
  private static final int ROLE_VIP = 0x30;
  private static final int ROLE_LB = 0x40;
  private static final int ROLE_LAN_NODE = 0x50;
  private static final int ROLE_LAN_LB = 0x60;

  private static Cidr ula64(int clusterId, int role) {
    return Cidr.parse(String.format("%s:%02x%02x::/64", ULA_PREFIX, clusterId, role));
  }

  /** The address in a role's {@code /64} that embeds {@code ipv4} verbatim in its low 32 bits. */
  private static InetAddress ula6(int clusterId, int role, InetAddress ipv4) {
    final String value =
        String.format("%s:%02x%02x::%s", ULA_PREFIX, clusterId, role, ipv4.getHostAddress());
    try {
      return InetAddress.getByName(value);
    } catch (java.net.UnknownHostException exception) {
      throw new IllegalArgumentException("Invalid ULA v6 host: " + value, exception);
    }
  }

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

    // IPv6 ULA mirror (see ULA_PREFIX): /48 super ⊃ /56 cluster ⊃ /64 per role, each host
    // embedding its IPv4 in the low 32 bits.
    final Cidr superNetworkCidr6 = Cidr.parse(ULA_PREFIX + "::/48");
    final Cidr clusterCidr6 = Cidr.parse(String.format("%s:%02x00::/56", ULA_PREFIX, clusterId));
    final Cidr nodeCidr6 = ula64(clusterId, ROLE_NODE);
    final Cidr vipCidr6 = ula64(clusterId, ROLE_VIP);
    final Cidr lbCidr6 = ula64(clusterId, ROLE_LB);
    final Cidr lanNodeCidr6 = ula64(clusterId, ROLE_LAN_NODE);
    final Cidr lanLbCidr6 = ula64(clusterId, ROLE_LAN_LB);

    final InetAddress nodeGateway6 = ula6(clusterId, ROLE_NODE, nodeGatewayInetaddr);
    final InetAddress nodeHost6 = ula6(clusterId, ROLE_NODE, nodeHostInetaddr);
    final InetAddress vipGateway6 = ula6(clusterId, ROLE_VIP, vipGatewayInetaddr);
    final InetAddress vipHost6 = ula6(clusterId, ROLE_VIP, vipHostInetaddr);
    final InetAddress lanHost6 = ula6(clusterId, ROLE_LAN_NODE, lanHostInetaddr);
    final InetAddress lanGateway6 = ula6(clusterId, ROLE_LAN_NODE, lanGatewayInetaddr);
    final InetAddress lanHeadscale6 = ula6(clusterId, ROLE_LAN_LB, lanHeadscaleInetaddr);
    final InetAddress lanTailscale6 = ula6(clusterId, ROLE_LAN_LB, lanTailscaleInetaddr);

    return new ClusterNetworkBlueprint(
        new ClusterRef(clusterName, clusterId),
        new NodeRef(nodeName, nodeId, nodeType),
        new HostPlan(
            Cidr.parse("10.80.0.0/18"),
            clusterCidr,
            clusterGatewayInetaddr,
            superNetworkCidr6,
            clusterCidr6,
            nodeGateway6),
        new NodeNetworkPlan(
            nodeCidr, nodeGatewayInetaddr, nodeHostInetaddr, nodeCidr6, nodeGateway6, nodeHost6),
        new VipPlan(vipCidr, vipGatewayInetaddr, vipHostInetaddr, vipCidr6, vipGateway6, vipHost6),
        new LoadBalancerPlan(lbCidr, lbCidr6),
        new LanPlan(
            lanNodeCidr,
            lanLbCidr,
            lanHostInetaddr,
            lanGatewayInetaddr,
            lanHeadscaleInetaddr,
            lanTailscaleInetaddr,
            lanHostMacaddr,
            lanBridgeMacaddr,
            lanNodeCidr6,
            lanLbCidr6,
            lanHost6,
            lanGateway6,
            lanHeadscale6,
            lanTailscale6),
        new WanPlan(wanDhcpRange, wanHostMacaddr),
        new InterfacePlan(nodeName + "-lan0", nodeName + "-vmnet0", "vmnet0"),
        new VlanPlan(100, "rke2-vlan"),
        new NamePlan(
            clusterName + "-" + nodeName,
            clusterName + "-" + nodeName + ".local",
            clusterName + "-nixos"));
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

    /**
     * The rke2 wire form of the role ({@code server}/{@code agent}) — the single source both the
     * manifests node-env identity and the incus grow-plan identity project as {@code
     * RKE2LAB_NODE_KIND}. The enum constant lowercased IS that form, so the mapping lives once
     * here.
     */
    public String kind() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  public record ClusterRef(String name, int id) {}

  public record NodeRef(String name, int id, NodeType type) {}

  public record HostPlan(
      Cidr superNetworkCidr,
      Cidr clusterCidr,
      InetAddress clusterGatewayInetaddr,
      Cidr superNetworkCidr6,
      Cidr clusterCidr6,
      InetAddress clusterGatewayInetaddr6) {}

  public record NodeNetworkPlan(
      Cidr nodeCidr,
      InetAddress nodeGatewayInetaddr,
      InetAddress nodeHostInetaddr,
      Cidr nodeCidr6,
      InetAddress nodeGatewayInetaddr6,
      InetAddress nodeHostInetaddr6) {}

  public record VipPlan(
      Cidr vipCidr,
      InetAddress vipGatewayInetaddr,
      InetAddress vipHostInetaddr,
      Cidr vipCidr6,
      InetAddress vipGatewayInetaddr6,
      InetAddress vipHostInetaddr6) {}

  public record LoadBalancerPlan(Cidr lbCidr, Cidr lbCidr6) {}

  public record LanPlan(
      Cidr nodeCidr,
      Cidr lbCidr,
      InetAddress hostInetaddr,
      InetAddress gatewayInetaddr,
      InetAddress headscaleInetaddr,
      InetAddress tailscaleInetaddr,
      MacAddress hostMacaddr,
      MacAddress bridgeMacaddr,
      Cidr nodeCidr6,
      Cidr lbCidr6,
      InetAddress hostInetaddr6,
      InetAddress gatewayInetaddr6,
      InetAddress headscaleInetaddr6,
      InetAddress tailscaleInetaddr6) {}

  public record WanPlan(String dhcpRange, MacAddress hostMacaddr) {}

  public record InterfacePlan(String lanInterface, String wanInterface, String vipInterface) {}

  public record VlanPlan(int id, String name) {}

  /**
   * The identity-derived NAMES the cluster resolves nodes and infra hosts by — the single source
   * for hostnames domains otherwise re-concatenate. {@code nodeHostname} is the bare {@code
   * <cluster>-<node>}; {@code nodeMdnsFqdn} its mDNS name ({@code .local}, how a same-LAN host —
   * e.g. the seed's systemd probe — reaches it); {@code nixosHost} the {@code <cluster>-nixos}
   * builder/daemon host. Ports are NOT here: a port is a fixed service constant, not
   * identity-derived — each domain pairs a name from here with its own port.
   */
  public record NamePlan(String nodeHostname, String nodeMdnsFqdn, String nixosHost) {}

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
