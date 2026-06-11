package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import com.pulumi.deployment.Deployment;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.bdd.Checkpoint;
import io.nxmatic.rke2lab.controlplane.bdd.ConsultationLog;
import io.nxmatic.rke2lab.controlplane.bdd.ConsultationNarration;
import io.nxmatic.rke2lab.controlplane.bdd.ConsultationReport;
import io.nxmatic.rke2lab.controlplane.bdd.Generalist;
import io.nxmatic.rke2lab.controlplane.bdd.MedicalRecord;
import io.nxmatic.rke2lab.controlplane.bdd.Observation;
import io.nxmatic.rke2lab.controlplane.bdd.RemediationPlan;
import io.nxmatic.rke2lab.controlplane.bdd.Severity;
import io.nxmatic.rke2lab.controlplane.bdd.SimulatedSystemdAdapterProbe;
import io.nxmatic.rke2lab.controlplane.bdd.Symptom;
import io.nxmatic.rke2lab.controlplane.bdd.SystemdAdapterProbe;
import io.nxmatic.rke2lab.controlplane.bdd.SystemdAdapterScenario;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.pipeline.PipelineStageFailure;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterEndpointGate;
import java.util.List;
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
  private static final String SCENARIO_ID = Checkpoint.SYSTEMD_ADAPTER.slug();

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
  private final ConsultationLog consultations;
  private final Generalist generalist;
  private final SystemdAdapterProbe liveProbe;
  private final Consumer<Map<String, Object>> sink;

  public SystemdAdapterStage(
      BootstrapConfig config,
      ControlplanePolicy policy,
      boolean pulumiMode,
      Consumer<String> readinessLogger,
      ReportModel runbook,
      ConsultationLog consultations,
      Generalist generalist,
      SystemdAdapterProbe liveProbe,
      Consumer<Map<String, Object>> sink) {
    this.config = config;
    this.policy = policy;
    this.pulumiMode = pulumiMode;
    this.readinessLogger = readinessLogger;
    this.runbook = runbook;
    this.consultations = consultations;
    this.generalist = generalist;
    this.liveProbe = liveProbe;
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

    // Capture the observation as the probe produces it, so it is available at the catch site for
    // the
    // doctor even when the Then assertion throws (the failed observation carries the typed
    // symptom).
    final Observation[] observationHolder = new Observation[1];
    // The injected live probe is the default; a preview-only simulated incident overrides it (and
    // only then). Production injects the real endpoint gate; tests inject a fake and play the same
    // launch(), so the scenario script lives in one place.
    final SystemdAdapterProbe underlying =
        simulated.<SystemdAdapterProbe>map(SimulatedSystemdAdapterProbe::of).orElse(liveProbe);
    final SystemdAdapterProbe probe =
        cfg -> {
          final Observation produced = underlying.probe(cfg);
          observationHolder[0] = produced;
          return produced;
        };
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

    Observation captured = null;
    Throwable failure = null;
    try {
      final Scenario<
              SystemdAdapterScenario.Given,
              SystemdAdapterScenario.When,
              SystemdAdapterScenario.Then>
          scenario =
              Scenario.create(
                  SystemdAdapterScenario.Given.class,
                  SystemdAdapterScenario.When.class,
                  SystemdAdapterScenario.Then.class);
      scenario.setModel(reportModel);
      scenario.startScenario(Checkpoint.SYSTEMD_ADAPTER.scenarioTitle());
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
      captured = scenario.then().capturedObservation();
    } catch (Throwable cause) {
      failure = cause;
      captured =
          observationHolder[0]; // the failed observation (with its symptom), if the probe ran
    } finally {
      if (previousDryRun == null) {
        System.clearProperty(JGIVEN_DRY_RUN);
      } else {
        System.setProperty(JGIVEN_DRY_RUN, previousDryRun);
      }
    }

    if (failure == null) {
      // Success — or dry-run, where step bodies are skipped so no observation is produced.
      final Observation observation =
          captured != null ? captured : SeedSystemdAdapterEndpointGate.deferredPreview(config);
      sink.accept(observation.toOutputMap());
      return this;
    }

    // Failure: the patient consults. The doctor diagnoses the captured observation's symptom into a
    // remediation plan, which is logged and (Increment C) flows into the runbook node.
    consultDoctor(captured);

    // The operator override wins over the scenario's intrinsic severity.
    final Severity effective = policy.readiness().override(SCENARIO_ID).orElse(INTRINSIC_SEVERITY);
    if (effective == Severity.CRITICAL) {
      log("✗ " + SCENARIO_ID + " FAILED, severity=CRITICAL → stopping provisioning");
      throw new PipelineStageFailure("systemd adapter", failure);
    }
    log("⚠ " + SCENARIO_ID + " FAILED, severity=WARNING → continuing in DEGRADED mode");
    sink.accept(degradedObservation(failure).toOutputMap());
    return this;
  }

  /**
   * The patient consults the doctor on failure: route the captured observation's symptom to the
   * Generalist, log the prescriptions, and keep the plan on a {@link ConsultationReport} in the
   * shared log (no longer dropped). A symptomless or absent observation (e.g. failure before the
   * probe ran) has nothing to route, so the consultation is skipped.
   */
  private void consultDoctor(Observation observation) {
    if (observation == null || observation.symptom().isEmpty()) {
      return;
    }
    final Symptom symptom = observation.symptom().get();
    final MedicalRecord record = generalist.recordForCurrentPatient();
    log("⚕ " + ConsultationNarration.consultedLine(record, symptom));
    log("⚕ " + generalist.cohortFinding(symptom));
    final RemediationPlan plan = generalist.consult(symptom, observation);
    if (consultations != null) {
      consultations.record(new ConsultationReport(SCENARIO_ID, List.of(observation), plan));
    }
  }

  private Observation degradedObservation(Throwable failure) {
    return Observation.of(
        "degraded",
        Optional.empty(),
        "dbusEndpoint="
            + config.systemdAdapterDbusHost()
            + ":"
            + config.systemdAdapterDbusPort()
            + " status=degraded ("
            + failure.getMessage()
            + ")",
        Map.of("source", "systemd-adapter-endpoint-gate", "probeMode", "systemd-adapter-runtime"));
  }

  private void log(String message) {
    if (readinessLogger != null) {
      readinessLogger.accept(message);
    }
  }
}
