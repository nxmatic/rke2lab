package io.nxmatic.rk2lab.manifests.layers.env;

import java.nio.file.Path;

/**
 * Read-only bootstrap context passed to layer env contributors. Provides access to bootstrap-time
 * paths, node identity, and cluster topology.
 */
public interface LayerEnvContext {

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

  // Network Topology (populated by networking layer during init)
  String clusterCidr(); // "10.80.0.0/21"

  String clusterPodCidr(); // "10.42.0.0/16"

  String clusterServiceCidr(); // "10.43.0.0/16"

  String nodeHostInetAddr(); // "10.80.0.10"

  String nodeNetworkCidr(); // "10.80.0.0/23"

  String nodeNetworkGatewayAddr(); // "10.80.0.1"
}
