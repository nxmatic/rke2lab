package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import com.pulumi.deployment.Deployment;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.text.PlainTextReporter;
import io.nxmatic.rke2lab.controlplane.bdd.ClusterReadinessPhase;
import io.nxmatic.rke2lab.controlplane.bdd.ClusterReadinessProbe;
import io.nxmatic.rke2lab.controlplane.bdd.Dossier;
import io.nxmatic.rke2lab.controlplane.bdd.Generalist;
import io.nxmatic.rke2lab.controlplane.bdd.GivenClusterReadiness;
import io.nxmatic.rke2lab.controlplane.bdd.Prescription;
import io.nxmatic.rke2lab.controlplane.bdd.ProductionClusterReadinessProbe;
import io.nxmatic.rke2lab.controlplane.bdd.RemediationPlan;
import io.nxmatic.rke2lab.controlplane.bdd.SystemdAdapterProbe;
import io.nxmatic.rke2lab.controlplane.bdd.ThenClusterReadiness;
import io.nxmatic.rke2lab.controlplane.bdd.WhenClusterReadiness;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier.VerificationResult;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Cluster-readiness checkpoint, played as the BDD scenario it documents — the second checkpoint,
 * unified onto the systemd-adapter shape (Increment D). It runs eager in the pipeline thread (the
 * dependencies are already concrete values by the time it runs), records into the shared runbook,
 * replays the systemd-adapter dependency nested (the follow-the-chain DAG edge), consults the
 * doctor on failure, and projects the per-phase dossiers into the {@link VerificationResult} the
 * output layer already consumes. {@code ClusterReadinessResource} is a thin mirror of the result.
 */
public final class ClusterReadinessStage {

  private static final String JGIVEN_DRY_RUN = "jgiven.report.dry-run";
  private static final String SCENARIO_ID = "cluster-readiness";

  private final BootstrapConfig config;
  private final ControlplanePolicy policy;
  private final boolean readinessEnabled;
  private final boolean pulumiMode;
  private final Consumer<String> readinessLogger;
  private final ReportModel runbook;
  private final Generalist generalist;
  private final Map<String, Object> systemdAdapterLaunchSummary;
  private final Consumer<VerificationResult> sink;

  public ClusterReadinessStage(
      BootstrapConfig config,
      ControlplanePolicy policy,
      boolean readinessEnabled,
      boolean pulumiMode,
      Consumer<String> readinessLogger,
      ReportModel runbook,
      Generalist generalist,
      Map<String, Object> systemdAdapterLaunchSummary,
      Consumer<VerificationResult> sink) {
    this.config = config;
    this.policy = policy;
    this.readinessEnabled = readinessEnabled;
    this.pulumiMode = pulumiMode;
    this.readinessLogger = readinessLogger;
    this.runbook = runbook;
    this.generalist = generalist;
    this.systemdAdapterLaunchSummary =
        systemdAdapterLaunchSummary == null ? Map.of() : systemdAdapterLaunchSummary;
    this.sink = sink;
  }

