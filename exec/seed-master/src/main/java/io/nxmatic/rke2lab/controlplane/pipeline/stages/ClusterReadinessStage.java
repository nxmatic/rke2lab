package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import com.pulumi.deployment.Deployment;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessPhase;
import io.nxmatic.rke2lab.controlplane.bdd.ClusterReadinessProbe;
import io.nxmatic.rke2lab.controlplane.bdd.ClusterReadinessScenario;
import io.nxmatic.rke2lab.controlplane.bdd.ObservationView;
import io.nxmatic.rke2lab.controlplane.bdd.SystemdAdapterProbe;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier.VerificationResult;
import io.nxmatic.rke2lab.doctor.port.ConsultationLog;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Checkpoint;
import io.nxmatic.rke2lab.world.gateway.port.Consultation;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.ObservationWire;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessCheckpoint;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.StreamSupport;

/**
 * Cluster-readiness checkpoint, played as the BDD scenario it documents — the second checkpoint,
 * unified onto the systemd-adapter shape (Increment D). It runs eager in the pipeline thread (the
 * dependencies are already concrete values by the time it runs), records into the shared runbook,
 * replays the systemd-adapter dependency nested (the follow-the-chain DAG edge), consults the
 * doctor on failure, and projects the per-phase observations into the {@link VerificationResult}
 * the output layer already consumes. {@code ClusterReadinessResource} is a thin mirror of the
 * result.
 */
public final class ClusterReadinessStage {

  private static final String JGIVEN_DRY_RUN = "jgiven.report.dry-run";
  private static final String SCENARIO_ID = Checkpoint.CLUSTER_READINESS.slug();

  private final BootstrapConfig config;
  private final ControlplanePolicy policy;
  private final boolean readinessEnabled;
  private final boolean pulumiMode;
  private final Consumer<String> readinessLogger;
  private final ReportModel runbook;
  private final ConsultationLog consultations;
  private final ConsultingService doctor;
  private final ClusterReadinessProbe phaseProbe;
  private final Map<String, Object> systemdAdapterLaunchSummary;
  private final Consumer<VerificationResult> sink;

  /**
   * The run's stable instant, written into the consult checkpoint so OSGi stamps its expectations.
   */
  private final Instant recordedAt;

  private final DocumentCodec codec = new DocumentCodec();

