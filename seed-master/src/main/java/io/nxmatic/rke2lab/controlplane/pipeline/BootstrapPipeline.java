package io.nxmatic.rke2lab.controlplane.pipeline;

import com.pulumi.deployment.Deployment;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rke2lab.controlplane.bdd.ClusterSpecialist;
import io.nxmatic.rke2lab.controlplane.bdd.ConsultationLog;
import io.nxmatic.rke2lab.controlplane.bdd.DbusTcpSpecialist;
import io.nxmatic.rke2lab.controlplane.bdd.DriftSpecialist;
import io.nxmatic.rke2lab.controlplane.bdd.Generalist;
import io.nxmatic.rke2lab.controlplane.bdd.HealthSystem;
import io.nxmatic.rke2lab.controlplane.bdd.InterventionLedger;
import io.nxmatic.rke2lab.controlplane.bdd.InterventionLedgerLayout;
import io.nxmatic.rke2lab.controlplane.bdd.InterventionLedgerSource;
import io.nxmatic.rke2lab.controlplane.bdd.LiveMedicalRecordRegistry;
import io.nxmatic.rke2lab.controlplane.bdd.MedicalRecord;
import io.nxmatic.rke2lab.controlplane.bdd.NetworkSpecialist;
import io.nxmatic.rke2lab.controlplane.bdd.Patient;
import io.nxmatic.rke2lab.controlplane.bdd.PulumiInterventionLedgerWriter;
import io.nxmatic.rke2lab.controlplane.bdd.SystemdAdapterProbe;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.pipeline.stages.BboxStage;
import io.nxmatic.rke2lab.controlplane.pipeline.stages.IncusStage;
import io.nxmatic.rke2lab.controlplane.pipeline.stages.PreflightStage;
import io.nxmatic.rke2lab.controlplane.pipeline.stages.ResourcesStage;
import io.nxmatic.rke2lab.controlplane.pipeline.stages.SystemdAdapterStage;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterEndpointGate;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Fluent bootstrap pipeline. See docs/fluent-pipeline-grammar.adoc for the grammar definition; this
 * class is the canonical exemplar.
 *
 * <pre>
 * BootstrapPipeline.forCluster(config, policy)
 *     .withOptions(options)
 *     .using(bboxOrchestrator, resourceManager, outputBuilder)
 *     .onFailure(SeedLog::error)
 *     .runningInPulumi(logger)
 *     .during("preflight", preflight -&gt; preflight
 *         .enforceEntryGates()
 *         .requireLocalCommands("ssh", "kubectl")
 *         .requireRemoteCommand("incus"))
 *     .then()
 *     .during("bbox reconciliation", bbox -&gt; bbox.reconcileReservations())
 *     .then()
 *     .during("incus provisioning", incus -&gt; incus.provisionInstance())
 *     .then()
 *     .during("systemd adapter", adapter -&gt; adapter.launch())
 *     .then()
 *     .during("bootstrap resources", resources -&gt; resources.createAll())
 *     .collectOutputs();
 * </pre>
 */
public final class BootstrapPipeline {

  private BootstrapPipeline() {}

  public static ConfiguringPipeline forCluster(BootstrapConfig config, ControlplanePolicy policy) {
    return new ConfiguringPipeline(new PipelineState(config, policy));
  }

  /**
   * The symptom-independent follow-up: after the record is reconstructed for the run's patient,
   * load the intervention ledger and let the generalist review every resolved problem. The drift
   * specialist persists any inferred external change through its own writer. A no-op when no
   * file:// backend is configured (nothing to load or persist).
   */
  static void reviewDriftAtReconstruction(HealthSystem healthSystem, Path backendDir) {
    if (backendDir == null) {
      return;
    }
    final Generalist generalist = healthSystem.generalist();
    final MedicalRecord record = generalist.recordForCurrentPatient();
    final InterventionLedger ledger =
        new InterventionLedgerSource(backendDir, InterventionLedgerLayout.ledger()).load();
    generalist.reviewOpenProblems(record, ledger);
  }

  public static final class ConfiguringPipeline {
    private final PipelineState state;

    private ConfiguringPipeline(PipelineState state) {
      this.state = state;
    }

    public ConfiguredPipeline withOptions(BootstrapOptions options) {
      state.options = options;
      return new ConfiguredPipeline(state);
    }
  }

