package io.nxmatic.rke2lab.manifests.node;

import io.nxmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.nxmatic.rke2lab.manifests.contract.node.NodeEnvContext;
import io.nxmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import java.nio.file.Path;
import java.util.Map;

/**
 * Default synthesis-time {@link NodeEnvContext} backed by canonical netplan blueprint derivation.
 */
public final class DefaultNodeEnvContext implements NodeEnvContext {

  private static final Path ROOT_PATH = Path.of("/srv/host");

  private static final String CLUSTER_NAME = "bioskop";

  private static final String NODE_NAME = "master";

  private final ClusterNetworkBlueprint blueprint =
      ClusterNetworkBlueprint.builder()
          .cluster(CLUSTER_NAME)
          .node(NODE_NAME)
          .deriveRecipeModel()
          .build();

  private final ManifestDomainPolicy manifestDomainPolicy;

  /** The run's policy carrier — a synth-time unit passes the policy from its unit context. */
  public DefaultNodeEnvContext(ManifestDomainPolicy manifestDomainPolicy) {
    this.manifestDomainPolicy = manifestDomainPolicy;
  }

  /** No policy in scope (units that emit no publish vars) — an empty, complete policy. */
  public DefaultNodeEnvContext() {
    this(new ManifestDomainPolicy(Map.of()));
  }

  @Override
  public ManifestDomainPolicy manifestDomainPolicy() {
    return manifestDomainPolicy;
  }

  @Override
  public Path rootPath() {
    return ROOT_PATH;
  }

  @Override
  public Path envDirPath() {
    return ROOT_PATH.resolve("rke2lab-environment.d");
  }

  @Override
  public Path scriptsDirPath() {
    return ROOT_PATH.resolve("systemd-scripts.d");
  }

  @Override
  public Path systemdDirPath() {
    return ROOT_PATH.resolve("systemd-units.d");
  }

  @Override
  public Path configDirPath() {
    return ROOT_PATH.resolve("rke2-config.d");
  }

  @Override
  public Path cloudconfigNocloudDirPath() {
    return ROOT_PATH.resolve("cloudconfig-nocloud.d");
  }

  @Override
  public Path manifestsDirPath() {
    return ROOT_PATH.resolve("rke2-manifests.d");
  }

  @Override
  public Path sharedDirPath() {
    return ROOT_PATH.resolve("rke2lab-share.d");
  }

  @Override
  public Path kubeconfigDirPath() {
    return ROOT_PATH.resolve("rke2lab-kube.d");
  }

  @Override
  public int nodeId() {
    return blueprint.node().id();
  }

  @Override
  public String nodeName() {
    return blueprint.node().name();
  }

  @Override
  public String nodeKind() {
    return switch (blueprint.node().type()) {
      case SERVER -> "server";
      case AGENT -> "agent";
    };
  }

  @Override
  public int clusterId() {
    return blueprint.cluster().id();
  }

  @Override
  public String clusterName() {
    return blueprint.cluster().name();
  }

  @Override
  public String clusterToken() {
    return clusterName();
  }

  @Override
  public String clusterDomain() {
    return "cluster.local";
  }

  @Override
  public String clusterCidr() {
    return blueprint.host().clusterCidr().toString();
  }

  @Override
  public String clusterPodCidr() {
    return "10.42.0.0/16";
  }

  @Override
  public String clusterServiceCidr() {
    return "10.43.0.0/16";
  }

  @Override
  public String nodeHostInetAddr() {
    return blueprint.nodeNetwork().nodeHostInetaddr().getHostAddress();
  }

  @Override
  public String nodeNetworkCidr() {
    return blueprint.nodeNetwork().nodeCidr().toString();
  }

  @Override
  public String nodeNetworkGatewayAddr() {
    return blueprint.nodeNetwork().nodeGatewayInetaddr().getHostAddress();
  }

  @Override
  public String clusterLoadBalancerCidr() {
    return blueprint.loadBalancer().lbCidr().toString();
  }

  @Override
  public String clusterLoadBalancerGatewayAddr() {
    return blueprint.lan().headscaleInetaddr().getHostAddress();
  }

  @Override
  public String lanInterface() {
    return blueprint.interfaces().lanInterface();
  }

  @Override
  public String lanHostInetAddr() {
    return blueprint.lan().hostInetaddr().getHostAddress();
  }

  @Override
  public String lanLoadBalancerCidr() {
    return blueprint.lan().lbCidr().toString();
  }

  @Override
  public String wanInterface() {
    return blueprint.interfaces().wanInterface();
  }

  @Override
  public String vipInterface() {
    return blueprint.interfaces().vipInterface();
  }

  @Override
  public String vipCidr() {
    return blueprint.vip().vipCidr().toString();
  }

  @Override
  public String vipGatewayInetAddr() {
    return blueprint.vip().vipGatewayInetaddr().getHostAddress();
  }

  @Override
  public String vipHostInetAddr() {
    return blueprint.vip().vipHostInetaddr().getHostAddress();
  }
}
