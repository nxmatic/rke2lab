package io.nxmatic.rke2lab.manifests.units.ha;

import io.nxmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.contract.node.NodeEnvContext;
import io.nxmatic.rke2lab.manifests.contract.node.NodeEnvContributor;
import io.nxmatic.rke2lab.manifests.contract.profiles.NetworkTopology;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/** HighAvailability domain node-env contributor. Contributes: network-vip */
@Component(service = NodeEnvContributor.class)
public class HighAvailabilityNodeEnvContributor implements NodeEnvContributor {

  private final ManifestDomainCatalog manifestDomainCatalog =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

  @Override
  public String domainId() {
    return manifestDomainCatalog.highAvailability();
  }

  @Override
  public List<String> contributedSections() {
    return List.of("network-vip");
  }

  @Override
  public Map<String, String> contributeVariables(String sectionName, NodeEnvContext context)
      throws IOException {
    return switch (sectionName) {
      case "network-vip" -> {
        final NetworkTopology net = context.networkTopology();
        yield Map.of(
            "RKE2LAB_NETWORK_VIP_INTERFACE", net.vipInterface(),
            "RKE2LAB_NETWORK_VIP_CIDR", net.vipCidr(),
            "RKE2LAB_NETWORK_VIP_GATEWAY_INETADDR", net.vipGatewayInetAddr(),
            "RKE2LAB_NETWORK_VIP_HOST_INETADDR", net.vipHostInetAddr());
      }
      default -> Map.of();
    };
  }
}