  public ClusterReadinessStage launch() {
    final boolean preview = pulumiMode && Deployment.getInstance().isDryRun();

    if (!readinessEnabled) {
      log("cluster readiness disabled by configuration");
      sink.accept(ClusterBootstrapReadinessVerifier.skipped(policy, readinessLogger));
      return this;
    }

    // Capture each phase's dossier as the probe produces it, so the VerificationResult projection
    // and the doctor consultation read data the scenario already computed (never JGiven stage state
    // read back through a getter — JGiven intercepts those as steps).
    final Map<ClusterReadinessPhase, Dossier> phaseDossiers =
        new EnumMap<>(ClusterReadinessPhase.class);
    final ClusterReadinessProbe live = new ProductionClusterReadinessProbe(policy, readinessLogger);
    final ClusterReadinessProbe phaseProbe =
        (cfg, phase) -> {
          final Dossier produced = live.probe(cfg, phase);
          phaseDossiers.put(phase, produced);
          return produced;
        };

    // The nested systemd-adapter dependency replays from the already-captured launch summary — no
    // second live probe. Its status reflects what the systemd-adapter checkpoint already found.
    final SystemdAdapterProbe nestedSystemdAdapterProbe = cfg -> capturedSystemdAdapterDossier();

    final ReportModel reportModel = runbook != null ? runbook : new ReportModel();

    // Preview: set JGiven dry-run so the step bodies are skipped (no live infra touched), but still
    // PLAY the scenario so its shell renders in the runbook — the same "walk structure, emit doc,
    // no side effects" notion as the systemd-adapter checkpoint. The result stays deferred.
    final String previousDryRun = System.getProperty(JGIVEN_DRY_RUN);
    if (preview) {
      System.setProperty(JGIVEN_DRY_RUN, "true");
    }

    Throwable failure = null;
    try {
      final Scenario<GivenClusterReadiness, WhenClusterReadiness, ThenClusterReadiness> scenario =
          Scenario.create(
              GivenClusterReadiness.class, WhenClusterReadiness.class, ThenClusterReadiness.class);
      scenario.setModel(reportModel);
      scenario.startScenario("cluster becomes ready");
      try {
        scenario
            .given()
            .the_cluster(config.clusterName(), config)
            .with_phase_probe(phaseProbe)
            .depending_on_systemd_adapter(nestedSystemdAdapterProbe);
        scenario
            .when()
            .the_systemd_adapter_dependency_is_satisfied()
            .and()
            .the_kubeconfig_is_published()
            .and()
            .the_api_is_ready()
            .and()
            .the_required_controllers_are_effective();
        scenario.then().the_cluster_is_ready();
      } finally {
        scenario.finished();
      }
    } catch (Throwable cause) {
      failure = cause;
    } finally {
      logReport(reportModel);
      if (previousDryRun == null) {
        System.clearProperty(JGIVEN_DRY_RUN);
      } else {
        System.setProperty(JGIVEN_DRY_RUN, previousDryRun);
      }
    }

    if (preview) {
      // Shell rendered; live checks are deferred to apply (no real verification ran).
      log("cluster readiness deferred during preview; live checks run during apply");
      sink.accept(ClusterBootstrapReadinessVerifier.deferredPreview(policy, readinessLogger));
      return this;
    }

    if (failure == null) {
      log("✓ " + SCENARIO_ID + " ready: kubeconfig published, API ready, controllers effective");
      sink.accept(ClusterBootstrapReadinessVerifier.ready(policy));
      return this;
    }

    // Failure: the patient consults, then project the per-phase dossiers into a failed result so
    // the output contract (handoffReady → nextStep, bootstrapStatus) is preserved.
    consultDoctor(phaseDossiers);
    sink.accept(failedProjection(phaseDossiers));
    return this;
  }

  /** Reconstruct the systemd-adapter dependency's dossier from its already-captured summary. */
  private Dossier capturedSystemdAdapterDossier() {
    final String status =
        String.valueOf(systemdAdapterLaunchSummary.getOrDefault("status", "unknown"));
    final String summary =
        String.valueOf(systemdAdapterLaunchSummary.getOrDefault("summary", "systemd adapter"));
    return Dossier.of(status, Optional.empty(), summary, systemdAdapterLaunchSummary);
  }

  /** Project the per-phase dossiers into the VerificationResult the output layer consumes. */
  private VerificationResult failedProjection(Map<ClusterReadinessPhase, Dossier> phaseDossiers) {
    final boolean kubeconfig = phaseOk(phaseDossiers, ClusterReadinessPhase.KUBECONFIG_PUBLISHED);
    final boolean api = phaseOk(phaseDossiers, ClusterReadinessPhase.API_READY);
    final boolean controllers = phaseOk(phaseDossiers, ClusterReadinessPhase.CONTROLLERS_EFFECTIVE);
    final String summary =
        phaseDossiers.values().stream()
            .filter(dossier -> !dossier.isOk())
            .map(Dossier::summary)
            .findFirst()
            .orElse("cluster readiness failed");
    return ClusterBootstrapReadinessVerifier.failed(kubeconfig, api, controllers, summary, policy);
  }

  private static boolean phaseOk(
      Map<ClusterReadinessPhase, Dossier> phaseDossiers, ClusterReadinessPhase phase) {
    final Dossier dossier = phaseDossiers.get(phase);
    return dossier != null && dossier.isOk();
  }

  /** The patient consults the doctor on the first failing phase's symptom. */
  private void consultDoctor(Map<ClusterReadinessPhase, Dossier> phaseDossiers) {
    phaseDossiers.values().stream()
        .filter(dossier -> dossier.symptom().isPresent())
        .findFirst()
        .ifPresent(
            dossier -> {
              final RemediationPlan plan = generalist.consult(dossier.symptom().get(), dossier);
              log("⚕ " + SCENARIO_ID + " diagnosis: " + plan.generalistSummary());
              for (Prescription prescription : plan.prescriptions()) {
                log("  ℞ " + prescription.programRef().id() + " — " + prescription.humanHint());
              }
            });
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
