package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rke2lab.controlplane.bdd.RunbookRenderer;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.pipeline.BootstrapOptions;
import io.nxmatic.rke2lab.controlplane.pipeline.ClusterSeedPipeline;
import io.nxmatic.rke2lab.controlplane.pipeline.OutputBuilder;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager;
import io.nxmatic.rke2lab.doctor.port.ConsultationLog;
import io.nxmatic.rke2lab.osgi.runtime.framework.BootedFramework;
import io.nxmatic.rke2lab.osgi.runtime.framework.FrameworkLaunchPipeline;
import io.nxmatic.rke2lab.pipeline.OnFailure;
import io.nxmatic.rke2lab.pipeline.Topic;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Cluster-seed topic — a {@link Topic.Pipeline}: its body launches the nested {@link
 * ClusterSeedPipeline} (under a booted framework), so it is a pipeline nature, not a plain
 * execution. Its config/policy/options/onFailure inputs arrive as {@link Supplier}s (the read-face
 * dual of a sink) — forwarded from the parent's builder without materializing here. Pushes the
 * collected outputs through its {@link Sink}.
 */
public final class ClusterSeedTopic implements Topic.Pipeline {

  private final boolean pulumiMode;
  private final Supplier<BootstrapConfig> configSupplier;
  private final Supplier<ControlplanePolicy> policySupplier;
  private final Supplier<BootstrapOptions> optionsSupplier;
  private final Supplier<OnFailure> onFailureSupplier;
  private final Consumer<String> readinessLogger;
  private final BboxReconciliationOrchestrator bboxOrchestrator;
  private final ResourceManager resourceManager;
  private final OutputBuilder outputBuilder;
  private final Sink sink;

  public ClusterSeedTopic(
      boolean pulumiMode,
      Supplier<BootstrapConfig> configSupplier,
      Supplier<ControlplanePolicy> policySupplier,
      Supplier<BootstrapOptions> optionsSupplier,
      Supplier<OnFailure> onFailureSupplier,
      Consumer<String> readinessLogger,
      BboxReconciliationOrchestrator bboxOrchestrator,
      ResourceManager resourceManager,
      OutputBuilder outputBuilder,
      Sink sink) {
    this.pulumiMode = pulumiMode;
    this.configSupplier = configSupplier;
    this.policySupplier = policySupplier;
    this.optionsSupplier = optionsSupplier;
    this.onFailureSupplier = onFailureSupplier;
    this.readinessLogger = readinessLogger;
    this.bboxOrchestrator = bboxOrchestrator;
    this.resourceManager = resourceManager;
    this.outputBuilder = outputBuilder;
    this.sink = sink;
  }

  /** The write-face of the cluster-seed topic — the collected stack outputs. */
  public interface Sink extends Topic.Sink {
    void outputs(Map<String, Object> outputs);
  }

  @Override
  public String role() {
    return "cluster seed";
  }

  /**
   * Seeds the cluster across two altitudes, kept explicitly apart:
   *
   * <ol>
   *   <li>the <em>framework-launch crossing</em> ({@link FrameworkLaunchPipeline#embedded()}) —
   *       boot the embedded OSGi framework once for the whole run, so the reasoning below can read
   *       the manifests-world services from its registry;
   *   <li>the <em>cluster-seed reasoning body</em> ({@link #seedClusterWithinFramework}) — run
   *       under the booted framework.
   * </ol>
   *
   * <p>The runbook is owned here and recorded into by every checkpoint. It is rendered in the
   * reasoning body's own {@code finally}, which runs BEFORE {@code FrameworkLaunchPipeline} closes
   * the framework — so a CRITICAL stop (a checkpoint that throws to abort provisioning) still
   * produces a runbook, exactly the failure the runbook exists to document.
   */
  public ClusterSeedTopic seedCluster() {
    final ReportModel runbook = new ReportModel();
    final ConsultationLog consultations = new ConsultationLog();
    FrameworkLaunchPipeline.embedded()
        .during(
            "framework",
            framework -> seedClusterWithinFramework(framework, runbook, consultations));
    return this;
  }

  private void seedClusterWithinFramework(
      BootedFramework framework, ReportModel runbook, ConsultationLog consultations) {
    try {
      ClusterSeedPipeline.ComponentBoundPipeline ready =
          ClusterSeedPipeline.forCluster(configSupplier.get(), policySupplier.get())
              .withOptions(optionsSupplier.get())
              .using(bboxOrchestrator, resourceManager, outputBuilder)
              .withBootedFramework(framework)
              .recordingInto(runbook, consultations);
      final OnFailure handler = onFailureSupplier.get();
      if (handler != null) {
        ready = ready.onFailure(handler);
      }
      final ClusterSeedPipeline.AwaitingPreflight primed =
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
      sink.outputs(outputs);
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
