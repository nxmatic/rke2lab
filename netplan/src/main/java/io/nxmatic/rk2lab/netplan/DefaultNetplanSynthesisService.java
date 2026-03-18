package io.nxmatic.rk2lab.netplan;

import io.nxmatic.rk2lab.netplan.api.NetplanSynthesisRequest;
import io.nxmatic.rk2lab.netplan.api.NetplanSynthesisResult;
import io.nxmatic.rk2lab.netplan.api.NetplanSynthesisService;

/** Default SPI implementation for canonical netplan synthesis. */
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
