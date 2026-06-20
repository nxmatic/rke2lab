package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rke2lab.controlplane.bdd.ConsultationLog;
import io.nxmatic.rke2lab.controlplane.bdd.RunbookRenderer;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.pipeline.BootstrapOptions;
import io.nxmatic.rke2lab.controlplane.pipeline.BootstrapPipeline;
import io.nxmatic.rke2lab.controlplane.pipeline.OnFailure;
import io.nxmatic.rke2lab.controlplane.pipeline.OutputBuilder;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager;
import io.nxmatic.rke2lab.osgi.runtime.OsgiRuntime;
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
    // Boot the embedded OSGi framework once for the whole run. The stages read the manifests-world
    // services from its registry. Closed in the finally, after the pipeline AND the runbook render.
    final OsgiRuntime osgiRuntime = bootEmbeddedOsgiRuntime();
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
      return this;
    } finally {
      new RunbookRenderer(runbookOutputDir(), readinessLogger).render(runbook, consultations);
      osgiRuntime.close();
    }
  }

  /**
   * Boot Felix from the bundles embedded in this exec-jar — the same topology as {@code
   * EmbeddedBundlesBootTest}: pax-logging at the LogService layer, felix.scr + felix.resolver as
   * the runtime, manifests-core as the model bundle. Fails fast if the artifact was assembled
   * without its embedded bundles — that is a packaging defect, not a degraded run mode.
   */
  private OsgiRuntime bootEmbeddedOsgiRuntime() {
    if (!OsgiRuntime.hasEmbeddedBundles()) {
      throw new IllegalStateException(
          "exec-jar assembled without its embedded OSGi bundles under META-INF/bundles/");
    }
    try {
      return OsgiRuntime.builder()
          .embeddedPaxLogging("pax-logging-api.jar", "pax-logging-logback.jar")
          .withScr()
          .embeddedRuntimeJar("org.apache.felix.scr.jar")
          .embeddedRuntimeJar("org.apache.felix.resolver.jar")
          .embeddedBundle("manifests-core.jar")
          .build()
          .boot();
    } catch (java.io.IOException ex) {
      throw new IllegalStateException("failed to boot the embedded OSGi runtime", ex);
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
