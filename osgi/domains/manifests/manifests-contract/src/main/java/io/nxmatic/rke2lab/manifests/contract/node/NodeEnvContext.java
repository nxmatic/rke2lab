package io.nxmatic.rke2lab.manifests.contract.node;

import io.nxmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.nxmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.nxmatic.rke2lab.manifests.contract.profiles.NetworkTopology;
import java.nio.file.Path;

/**
 * Read-only context passed to node-env contributors. Provides access to bootstrap-time paths, node
 * identity, and cluster topology.
 */
public interface NodeEnvContext {

  // Bootstrap Paths
  Path rootPath(); // /srv/host

  Path envDirPath(); // /srv/host/rke2lab-environment.d

  Path scriptsDirPath(); // /srv/host/systemd-scripts.d

  Path systemdDirPath(); // /srv/host/systemd-units.d

  Path configDirPath(); // /srv/host/rke2-config.d

  Path cloudconfigNocloudDirPath(); // /srv/host/cloudconfig-nocloud.d

  Path manifestsDirPath(); // /srv/host/rke2-manifests.d

  Path sharedDirPath(); // /srv/host/rke2lab-share.d

  Path kubeconfigDirPath(); // /srv/host/rke2lab-kube.d

  // Node Identity
  int nodeId(); // 0 for master

  String nodeName(); // "master"

  String nodeKind(); // "server" for control plane

  // Cluster Identity
  int clusterId(); // 0

  String clusterName(); // "bioskop"

  String clusterToken(); // "bioskop"

  String clusterDomain(); // "cluster.local"

  // Network Topology (populated by networking domain during init)
  String clusterCidr(); // "10.80.0.0/21"

  String clusterPodCidr(); // "10.42.0.0/16"

  String clusterServiceCidr(); // "10.43.0.0/16"

  String nodeHostInetAddr(); // "10.80.0.10"

  String nodeNetworkCidr(); // "10.80.0.0/23"

  String nodeNetworkGatewayAddr(); // "10.80.0.1"

  String clusterLoadBalancerCidr(); // "10.80.0.64/26"

  String clusterLoadBalancerGatewayAddr(); // "10.80.0.65"

  String lanInterface(); // "master-lan0"

  String lanHostInetAddr(); // "192.168.1.131"

  String lanLoadBalancerCidr(); // "192.168.1.192/27"

  String wanInterface(); // "master-vmnet0"

  String vipInterface(); // "rke2-vip0"

  String vipCidr(); // "10.80.7.0/24"

  String vipGatewayInetAddr(); // "10.80.7.1"

  String vipHostInetAddr(); // "10.80.7.10"

  // Publish policy — the run's manifest-domain decision (which layers are enabled). Carried here so
  // a node-env contributor can derive the RKE2LAB_MANIFESTS_PUBLISH_* vars from the same run-scoped
  // policy that drives the synth-time domain filter.
  ManifestDomainPolicy manifestDomainPolicy();

  // Incus Infrastructure Identity
  default String incusRemoteName() {
    return clusterName(); // Default: remote name = cluster name
  }

  /**
   * Identity slice for synth-time consumption. The cluster env is left blank by default — the env
   * loader / Pulumi config can override per cluster as that surface materializes.
   */
  default BootstrapIdentity bootstrapIdentity() {
    return new BootstrapIdentity(
        clusterName(),
        clusterId(),
        clusterToken(),
        clusterDomain(),
        "",
        nodeName(),
        nodeId(),
        nodeKind(),
        incusRemoteName());
  }

  /** Network topology slice for synth-time consumption. */
  default NetworkTopology networkTopology() {
    return new NetworkTopology(
        clusterCidr(),
        clusterPodCidr(),
        clusterServiceCidr(),
        nodeHostInetAddr(),
        nodeNetworkCidr(),
        nodeNetworkGatewayAddr(),
        clusterLoadBalancerCidr(),
        clusterLoadBalancerGatewayAddr(),
        lanInterface(),
        lanHostInetAddr(),
        lanLoadBalancerCidr(),
        wanInterface(),
        vipInterface(),
        vipCidr(),
        vipGatewayInetAddr(),
        vipHostInetAddr());
  }
}
