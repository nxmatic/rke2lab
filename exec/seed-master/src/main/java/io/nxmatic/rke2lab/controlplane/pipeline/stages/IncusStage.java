package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap.BootstrapResult;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.osgi.runtime.OsgiRuntime;
import java.util.function.Consumer;

public final class IncusStage {

  private final BootstrapConfig config;
  private final ControlplanePolicy policy;
  private final OsgiRuntime osgiRuntime;
  private final Consumer<BootstrapResult> sink;

  public IncusStage(
      BootstrapConfig config,
      ControlplanePolicy policy,
      OsgiRuntime osgiRuntime,
      Consumer<BootstrapResult> sink) {
    this.config = config;
    this.policy = policy;
    this.osgiRuntime = osgiRuntime;
    this.sink = sink;
  }

  public IncusStage provisionInstance() {
    sink.accept(new IncusResourceBootstrap(config, osgiRuntime).apply(policy));
    return this;
  }
}
