package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rke2lab.controlplane.bdd.ClusterSeedRun;
import io.nxmatic.rke2lab.controlplane.bdd.ClusterSeedScenario;
import io.nxmatic.rke2lab.controlplane.bdd.HostFacts;
import io.nxmatic.rke2lab.controlplane.bdd.HostSeeder;
import io.nxmatic.rke2lab.controlplane.bdd.RunbookRenderer;
import io.nxmatic.rke2lab.controlplane.bdd.SeedProbes;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.pipeline.BootstrapOptions;
import io.nxmatic.rke2lab.controlplane.pipeline.OutputBuilder;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager;
import io.nxmatic.rke2lab.doctor.port.ConsultationLog;
import io.nxmatic.rke2lab.osgi.runtime.framework.BootedFramework;
import io.nxmatic.rke2lab.osgi.runtime.framework.FrameworkLaunchPipeline;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.JUnitLauncherCore;
import io.nxmatic.rke2lab.pipeline.OnFailure;
import io.nxmatic.rke2lab.pipeline.Topic;
import io.nxmatic.rke2lab.pulumi.edge.LiveGate;
import io.nxmatic.rke2lab.pulumi.edge.RunMode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.store.Namespace;

/**
 * Cluster-seed topic: its body boots the embedded framework and launches {@link
 * ClusterSeedScenario} on the JUnit-platform launcher (the seeding is jGiven scenarios now, not the
 * fluent pipeline). Its config/policy/options/onFailure inputs arrive as {@link Supplier}s (the
 * read-face dual of a sink) — forwarded from the parent's builder without materializing here.
 * Pushes the collected outputs through its {@link Sink}.
 */
public final class ClusterSeedTopic implements Topic.Execution {

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
    final AtomicReference<ReportModel> runbook = new AtomicReference<>();
    final ConsultationLog consultations = new ConsultationLog();
    try {
      FrameworkLaunchPipeline.embedded()
          .during(
              "framework",
              framework -> seedClusterWithinFramework(framework, runbook, consultations));
    } finally {
      // Crash-safe render: the holder carries jGiven's model once HostSeeder published it (early,
      // in postProcessTestInstance), so an abort mid-run still renders whatever was recorded. Empty
      // only if the launch died before the scenario started — then there is nothing to render.
      final ReportModel model = runbook.get();
      if (model != null) {
        new RunbookRenderer(runbookOutputDir(), readinessLogger).render(model, consultations);
      }
    }
    return this;
  }

  /**
   * Boot done — now play {@link ClusterSeedScenario} on the launcher. The host builds the {@link
   * HostFacts} bag and an {@link OsgiConnection} attached (not owning) to the booted framework,
   * seeds them plus the run-model holder and the outputs sink into the launcher session store, runs
   * the scenario, and pushes the harvested outputs through the {@link Sink}. The runbook holder is
   * filled by {@link HostSeeder} from jGiven's own model; the outputs sink by the terminal {@code
   * OutputsStage} — both read back here through the {@link ClusterSeedRun} harvest.
   */
  private void seedClusterWithinFramework(
      BootedFramework framework,
      AtomicReference<ReportModel> runbook,
      ConsultationLog consultations) {
    final RunMode runMode = RunMode.detect(pulumiMode);
    final HostFacts facts =
        new HostFacts(
            configSupplier.get(),
            policySupplier.get(),
            optionsSupplier.get(),
            LiveGate.forRun(runMode),
            runMode.materialises(),
            bboxOrchestrator,
            resourceManager,
            outputBuilder,
            readinessLogger,
            onFailureSupplier.get(),
            consultations);
    final OsgiConnection connection = OsgiConnection.over(framework.context(), false, () -> {});
    final AtomicReference<Map<String, Object>> outputsSink = new AtomicReference<>();

    final ClusterSeedRun run;
    try {
      run =
          new JUnitLauncherCore<ClusterSeedRun>()
              .run(
                  getClass().getClassLoader(),
                  JupiterTestEngine.class,
                  wiring -> List.of(DiscoverySelectors.selectClass(ClusterSeedScenario.class)),
                  (launcher, request) -> {
                    launcher.execute(request);
                    return new ClusterSeedRun(runbook.get(), outputsSink.get());
                  },
                  store -> {
                    final Namespace ns = Namespace.create(HostSeeder.NS.getParts());
                    store.put(ns, HostSeeder.HOST_FACTS, facts);
                    store.put(ns, HostSeeder.CONNECTION, connection);
                    store.put(ns, HostSeeder.PROBES, SeedProbes.live());
                    store.put(ns, HostSeeder.RUN_MODEL, runbook);
                    store.put(ns, HostSeeder.OUTPUTS_SINK, outputsSink);
                  });
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted launching the cluster-seed scenario", interrupted);
    }

    final Map<String, Object> outputs = run.outputs();
    if (outputs != null) {
      sink.outputs(outputs);
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
