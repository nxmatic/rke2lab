package io.nxmatic.rke2lab.netplan.cli;

import io.nxmatic.rke2lab.netplan.api.NetplanSynthesisRequest;
import io.nxmatic.rke2lab.netplan.api.NetplanSynthesisResult;
import io.nxmatic.rke2lab.netplan.api.NetplanSynthesisService;
import io.nxmatic.rke2lab.osgi.runtime.framework.FrameworkLaunchPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SynthesisCommand implements NetplanCli.Command {

  private static final Logger LOG = LoggerFactory.getLogger(SynthesisCommand.class);

  @Override
  public void execute(String[] args) {
    // Boot the embedded Felix from the bundles staged in this exec-jar (the shared boot seam),
    // resolve the one netplan service from the registry, drive it, then close. No flat-classpath
    // fallback: netplan-core's @Component activates only under a framework.
    FrameworkLaunchPipeline.embedded()
        .during("synthesis", NetplanSynthesisService.class, this::synthesize);
  }

  private void synthesize(NetplanSynthesisService service) {
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
}