  public ClusterReadinessStage(
      BootstrapConfig config,
      ControlplanePolicy policy,
      boolean readinessEnabled,
      boolean pulumiMode,
      Consumer<String> readinessLogger,
      ReportModel runbook,
      ConsultationLog consultations,
      ConsultingService doctor,
      ClusterReadinessProbe phaseProbe,
      Map<String, Object> systemdAdapterLaunchSummary,
      Consumer<VerificationResult> sink,
      Instant recordedAt) {
    this.config = config;
    this.policy = policy;
    this.readinessEnabled = readinessEnabled;
    this.pulumiMode = pulumiMode;
    this.readinessLogger = readinessLogger;
    this.runbook = runbook;
    this.consultations = consultations;
    this.doctor = doctor;
    this.phaseProbe = phaseProbe;
    this.recordedAt = recordedAt;
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

    // Capture each phase's observation as the injected probe produces it, so the VerificationResult
    // projection and the doctor consultation read data the scenario already computed (never JGiven
    // stage state read back through a getter — JGiven intercepts those as steps). Live
    // injects
    // a LiveClusterReadinessProbe; tests inject a simulated/fake probe and play the same
    // launch(), so the scenario script lives in exactly one place.
    final Map<ClusterReadinessPhase, ObservationView> phaseObservations =
        new EnumMap<>(ClusterReadinessPhase.class);
    final ClusterReadinessProbe capturingProbe =
        (cfg, phase) -> {
          final ObservationView produced = phaseProbe.probe(cfg, phase);
          phaseObservations.put(phase, produced);
          return produced;
        };

    // The nested systemd-adapter dependency replays from the already-captured launch summary — no
    // second live probe. Its status reflects what the systemd-adapter checkpoint already found.
    final SystemdAdapterProbe nestedSystemdAdapterProbe =
        cfg -> capturedSystemdAdapterObservation();

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
      final Scenario<
              ClusterReadinessScenario.Given,
              ClusterReadinessScenario.When,
              ClusterReadinessScenario.Then>
          scenario =
              Scenario.create(
                  ClusterReadinessScenario.Given.class,
                  ClusterReadinessScenario.When.class,
                  ClusterReadinessScenario.Then.class);
      scenario.setModel(reportModel);
      scenario.startScenario(Checkpoint.CLUSTER_READINESS.scenarioTitle());
      try {
        scenario
            .given()
            .the_cluster(config.clusterName(), config)
            .with_phase_probe(capturingProbe)
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

    // Failure: the patient consults, then project the per-phase observations into a failed result
    // so
    // the output contract (handoffReady → nextStep, bootstrapStatus) is preserved.
    consultDoctor(phaseObservations);
    sink.accept(failedProjection(phaseObservations));
    return this;
  }

  /** Reconstruct the systemd-adapter dependency's observation from its already-captured summary. */
  private ObservationView capturedSystemdAdapterObservation() {
    final String status =
        String.valueOf(systemdAdapterLaunchSummary.getOrDefault("status", "unknown"));
    final String summary =
        String.valueOf(systemdAdapterLaunchSummary.getOrDefault("summary", "systemd adapter"));
    return ObservationView.of(status, Optional.empty(), summary, systemdAdapterLaunchSummary);
  }

  /** Project the per-phase observations into the VerificationResult the output layer consumes. */
  private VerificationResult failedProjection(
      Map<ClusterReadinessPhase, ObservationView> phaseObservations) {
    final boolean kubeconfig =
        phaseOk(phaseObservations, ClusterReadinessPhase.KUBECONFIG_PUBLISHED);
    final boolean api = phaseOk(phaseObservations, ClusterReadinessPhase.API_READY);
    final boolean controllers =
        phaseOk(phaseObservations, ClusterReadinessPhase.CONTROLLERS_EFFECTIVE);
    final String summary =
        phaseObservations.values().stream()
            .filter(observation -> !observation.isOk())
            .map(ObservationView::summary)
            .findFirst()
            .orElse("cluster readiness failed");
    return ClusterBootstrapReadinessVerifier.failed(kubeconfig, api, controllers, summary, policy);
  }

  private static boolean phaseOk(
      Map<ClusterReadinessPhase, ObservationView> phaseObservations, ClusterReadinessPhase phase) {
    final ObservationView observation = phaseObservations.get(phase);
    return observation != null && observation.isOk();
  }

  /**
   * The patient consults the doctor when a phase failed: the host crosses ALL phase observations as
   * a checkpoint {@link Document} (the doctor routes on the first symptom-bearing one but keeps
   * every observation in the record — no information lost), the doctor reasons OSGi-side, and the
   * host logs the returned narration and keeps the consultation Document in the shared log. Skipped
   * when no phase raised a symptom, or when there is no doctor.
   */
  private void consultDoctor(Map<ClusterReadinessPhase, ObservationView> phaseObservations) {
    if (doctor == null
        || phaseObservations.values().stream().noneMatch(o -> o.symptom().isPresent())) {
      return;
    }
    final Document consultation = doctor.consult(consultCheckpoint(phaseObservations.values()));
    log("⚕ " + codec.decode(consultation, Consultation.class).narration());
    if (consultations != null) {
      consultations.record(consultation);
    }
  }

  /**
   * The consult checkpoint Document: every phase observation ({@link ObservationView#toWire()})
   * plus the run's stable {@code recordedAt}, unioned into one {@link ReadinessCheckpoint} the
   * codec encodes, so OSGi reconstructs them all and routes on the first symptom-bearing one.
   */
  private Document consultCheckpoint(Iterable<ObservationView> observations) {
    final List<ObservationWire> wires =
        StreamSupport.stream(observations.spliterator(), false)
            .map(ObservationView::toWire)
            .toList();
    final ReadinessCheckpoint checkpoint =
        new ReadinessCheckpoint(
            SCENARIO_ID, Optional.empty(), Optional.empty(), Optional.of(recordedAt), wires);
    return new Document(
        Domain.DOCTOR.slug(), Coordinate.READINESS_CHECKPOINT.slug(), codec.encode(checkpoint));
  }

  private void log(String message) {
    if (readinessLogger != null) {
      readinessLogger.accept(message);
    }
  }
}
