package io.nxmatic.rk2lab.netplan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

class Net2PlanEndpointTest {

  @Test
  void normalizesRelativePath_andResolvesCanonicalNetworkPlanUri() {
    Net2PlanEndpoint endpoint =
        new Net2PlanEndpoint(URI.create("https://net2plan.example.internal:8443"), "network-plans");

    assertEquals("/network-plans", endpoint.networkPlanPath());
    assertEquals(
        URI.create("https://net2plan.example.internal:8443/network-plans"),
        endpoint.networkPlanUri());
  }

  @Test
  void rejectsUnsupportedSchemes() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Net2PlanEndpoint(
                URI.create("ftp://net2plan.example.internal"), "/api/network-plans"));
  }
}
