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

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String clusterName = "bioskop";
    private String nodeName = "master";
    private Optional<Net2PlanEndpoint> net2PlanEndpoint = Optional.empty();

    private Builder() {}

    public Builder clusterName(String value) {
      this.clusterName = value;
      return this;
    }

    public Builder nodeName(String value) {
      this.nodeName = value;
      return this;
    }

    public Builder net2PlanEndpoint(Optional<Net2PlanEndpoint> value) {
      this.net2PlanEndpoint = value;
      return this;
    }

    public Builder net2PlanEndpoint(Net2PlanEndpoint value) {
      this.net2PlanEndpoint = Optional.ofNullable(value);
      return this;
    }

    public NetplanSynthesisRequest build() {
      return new NetplanSynthesisRequest(clusterName, nodeName, net2PlanEndpoint);
    }
  }

  public static NetplanSynthesisRequest fromSystemProperties() {
    final String cluster = System.getProperty("rk2lab.netplan.cluster", "bioskop");
    final String node = System.getProperty("rk2lab.netplan.node", "master");
    return builder()
        .clusterName(cluster)
        .nodeName(node)
        .net2PlanEndpoint(Net2PlanEndpoint.fromSystemProperties())
        .build();
  }
}
