package io.seedmatic.rke2lab.netplan;

import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import io.seedmatic.rke2lab.netplan.contract.NetplanSynthesisRequest;
import io.seedmatic.rke2lab.netplan.contract.NetplanSynthesisResult;
import io.seedmatic.rke2lab.netplan.contract.NetplanSynthesisService;
import org.osgi.service.component.annotations.Component;

/** Default SPI implementation for canonical netplan synthesis. */
@Component(service = NetplanSynthesisService.class)
public final class DefaultNetplanSynthesisService implements NetplanSynthesisService {

  @Override
  public String providerId() {
    return "default-netplan-synthesizer";
  }

  @Override
  public NetplanSynthesisResult synthesize(NetplanSynthesisRequest request) {
    final ClusterNetworkBlueprint blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster(request.clusterName())
            .node(request.nodeName())
            .deriveRecipeModel()
            .build();
    return new NetplanSynthesisResult(blueprint);
  }
}
