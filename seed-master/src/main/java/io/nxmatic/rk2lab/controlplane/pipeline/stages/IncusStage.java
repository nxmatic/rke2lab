package io.nxmatic.rk2lab.controlplane.pipeline.stages;

import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rk2lab.controlplane.incus.IncusResourceBootstrap.BootstrapResult;
import io.nxmatic.rk2lab.controlplane.policy.ControlplanePolicy;
import java.util.function.Consumer;

public final class IncusStage {

  private final BootstrapConfig config;
  private final ControlplanePolicy policy;
  private final Consumer<BootstrapResult> sink;

  public IncusStage(
      BootstrapConfig config, ControlplanePolicy policy, Consumer<BootstrapResult> sink) {
    this.config = config;
    this.policy = policy;
    this.sink = sink;
  }

  public IncusStage provisionInstance() {
    sink.accept(new IncusResourceBootstrap(config, policy).apply());
    return this;
  }
}