  public static final class ConfiguredPipeline {
    private final PipelineState state;

    private ConfiguredPipeline(PipelineState state) {
      this.state = state;
    }

    public ComponentBoundPipeline using(
        BboxReconciliationOrchestrator bboxOrchestrator,
        ResourceManager resourceManager,
        OutputBuilder outputBuilder) {
      state.bboxOrchestrator = bboxOrchestrator;
      state.resourceManager = resourceManager;
      state.outputBuilder = outputBuilder;
      return new ComponentBoundPipeline(state);
    }
  }

  public static final class ComponentBoundPipeline {
    private final PipelineState state;

    private ComponentBoundPipeline(PipelineState state) {
      this.state = state;
    }

    /** Optional: register a per-topic failure handler. Defaults to no-op when not called. */
    public ComponentBoundPipeline onFailure(OnFailure handler) {
      state.onFailure = handler;
      return this;
    }

    /**
     * Optional: record every checkpoint's scenario into one caller-owned runbook model, and every
     * doctor consultation into one caller-owned {@link ConsultationLog}. The caller renders the
     * runbook (in a {@code finally}, so a CRITICAL stop still produces one); the consultation log
     * is the in-memory accumulation the medical record (layer 3) will read. When not called, each
     * checkpoint uses discarded local instances — inline log only.
     */
    public ComponentBoundPipeline recordingInto(
        ReportModel runbook, ConsultationLog consultations) {
      state.runbook = runbook;
      state.consultations = consultations;
      return this;
    }

    public AwaitingPreflight runningStandalone(Consumer<String> readinessLogger) {
      state.readinessLogger = readinessLogger;
      state.pulumiMode = false;
      bindMedicalRecord(state);
      return new AwaitingPreflight(state);
    }

    public AwaitingPreflight runningInPulumi(Consumer<String> readinessLogger) {
      state.readinessLogger = readinessLogger;
      state.pulumiMode = true;
      bindMedicalRecord(state);
      return new AwaitingPreflight(state);
    }

    /**
     * Built once at the readiness transition (logger + mode settled): the patient under care is
     * this Pulumi stack's org/project/stack when running under the engine, a placeholder otherwise
     * (the registry degrades to an empty record either way when no file:// backend is configured).
     * Admits the patient into the HealthSystem keystone the stages consult.
     */
    private static void bindMedicalRecord(PipelineState state) {
      final Consumer<String> logger =
          state.readinessLogger != null ? state.readinessLogger : msg -> {};
      final LiveMedicalRecordRegistry registry = LiveMedicalRecordRegistry.fromEnvironment(logger);
      final Patient patient = currentPatient(state.pulumiMode);
      final Path backendDir = registry.backendDir();
      // No file:// backend → nothing to persist against; the drift specialist runs with a no-op
      // writer (inference computed, never stored), mirroring the registry's no-backend degrade.
      final DriftSpecialist driftSpecialist =
          backendDir != null
              ? new DriftSpecialist(new PulumiInterventionLedgerWriter(backendDir))
              : new DriftSpecialist(intervention -> {});
      state.healthSystem =
          HealthSystem.admit(
              patient,
              registry,
              List.of(
                  new DbusTcpSpecialist(state.config),
                  new NetworkSpecialist(),
                  new ClusterSpecialist()),
              driftSpecialist,
              logger);
      reviewDriftAtReconstruction(state.healthSystem, backendDir);
    }

    private static Patient currentPatient(boolean pulumiMode) {
      final Patient placeholder = new Patient("organization", "rke2lab", "standalone");
      if (!pulumiMode) {
        return placeholder;
      }
      try {
        final Deployment deployment = Deployment.getInstance();
        return new Patient(
            deployment.getOrganizationName(),
            deployment.getProjectName(),
            deployment.getStackName());
      } catch (RuntimeException noEngine) {
        return placeholder;
      }
    }
  }

  public static final class AwaitingPreflight {
    private final PipelineState state;

    private AwaitingPreflight(PipelineState state) {
      this.state = state;
    }

    public PreflightDone during(String topic, Function<PreflightStage, PreflightStage> body) {
      final PreflightStage stage =
          new PreflightStage(
              state.config.localWorktreePath(),
              state.config.imageBuilderHost(),
              state.options.cleanWorktreeRequired(),
              state.readinessLogger);
      TopicRunner.runDuring("pipeline", topic, stage, body, state.onFailure);
      return new PreflightDone(state);
    }
  }

