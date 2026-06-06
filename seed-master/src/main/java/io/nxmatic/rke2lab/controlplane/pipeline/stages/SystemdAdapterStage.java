package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import com.pulumi.deployment.Deployment;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.text.PlainTextReporter;
import io.nxmatic.rke2lab.controlplane.bdd.GivenSystemdAdapter;
import io.nxmatic.rke2lab.controlplane.bdd.Severity;
import io.nxmatic.rke2lab.controlplane.bdd.SimulatedSystemdAdapterProbe;
import io.nxmatic.rke2lab.controlplane.bdd.Symptom;
import io.nxmatic.rke2lab.controlplane.bdd.SystemdAdapterProbe;
import io.nxmatic.rke2lab.controlplane.bdd.ThenSystemdAdapter;
import io.nxmatic.rke2lab.controlplane.bdd.WhenSystemdAdapter;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.pipeline.PipelineStageFailure;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterEndpointGate;
import java.util.Map;
import java.util.Optional;
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

  /** Override key + report label for this gate. */
  private static final String SCENARIO_ID = "systemd-adapter";

  /**
   * Intrinsic severity: master can provision without the dbus adapter (degraded), so a failure is a
   * WARNING unless the operator overrides it (e.g. strict debugging).
   */
  private static final Severity INTRINSIC_SEVERITY = Severity.WARNING;

  private final BootstrapConfig config;
  private final ControlplanePolicy policy;
  private final boolean pulumiMode;
  private final Consumer<String> readinessLogger;
  private final ReportModel runbook;
  private final Consumer<Map<String, Object>> sink;

  public SystemdAdapterStage(
      BootstrapConfig config,
      ControlplanePolicy policy,
      boolean pulumiMode,
      Consumer<String> readinessLogger,
      ReportModel runbook,
      Consumer<Map<String, Object>> sink) {
    this.config = config;
    this.policy = policy;
    this.pulumiMode = pulumiMode;
    this.readinessLogger = readinessLogger;
    this.runbook = runbook;
    this.sink = sink;
  }

  public SystemdAdapterStage launch() {
    final boolean preview = pulumiMode && Deployment.getInstance().isDryRun();

    // A fake incident is PREVIEW-ONLY: the simulate map is consulted only during `pulumi preview`,
    // never during a real `pulumi up`. Gating purely on dry-run is the safety contract — a stale
    // simulate entry can never inject a fake failure into real provisioning (which, at CRITICAL
    // severity, would abort an apply over a defect that does not exist). In preview, the simulated
    // scenario lifts dry-run and runs a canned failing probe (emitting the typed symptom) so the
    // incident renders without touching live infrastructure.
    final Optional<Symptom> simulated =
        preview ? policy.preview().simulate(SCENARIO_ID) : Optional.empty();

    // Normal preview skips step bodies (deferred-preview); a simulated preview runs them against
    // the fake probe so the failure is visible.
    final boolean dryRun = preview && simulated.isEmpty();
    final SystemdAdapterProbe probe =
        simulated
            .map(SimulatedSystemdAdapterProbe::of)
            .orElse(cfg -> SeedSystemdAdapterEndpointGate.ensureReachable(cfg, readinessLogger));
    if (simulated.isPresent()) {
      log("⚙ " + SCENARIO_ID + " simulating incident: " + simulated.get().id());
    }

    // Standalone (non-JUnit) scenarios have no report model wired; finished() NPEs without one.
    // Record into the caller-owned runbook when present so this scenario joins the shared DAG;
    // otherwise a local model (inline log only). Held outside the try so the prose is logged even
    // when the probe fails the scenario.
    final ReportModel reportModel = runbook != null ? runbook : new ReportModel();

    final String previousDryRun = System.getProperty(JGIVEN_DRY_RUN);
    if (dryRun) {
      System.setProperty(JGIVEN_DRY_RUN, "true");
    }

    Map<String, Object> captured = null;
    Throwable failure = null;
    try {
      final Scenario<GivenSystemdAdapter, WhenSystemdAdapter, ThenSystemdAdapter> scenario =
          Scenario.create(
              GivenSystemdAdapter.class, WhenSystemdAdapter.class, ThenSystemdAdapter.class);
      scenario.setModel(reportModel);
      scenario.startScenario("systemd adapter becomes reachable");
      try {
        scenario.given().the_seed_node(config.systemdAdapterDbusHost(), config).probed_by(probe);
        scenario.when().the_systemd_adapter_probe_runs();
        scenario.then().the_dbus_endpoint_responds();
      } finally {
        // finished() flushes the scenario (steps + status) into the model so the node RENDERS —
        // pass or fail. It must run even when a step threw (it re-throws that failure, caught
        // below); skipping it on failure leaves the failed node empty in the runbook.
        scenario.finished();
      }
      captured = scenario.then().capturedSnapshot();
    } catch (Throwable cause) {
      failure = cause;
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

    if (failure == null) {
      // Success — or dry-run, where step bodies are skipped so no snapshot is produced.
      sink.accept(
          captured != null ? captured : SeedSystemdAdapterEndpointGate.deferredPreview(config));
      return this;
    }

    // Failure: the operator override wins over the scenario's intrinsic severity.
    final Severity effective = policy.readiness().override(SCENARIO_ID).orElse(INTRINSIC_SEVERITY);
    if (effective == Severity.CRITICAL) {
      log("✗ " + SCENARIO_ID + " FAILED, severity=CRITICAL → stopping provisioning");
      throw new PipelineStageFailure("systemd adapter", failure);
    }
    log("⚠ " + SCENARIO_ID + " FAILED, severity=WARNING → continuing in DEGRADED mode");
    sink.accept(degradedEnvelope(failure));
    return this;
  }

  private Map<String, Object> degradedEnvelope(Throwable failure) {
    return Map.of(
        "status", "degraded",
        "summary",
            "dbusEndpoint="
                + config.systemdAdapterDbusHost()
                + ":"
                + config.systemdAdapterDbusPort()
                + " status=degraded ("
                + failure.getMessage()
                + ")",
        "source", "systemd-adapter-endpoint-gate",
        "probeMode", "systemd-adapter-runtime");
  }

  private void log(String message) {
    if (readinessLogger != null) {
      readinessLogger.accept(message);
    }
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
