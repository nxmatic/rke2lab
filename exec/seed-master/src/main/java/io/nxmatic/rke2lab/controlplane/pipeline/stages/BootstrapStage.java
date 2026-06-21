package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rke2lab.controlplane.bdd.RunbookRenderer;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.pipeline.BootstrapOptions;
import io.nxmatic.rke2lab.controlplane.pipeline.BootstrapPipeline;
import io.nxmatic.rke2lab.controlplane.pipeline.OutputBuilder;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager;
import io.nxmatic.rke2lab.doctor.ConsultationLog;
import io.nxmatic.rke2lab.osgi.runtime.OsgiRuntime;
import io.nxmatic.rke2lab.osgi.runtime.SeedRuntime;
import io.nxmatic.rke2lab.pipeline.OnFailure;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class BootstrapStage {

  private final boolean pulumiMode;
  private final Supplier<BootstrapConfig> configSupplier;
  private final Supplier<ControlplanePolicy> policySupplier;
  private final Supplier<BootstrapOptions> optionsSupplier;
  private final Supplier<OnFailure> onFailureSupplier;
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
      Supplier<OnFailure> onFailureSupplier,
      Consumer<String> readinessLogger,
      BboxReconciliationOrchestrator bboxOrchestrator,
      ResourceManager resourceManager,
      OutputBuilder outputBuilder,
      Consumer<Map<String, Object>> outputsSink) {
    this.pulumiMode = pulumiMode;
    this.configSupplier = configSupplier;
    this.policySupplier = policySupplier;
    this.optionsSupplier = optionsSupplier;
    this.onFailureSupplier = onFailureSupplier;
    this.readinessLogger = readinessLogger;
    this.bboxOrchestrator = bboxOrchestrator;
    this.resourceManager = resourceManager;
    this.outputBuilder = outputBuilder;
    this.outputsSink = outputsSink;
  }

  public BootstrapStage runBootstrapPipeline() {
    // The runbook is owned here, recorded into by every checkpoint, and rendered in the finally so
    // a CRITICAL stop (a checkpoint that throws to abort provisioning) still produces a runbook —
    // exactly the failure the runbook exists to document.
    final ReportModel runbook = new ReportModel();
    final ConsultationLog consultations = new ConsultationLog();
    // Boot the embedded OSGi framework once for the whole run via the shared SeedRuntime seam; the
    // stages read the manifests-world services from its registry. SeedRuntime closes the framework
    // after the tail returns or throws — and the runbook render in the tail's own finally runs
    // BEFORE that close, so a CRITICAL stop still produces a runbook.
    SeedRuntime.bootingEmbedded("manifests-core.jar")
        .during("bootstrap", osgiRuntime -> runUnderRuntime(osgiRuntime, runbook, consultations));
    return this;
  }

  private void runUnderRuntime(
      OsgiRuntime osgiRuntime, ReportModel runbook, ConsultationLog consultations) {
    try {
      BootstrapPipeline.ComponentBoundPipeline ready =
          BootstrapPipeline.forCluster(configSupplier.get(), policySupplier.get())
              .withOptions(optionsSupplier.get())
              .using(bboxOrchestrator, resourceManager, outputBuilder)
              .withOsgiRuntime(osgiRuntime)
              .recordingInto(runbook, consultations);
      final OnFailure handler = onFailureSupplier.get();
      if (handler != null) {
        ready = ready.onFailure(handler);
      }
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
    } finally {
      new RunbookRenderer(runbookOutputDir(), readinessLogger).render(runbook, consultations);
    }
  }

  /**
   * Where the rendered runbook lands: under the build output tree (already git-ignored), resolved
   * from the seed worktree so it sits beside the jar Pulumi runs.
   */
  private Path runbookOutputDir() {
    return configSupplier.get().localWorktreePath().resolve("seed-master/target/runbook");
  }
}
