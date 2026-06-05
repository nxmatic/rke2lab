package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import com.pulumi.deployment.Deployment;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.text.PlainTextReporter;
import io.nxmatic.rke2lab.controlplane.bdd.GivenSystemdAdapter;
import io.nxmatic.rke2lab.controlplane.bdd.SystemdAdapterProbe;
import io.nxmatic.rke2lab.controlplane.bdd.ThenSystemdAdapter;
import io.nxmatic.rke2lab.controlplane.bdd.WhenSystemdAdapter;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.pipeline.PipelineStageFailure;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterEndpointGate;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Readiness gate, played as the BDD scenario it documents. The same Given/When/Then that runs
 * offline in tests runs here against the real probe; the scenario's captured snapshot becomes the
 * gate summary that flows into {@code SystemdAdapterResource}. During a Pulumi preview the gate
 * sets JGiven's dry-run property so the scenario renders its living-doc report without probing live
 * infrastructure — the same "walk structure, emit doc, no side effects" notion as {@code pulumi
 * preview} itself.
 */
public final class SystemdAdapterStage {

  private static final String JGIVEN_DRY_RUN = "jgiven.report.dry-run";

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
    final boolean dryRun = pulumiMode && Deployment.getInstance().isDryRun();
    final SystemdAdapterProbe probe =
        cfg -> SeedSystemdAdapterEndpointGate.ensureReachable(cfg, readinessLogger);

    // Standalone (non-JUnit) scenarios have no report model wired; finished() NPEs without one.
    // Held outside the try so the prose is logged even when the probe fails the scenario.
    final ReportModel reportModel = new ReportModel();

    final String previousDryRun = System.getProperty(JGIVEN_DRY_RUN);
    if (dryRun) {
      System.setProperty(JGIVEN_DRY_RUN, "true");
    }
    try {
      final Scenario<GivenSystemdAdapter, WhenSystemdAdapter, ThenSystemdAdapter> scenario =
          Scenario.create(
              GivenSystemdAdapter.class, WhenSystemdAdapter.class, ThenSystemdAdapter.class);
      scenario.setModel(reportModel);
      scenario.startScenario("systemd adapter becomes reachable");
      scenario.given().the_seed_node(config.systemdAdapterDbusHost(), config).probed_by(probe);
      scenario.when().the_systemd_adapter_probe_runs();
      scenario.then().the_dbus_endpoint_responds();
      scenario.finished();

      // Dry-run skips step bodies, so no snapshot is produced; report the deferred-preview
      // envelope.
      final Map<String, Object> captured = scenario.then().capturedSnapshot();
      sink.accept(
          captured != null ? captured : SeedSystemdAdapterEndpointGate.deferredPreview(config));
    } catch (Throwable cause) {
      throw new PipelineStageFailure("systemd adapter", cause);
    } finally {
      // The living-doc IS the gate's output: stream the Given/When/Then prose into the Pulumi log
      // so
      // the operator reads what was verified — inline, during preview and provisioning, pass or
      // fail.
      logReport(reportModel);
      if (previousDryRun == null) {
        System.clearProperty(JGIVEN_DRY_RUN);
      } else {
        System.setProperty(JGIVEN_DRY_RUN, previousDryRun);
      }
    }
    return this;
  }

  private void logReport(ReportModel reportModel) {
    if (readinessLogger == null) {
      return;
    }
    try {
      PlainTextReporter.toString(reportModel).lines().forEach(readinessLogger);
    } catch (Exception ignored) {
      // The report is a narration aid; never let rendering it fail the gate.
    }
  }
}
