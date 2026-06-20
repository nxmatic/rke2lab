package io.nxmatic.rke2lab.netplan.api;

import java.net.URI;
import java.util.Optional;

/**
 * External Net2Plan API endpoint contract for future mesh-topology integration.
 *
 * <p>This contract is intentionally lightweight so netplan can declare integration intent without
 * coupling current synthesis logic to a remote planner implementation.
 */
public record Net2PlanEndpoint(URI baseUri, String networkPlanPath) {

  private static final String ENDPOINT_PROPERTY = "rke2lab.netplan.net2plan.endpoint";
  private static final String ENDPOINT_ENV = "RK2LAB_NET2PLAN_API_ENDPOINT";
  private static final String PATH_PROPERTY = "rke2lab.netplan.net2plan.path";
  private static final String DEFAULT_NETWORK_PLAN_PATH = "/api/network-plans";

  public Net2PlanEndpoint {
    if (baseUri == null) {
      throw new IllegalArgumentException("baseUri must not be null");
    }

    final String scheme = baseUri.getScheme();
    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
      throw new IllegalArgumentException(
          "Net2Plan endpoint URI scheme must be http or https: " + baseUri);
    }

    if (networkPlanPath == null || networkPlanPath.isBlank()) {
      throw new IllegalArgumentException("networkPlanPath must not be blank");
    }

    networkPlanPath = normalizePath(networkPlanPath);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private URI baseUri;
    private String networkPlanPath = DEFAULT_NETWORK_PLAN_PATH;

    private Builder() {}

    public Builder baseUri(URI value) {
      this.baseUri = value;
      return this;
    }

    public Builder networkPlanPath(String value) {
      this.networkPlanPath = value;
      return this;
    }

    public Net2PlanEndpoint build() {
      return new Net2PlanEndpoint(baseUri, networkPlanPath);
    }
  }

  /** Resolve endpoint settings from system properties/environment when configured. */
  public static Optional<Net2PlanEndpoint> fromSystemProperties() {
    final String endpointValue = configuredEndpointValue();
    if (endpointValue == null || endpointValue.isBlank()) {
      return Optional.empty();
    }

    final String path =
        System.getProperty(PATH_PROPERTY) == null
            ? DEFAULT_NETWORK_PLAN_PATH
            : System.getProperty(PATH_PROPERTY);

    return Optional.of(
        builder().baseUri(URI.create(endpointValue.trim())).networkPlanPath(path).build());
  }

  /** Canonical full URL for posting/reading network plans in Net2Plan. */
  public URI networkPlanUri() {
    return baseUri.resolve(networkPlanPath);
  }

  private static String configuredEndpointValue() {
    final String propertyValue = System.getProperty(ENDPOINT_PROPERTY);
    if (propertyValue != null && !propertyValue.isBlank()) {
      return propertyValue;
    }

    final String envValue = System.getenv(ENDPOINT_ENV);
    if (envValue != null && !envValue.isBlank()) {
      return envValue;
    }

    return null;
  }

  private static String normalizePath(String value) {
    final String trimmed = value.trim();
    if (trimmed.startsWith("/")) {
      return trimmed;
    }
    return "/" + trimmed;
  }
}
