package io.nxmatic.rk2lab.netplan.api;

import java.util.Optional;

/** Request contract for canonical netplan synthesis. */
public record NetplanSynthesisRequest(
    String clusterName, String nodeName, Optional<Net2PlanEndpoint> net2PlanEndpoint) {

  public NetplanSynthesisRequest(String clusterName, String nodeName) {
    this(clusterName, nodeName, Optional.empty());
  }

  public NetplanSynthesisRequest {
    if (clusterName == null || clusterName.isBlank()) {
      throw new IllegalArgumentException("clusterName must not be blank");
    }
    if (nodeName == null || nodeName.isBlank()) {
      throw new IllegalArgumentException("nodeName must not be blank");
    }

    if (net2PlanEndpoint == null) {
      throw new IllegalArgumentException("net2PlanEndpoint must not be null");
    }

    clusterName = clusterName.trim();
    nodeName = nodeName.trim();
  }

  public static NetplanSynthesisRequest fromSystemProperties() {
    final String cluster = System.getProperty("rk2lab.netplan.cluster", "bioskop");
    final String node = System.getProperty("rk2lab.netplan.node", "master");
    return new NetplanSynthesisRequest(cluster, node, Net2PlanEndpoint.fromSystemProperties());
  }
}
