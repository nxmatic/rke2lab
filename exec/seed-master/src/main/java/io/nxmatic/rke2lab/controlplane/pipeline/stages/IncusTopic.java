package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap.BootstrapResult;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.osgi.runtime.BootedFramework;
import java.util.function.Consumer;

public final class IncusTopic {

  private final BootstrapConfig config;
  private final ControlplanePolicy policy;
  private final BootedFramework bootedFramework;
  private final Consumer<BootstrapResult> sink;

  public IncusTopic(
      BootstrapConfig config,
      ControlplanePolicy policy,
      BootedFramework bootedFramework,
      Consumer<BootstrapResult> sink) {
    this.config = config;
    this.policy = policy;
    this.bootedFramework = bootedFramework;
    this.sink = sink;
  }

  public IncusTopic provisionInstance() {
    sink.accept(new IncusResourceBootstrap(config, bootedFramework).apply(policy));
    return this;
  }
}
