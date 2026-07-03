package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap.BootstrapResult;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.osgi.runtime.BootedFramework;
import io.nxmatic.rke2lab.pipeline.Topic;

/** Incus-provisioning topic. Pushes the bootstrap result through its {@link Sink}. */
public final class IncusTopic implements Topic.Execution {

  private final BootstrapConfig config;
  private final ControlplanePolicy policy;
  private final BootedFramework bootedFramework;
  private final Sink sink;

  public IncusTopic(
      BootstrapConfig config,
      ControlplanePolicy policy,
      BootedFramework bootedFramework,
      Sink sink) {
    this.config = config;
    this.policy = policy;
    this.bootedFramework = bootedFramework;
    this.sink = sink;
  }

  /** The write-face of the incus topic. */
  public interface Sink extends Topic.Sink {
    void bootstrap(BootstrapResult result);
  }

  @Override
  public String role() {
    return "incus provisioning";
  }

  public IncusTopic provisionInstance() {
    sink.bootstrap(new IncusResourceBootstrap(config, bootedFramework).apply(policy));
    return this;
  }
}
