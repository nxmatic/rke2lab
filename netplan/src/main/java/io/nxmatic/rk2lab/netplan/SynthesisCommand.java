package io.nxmatic.rk2lab.netplan;

import io.nxmatic.rk2lab.netplan.api.NetplanSynthesisRequest;
import io.nxmatic.rk2lab.netplan.api.NetplanSynthesisResult;
import io.nxmatic.rk2lab.netplan.api.NetplanSynthesisService;
import java.util.List;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SynthesisCommand implements NetplanCli.Command {

  private static final Logger LOG = LoggerFactory.getLogger(SynthesisCommand.class);

  @Override
  public void execute(String[] args) {
    final NetplanSynthesisService service = loadService();
    final NetplanSynthesisRequest request = NetplanSynthesisRequest.fromSystemProperties();
    final NetplanSynthesisResult result = service.synthesize(request);

    LOG.info("Netplan synthesis completed by provider '{}'", service.providerId());
    LOG.info("Derived blueprint: {}", result.blueprint().ref());
    request
        .net2PlanEndpoint()
        .ifPresent(
            endpoint ->
                LOG.info(
                    "Net2Plan API endpoint configured at '{}' (network plan URL '{}')",
                    endpoint.baseUri(),
                    endpoint.networkPlanUri()));
  }

  private static NetplanSynthesisService loadService() {
    final List<NetplanSynthesisService> providers =
        ServiceLoader.load(NetplanSynthesisService.class).stream()
            .map(ServiceLoader.Provider::get)
            .toList();

    if (providers.isEmpty()) {
      throw new IllegalStateException(
          "No NetplanSynthesisService provider found via ServiceLoader.");
    }

    if (providers.size() > 1) {
      throw new IllegalStateException(
          "Expected exactly one NetplanSynthesisService provider, found "
              + providers.size()
              + ": "
              + providers.stream().map(NetplanSynthesisService::providerId).toList());
    }

    return providers.getFirst();
  }
}
