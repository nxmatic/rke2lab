package io.nxmatic.rk2lab.manifests.layers.ha;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContext;
import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContributor;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** HighAvailability layer environment variable contributor. Contributes: network-vip */
public class HighAvailabilityLayerEnvContributor implements LayerEnvContributor {

  private final ManifestDomainCatalog manifestDomainCatalog =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

  @Override
  public String layerId() {
    return manifestDomainCatalog.highAvailability();
  }

  @Override
  public List<String> contributedSections() {
    return List.of("network-vip");
  }

  @Override
  public Map<String, String> contributeVariables(String sectionName, LayerEnvContext context)
      throws IOException {
    return switch (sectionName) {
      case "network-vip" ->
          Map.of(
              "RKE2LAB_NETWORK_VIP_INTERFACE", "rke2-vip0",
              "RKE2LAB_NETWORK_VIP_CIDR", "10.80.7.0/24",
              "RKE2LAB_NETWORK_VIP_GATEWAY_INETADDR", "10.80.7.1",
              "RKE2LAB_NETWORK_VIP_HOST_INETADDR", "10.80.7.10");
      default -> Map.of();
    };
  }
}
