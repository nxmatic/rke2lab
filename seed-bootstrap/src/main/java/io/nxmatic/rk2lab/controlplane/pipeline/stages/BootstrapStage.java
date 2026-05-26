package io.nxmatic.rk2lab.controlplane.pipeline.stages;

import io.nxmatic.rk2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.pipeline.BootstrapOptions;
import io.nxmatic.rk2lab.controlplane.pipeline.BootstrapPipeline;
import io.nxmatic.rk2lab.controlplane.pipeline.OutputBuilder;
import io.nxmatic.rk2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rk2lab.controlplane.resources.ResourceManager;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class BootstrapStage {

  private final boolean pulumiMode;
  private final Supplier<BootstrapConfig> configSupplier;
  private final Supplier<ControlplanePolicy> policySupplier;
  private final Supplier<BootstrapOptions> optionsSupplier;
  private final Consumer<String> readinessLogger;
  private final BboxReconciliationOrchestrator bboxOrchestrator;
  private final ResourceManager resourceManager;
  private final OutputBuilder outputBuilder;
  private final Consumer<Map<String, Object>> outputsSink;

  public BootstrapStage(
      boolean pulumiMode,
      Supplier<BootstrapConfig> configSupplier,
      Supplier<ControlplanePolicy> policySupplier,
      Supplier<BootstrapOptions> optionsSupplier,
      Consumer<String> readinessLogger,
      BboxReconciliationOrchestrator bboxOrchestrator,
      ResourceManager resourceManager,
      OutputBuilder outputBuilder,
      Consumer<Map<String, Object>> outputsSink) {
    this.pulumiMode = pulumiMode;
    this.configSupplier = configSupplier;
    this.policySupplier = policySupplier;
    this.optionsSupplier = optionsSupplier;
    this.readinessLogger = readinessLogger;
    this.bboxOrchestrator = bboxOrchestrator;
    this.resourceManager = resourceManager;
    this.outputBuilder = outputBuilder;
    this.outputsSink = outputsSink;
  }

  public BootstrapStage runBootstrapPipeline() {
    final BootstrapPipeline.ComponentBoundPipeline ready =
        BootstrapPipeline.forCluster(configSupplier.get(), policySupplier.get())
            .withOptions(optionsSupplier.get())
            .using(bboxOrchestrator, resourceManager, outputBuilder);
    final BootstrapPipeline.AwaitingPreflight primed =
        pulumiMode
            ? ready.runningInPulumi(readinessLogger)
            : ready.runningStandalone(readinessLogger);
    final Map<String, Object> outputs =
        primed
            .during(
                "preflight",
                preflight ->
                    preflight
                        .enforceEntryGates()
                        .requireLocalCommands("ssh", "kubectl")
                        .requireRemoteCommand("incus"))
            .then()
            .during("bbox reconciliation", bbox -> bbox.reconcileReservations())
            .then()
            .during("incus provisioning", incus -> incus.provisionInstance())
            .then()
            .during("systemd adapter", adapter -> adapter.launch())
            .then()
            .during("bootstrap resources", resources -> resources.createAll())
            .collectOutputs();
    outputsSink.accept(outputs);
    return this;
  }
}