  public static final class PreflightDone {
    private final PipelineState state;

    private PreflightDone(PipelineState state) {
      this.state = state;
    }

    public AwaitingBbox then() {
      return new AwaitingBbox(state);
    }
  }

  public static final class AwaitingBbox {
    private final PipelineState state;

    private AwaitingBbox(PipelineState state) {
      this.state = state;
    }

    public BboxDone during(String topic, Function<BboxStage, BboxStage> body) {
      final BboxStage stage =
          new BboxStage(
              state.bboxOrchestrator,
              state.config.localWorktreePath(),
              state.options.bboxFailOnError(),
              result -> state.bboxResult = result);
      TopicRunner.runDuring("pipeline", topic, stage, body, state.onFailure);
      return new BboxDone(state);
    }
  }

  public static final class BboxDone {
    private final PipelineState state;

    private BboxDone(PipelineState state) {
      this.state = state;
    }

    public AwaitingIncus then() {
      return new AwaitingIncus(state);
    }
  }

  public static final class AwaitingIncus {
    private final PipelineState state;

    private AwaitingIncus(PipelineState state) {
      this.state = state;
    }

    public IncusDone during(String topic, Function<IncusStage, IncusStage> body) {
      final IncusStage stage =
          new IncusStage(state.config, state.policy, result -> state.bootstrapResult = result);
      TopicRunner.runDuring("pipeline", topic, stage, body, state.onFailure);
      return new IncusDone(state);
    }
  }

  public static final class IncusDone {
    private final PipelineState state;

    private IncusDone(PipelineState state) {
      this.state = state;
    }

    public AwaitingSystemdAdapter then() {
      return new AwaitingSystemdAdapter(state);
    }
  }

  public static final class AwaitingSystemdAdapter {
    private final PipelineState state;

    private AwaitingSystemdAdapter(PipelineState state) {
      this.state = state;
    }

    public SystemdAdapterDone during(
        String topic, Function<SystemdAdapterStage, SystemdAdapterStage> body) {
      final Generalist generalist = state.healthSystem.generalist();
      final SeedSystemdAdapterEndpointGate gate = SeedSystemdAdapterEndpointGate.production();
      final SystemdAdapterProbe liveProbe = cfg -> gate.ensureReachable(cfg, state.readinessLogger);
      final SystemdAdapterStage stage =
          new SystemdAdapterStage(
              state.config,
              state.policy,
              state.pulumiMode,
              state.readinessLogger,
              state.runbook,
              state.consultations,
              generalist,
              liveProbe,
              summary -> state.systemdAdapterLaunchSummary = summary);
      TopicRunner.runDuring("pipeline", topic, stage, body, state.onFailure);
      return new SystemdAdapterDone(state);
    }
  }

  public static final class SystemdAdapterDone {
    private final PipelineState state;

    private SystemdAdapterDone(PipelineState state) {
      this.state = state;
    }

    public AwaitingResources then() {
      return new AwaitingResources(state);
    }
  }

  public static final class AwaitingResources {
    private final PipelineState state;

    private AwaitingResources(PipelineState state) {
      this.state = state;
    }

    public ResourcesDone during(String topic, Function<ResourcesStage, ResourcesStage> body) {
      final Generalist generalist = state.healthSystem.generalist();
      final ResourcesStage stage =
          new ResourcesStage(
              state.resourceManager,
              state.config,
              state.policy,
              state.options.readinessEnabled(),
              state.pulumiMode,
              state.readinessLogger,
              state.runbook,
              state.consultations,
              generalist,
              () -> state.bootstrapResult,
              () -> state.systemdAdapterLaunchSummary,
              result -> state.resourceResult = result);
      TopicRunner.runDuring("pipeline", topic, stage, body, state.onFailure);
      return new ResourcesDone(state);
    }
  }

  public static final class ResourcesDone {
    private final PipelineState state;

    private ResourcesDone(PipelineState state) {
      this.state = state;
    }

    public Map<String, Object> collectOutputs() {
      return state.outputBuilder.buildOutputs(
          state.config,
          state.policy,
          state.bootstrapResult,
          state.bboxResult,
          state.systemdAdapterLaunchSummary,
          state.resourceResult);
    }
  }
}
