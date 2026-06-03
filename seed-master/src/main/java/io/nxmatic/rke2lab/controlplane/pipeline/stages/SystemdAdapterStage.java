package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import com.pulumi.deployment.Deployment;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterEndpointGate;
import java.util.Map;
import java.util.function.Consumer;

public final class SystemdAdapterStage {

  private final BootstrapConfig config;
  private final boolean pulumiMode;
  private final Consumer<String> readinessLogger;
  private final Consumer<Map<String, Object>> sink;

  public SystemdAdapterStage(
      BootstrapConfig config,
      boolean pulumiMode,
      Consumer<String> readinessLogger,
      Consumer<Map<String, Object>> sink) {
    this.config = config;
    this.pulumiMode = pulumiMode;
    this.readinessLogger = readinessLogger;
    this.sink = sink;
  }

  public SystemdAdapterStage launch() {
    final Map<String, Object> summary =
        pulumiMode && Deployment.getInstance().isDryRun()
            ? SeedSystemdAdapterEndpointGate.deferredPreview(config)
            : SeedSystemdAdapterEndpointGate.ensureReachable(config, readinessLogger);
    sink.accept(summary);
    return this;
  }
}
