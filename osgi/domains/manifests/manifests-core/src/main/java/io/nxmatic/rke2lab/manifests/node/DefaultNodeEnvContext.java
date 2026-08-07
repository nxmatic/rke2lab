package io.nxmatic.rke2lab.manifests.node;

import io.nxmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.nxmatic.rke2lab.manifests.contract.node.NodeEnvContext;
import io.nxmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.nxmatic.rke2lab.manifests.contract.profiles.HostPaths;
import io.nxmatic.rke2lab.manifests.contract.profiles.NetworkTopology;
import io.nxmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import java.nio.file.Path;

/**
 * Default synthesis-time {@link NodeEnvContext} backed by canonical netplan blueprint derivation.
 */
public final class DefaultNodeEnvContext implements NodeEnvContext {

  private static final Path ROOT_PATH = Path.of("/srv/host");

  /**
   * The lab's control node. The blueprint requires a canonical node name; when the handed-over
   * identity carries none (a bare/ephemeral run with no seeded identity), fall back to the single
   * master this lab grows — the node role, not cluster identity.
   */
  private static final String DEFAULT_NODE_NAME = "master";

  private final ClusterNetworkBlueprint blueprint;

  private final ManifestDomainPolicy manifestDomainPolicy;

  /**
   * Derives the node's network blueprint from the run's handed-over {@link BootstrapIdentity} — the
   * single source of the cluster name (no compile-time literal). The topology is a pure function of
   * the cluster + node name (see {@code ClusterNetworkBlueprint.deriveRecipeModel}), so the
   * identity is all this context needs to project the whole node environment.
   */
  public DefaultNodeEnvContext(
      final BootstrapIdentity identity, final ManifestDomainPolicy manifestDomainPolicy) {
    final String identityNode = identity.nodeName();
    final String nodeName =
        (identityNode == null
                || identityNode.isBlank()
                || BootstrapIdentity.UNKNOWN.equals(identityNode))
            ? DEFAULT_NODE_NAME
            : identityNode;
    this.blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster(identity.clusterName())
            .node(nodeName)
            .deriveRecipeModel()
            .build();
    this.manifestDomainPolicy = manifestDomainPolicy;
  }

  @Override
  public ManifestDomainPolicy manifestDomainPolicy() {
    return manifestDomainPolicy;
  }

  @Override
  public HostPaths hostPaths() {
    return HostPaths.builder()
        .rootPath(ROOT_PATH)
        .envDirPath(ROOT_PATH.resolve("rke2lab-environment.d"))
        .scriptsDirPath(ROOT_PATH.resolve("systemd-scripts.d"))
        .systemdDirPath(ROOT_PATH.resolve("systemd-units.d"))
        .configDirPath(ROOT_PATH.resolve("rke2-config.d"))
        .cloudconfigNocloudDirPath(ROOT_PATH.resolve("cloudconfig-nocloud.d"))
        .manifestsDirPath(ROOT_PATH.resolve("rke2-manifests.d"))
        .sharedDirPath(ROOT_PATH.resolve("rke2lab-share.d"))
        .build();
  }

  @Override
  public BootstrapIdentity bootstrapIdentity() {
    final String clusterName = blueprint.cluster().name();
    return BootstrapIdentity.builder()
        .clusterName(clusterName)
        .clusterId(blueprint.cluster().id())
        .clusterToken(clusterName)
        .clusterDomain("cluster.local")
        .nodeName(blueprint.node().name())
        .nodeId(blueprint.node().id())
        .nodeKind(blueprint.node().type().kind())
        .incusRemoteName(clusterName)
        .build();
  }

  @Override
  public NetworkTopology networkTopology() {
    return NetworkTopology.builder()
        .clusterCidr(blueprint.host().clusterCidr().toString())
        .clusterPodCidr("10.42.0.0/16")
        .clusterServiceCidr("10.43.0.0/16")
        .nodeHostInetAddr(blueprint.nodeNetwork().nodeHostInetaddr().getHostAddress())
        .nodeNetworkCidr(blueprint.nodeNetwork().nodeCidr().toString())
        .nodeNetworkGatewayAddr(blueprint.nodeNetwork().nodeGatewayInetaddr().getHostAddress())
        .clusterLoadBalancerCidr(blueprint.loadBalancer().lbCidr().toString())
        .clusterLoadBalancerGatewayAddr(blueprint.lan().headscaleInetaddr().getHostAddress())
        .lanInterface(blueprint.interfaces().lanInterface())
        .lanHostInetAddr(blueprint.lan().hostInetaddr().getHostAddress())
        .lanLoadBalancerCidr(blueprint.lan().lbCidr().toString())
        .wanInterface(blueprint.interfaces().wanInterface())
        .vipInterface(blueprint.interfaces().vipInterface())
        .vipCidr(blueprint.vip().vipCidr().toString())
        .vipGatewayInetAddr(blueprint.vip().vipGatewayInetaddr().getHostAddress())
        .vipHostInetAddr(blueprint.vip().vipHostInetaddr().getHostAddress())
        .lanHostMacAddr(blueprint.lan().hostMacaddr().value())
        .wanHostMacAddr(blueprint.wan().hostMacaddr().value())
        .build();
  }
}
