// @codebase
package io.nxmatic.rke2lab.manifests.port.profiles;

/**
 * Cluster network topology slice published to synth-time layers via {@link
 * io.nxmatic.rke2lab.manifests.ManifestSynthesisContext}. Centralizes the CIDRs and interface names
 * previously templated by kpt setters ({@code ${cluster-cidr}}, {@code ${node-host-inetaddr}},
 * {@code ${cluster-vip-cidr}}, …).
 *
 * <p>Stage B (CAPN-managed nodes) will read most of these to produce {@code LXCMachineTemplate} /
 * {@code Cluster} resources, so the slice is shaped to match what the Cluster API provider needs.
 *
 * <p>Empty defaults are intentional: when nothing is bound, layers that look up topology data emit
 * a clearly-blank value, which is easier to spot in rendered manifests than a silent fallback to a
 * production address.
 */
public record NetworkTopology(
    String clusterCidr,
    String clusterPodCidr,
    String clusterServiceCidr,
    String nodeHostInetAddr,
    String nodeNetworkCidr,
    String nodeNetworkGatewayAddr,
    String clusterLoadBalancerCidr,
    String clusterLoadBalancerGatewayAddr,
    String lanInterface,
    String lanHostInetAddr,
    String lanLoadBalancerCidr,
    String wanInterface,
    String vipInterface,
    String vipCidr,
    String vipGatewayInetAddr,
    String vipHostInetAddr) {

  private static final NetworkTopology DEFAULT =
      new NetworkTopology("", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "");

  public NetworkTopology {
    clusterCidr = nullToBlank(clusterCidr);
    clusterPodCidr = nullToBlank(clusterPodCidr);
    clusterServiceCidr = nullToBlank(clusterServiceCidr);
    nodeHostInetAddr = nullToBlank(nodeHostInetAddr);
    nodeNetworkCidr = nullToBlank(nodeNetworkCidr);
    nodeNetworkGatewayAddr = nullToBlank(nodeNetworkGatewayAddr);
    clusterLoadBalancerCidr = nullToBlank(clusterLoadBalancerCidr);
    clusterLoadBalancerGatewayAddr = nullToBlank(clusterLoadBalancerGatewayAddr);
    lanInterface = nullToBlank(lanInterface);
    lanHostInetAddr = nullToBlank(lanHostInetAddr);
    lanLoadBalancerCidr = nullToBlank(lanLoadBalancerCidr);
    wanInterface = nullToBlank(wanInterface);
    vipInterface = nullToBlank(vipInterface);
    vipCidr = nullToBlank(vipCidr);
    vipGatewayInetAddr = nullToBlank(vipGatewayInetAddr);
    vipHostInetAddr = nullToBlank(vipHostInetAddr);
  }

  /** Empty topology — used when nothing has been bound (tests, ephemeral runs). */
  public static NetworkTopology empty() {
    return DEFAULT;
  }

  private static String nullToBlank(String value) {
    return value == null ? "" : value;
  }
}
