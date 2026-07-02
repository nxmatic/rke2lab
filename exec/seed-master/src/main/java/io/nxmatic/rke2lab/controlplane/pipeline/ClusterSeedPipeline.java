package io.nxmatic.rke2lab.controlplane.pipeline;

import com.pulumi.deployment.Deployment;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessContact;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rke2lab.controlplane.bdd.SystemdAdapterProbe;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.pipeline.PipelineState.PipelineInputs;
import io.nxmatic.rke2lab.controlplane.pipeline.stages.BboxTopic;
import io.nxmatic.rke2lab.controlplane.pipeline.stages.IncusTopic;
import io.nxmatic.rke2lab.controlplane.pipeline.stages.PreflightTopic;
import io.nxmatic.rke2lab.controlplane.pipeline.stages.ResourcesTopic;
import io.nxmatic.rke2lab.controlplane.pipeline.stages.SystemdAdapterTopic;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterEndpointGate;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterRuntimeStatusSnapshot;
import io.nxmatic.rke2lab.doctor.port.ConsultationLog;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.doctor.port.HealthSystem;
import io.nxmatic.rke2lab.doctor.port.InterventionJournal;
import io.nxmatic.rke2lab.doctor.port.InterventionLedgerWriter;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordJournal;
import io.nxmatic.rke2lab.osgi.runtime.BootedFramework;
import io.nxmatic.rke2lab.pipeline.OnFailure;
import io.nxmatic.rke2lab.pulumi.edge.PulumiInterventionLedgerWriter;
import io.nxmatic.rke2lab.pulumi.edge.StackInterventionJournal;
import io.nxmatic.rke2lab.pulumi.edge.StackMedicalRecordJournal;
import io.nxmatic.rke2lab.systemd.port.SystemdRuntimeProbe;
import io.nxmatic.rke2lab.world.gateway.port.Patient;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessAuthority;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Fluent bootstrap pipeline. See docs/fluent-pipeline-grammar.adoc for the grammar definition; this
 * class is the canonical exemplar.
 *
 * <pre>
 * ClusterSeedPipeline.forCluster(config, policy)
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
public final class ClusterSeedPipeline {

  private ClusterSeedPipeline() {}

  public static ConfiguringPipeline forCluster(BootstrapConfig config, ControlplanePolicy policy) {
    return new ConfiguringPipeline(PipelineInputs.forCluster(config, policy));
  }

  public static final class ConfiguringPipeline {
    private final PipelineInputs.Builder inputsBuilder;

    private ConfiguringPipeline(PipelineInputs.Builder inputsBuilder) {
      this.inputsBuilder = inputsBuilder;
    }

    public ConfiguredPipeline withOptions(BootstrapOptions options) {
      return new ConfiguredPipeline(inputsBuilder.options(options));
    }
  }

  public static final class ConfiguredPipeline {
    private final PipelineInputs.Builder inputsBuilder;

    private ConfiguredPipeline(PipelineInputs.Builder inputsBuilder) {
      this.inputsBuilder = inputsBuilder;
    }

    public ComponentBoundPipeline using(
        BboxReconciliationOrchestrator bboxOrchestrator,
        ResourceManager resourceManager,
        OutputBuilder outputBuilder) {
      return new ComponentBoundPipeline(
          inputsBuilder.using(bboxOrchestrator, resourceManager, outputBuilder));
    }
  }

  public static final class ComponentBoundPipeline {
    private final PipelineInputs.Builder inputsBuilder;

    private ComponentBoundPipeline(PipelineInputs.Builder inputsBuilder) {
      this.inputsBuilder = inputsBuilder;
    }

    /** Optional: register a per-topic failure handler. Defaults to no-op when not called. */
    public ComponentBoundPipeline onFailure(OnFailure handler) {
      inputsBuilder.onFailure(handler);
      return this;
    }

    /**
     * The embedded OSGi framework booted for this run; the stages read the manifests-world services
     * from its registry.
     */
    public ComponentBoundPipeline withBootedFramework(BootedFramework bootedFramework) {
      inputsBuilder.bootedFramework(bootedFramework);
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
      inputsBuilder.recordingInto(runbook, consultations);
      return this;
    }

    public AwaitingPreflight runningStandalone(Consumer<String> readinessLogger) {
      return running(readinessLogger, false);
    }

    public AwaitingPreflight runningInPulumi(Consumer<String> readinessLogger) {
      return running(readinessLogger, true);
    }

    private AwaitingPreflight running(Consumer<String> readinessLogger, boolean pulumiMode) {
      inputsBuilder.readinessLogger(readinessLogger).pulumiMode(pulumiMode);
      inputsBuilder
          .doctor(admitPatient(inputsBuilder, readinessLogger, pulumiMode))
          .systemdRuntimeStatus(resolveSystemdRuntimeStatus(inputsBuilder))
          .clusterReadinessContact(resolveClusterReadinessContact(inputsBuilder))
          .readinessAuthority(resolveReadinessAuthority(inputsBuilder));
      return new AwaitingPreflight(new PipelineState(inputsBuilder.build()));
    }

    /**
     * Built once at the readiness transition (logger + mode settled). The host owns the
     * institution's Layer-1 infrastructure (the two READ journals + the Pulumi ledger writer —
     * built from env/Pulumi knowledge), so it publishes all three into the embedded framework. The
     * two journals yield opaque {@code Document} blobs the host produces WITHOUT interpreting their
     * medical content; OSGi's internal {@code JournalMedicalRecordRegistry} folds them into the EHR
     * inside the bundle, which (with the ledger writer) satisfies the {@code HealthSystem}'s
     * references and SCR activates it (its diagnosing roster arrives by Declarative Services, never
     * crossing to the host). The host then crosses the seam — {@code
     * awaitService(HealthSystem).admit(patient)} — receiving the consulting contract, and triggers
     * the drift review ({@code reviewDrift()}), which rebuilds the record + ledger OSGi-side. No
     * {@code doctor.records} type ever crosses back; the stages consult the contract, never the
     * hidden actors.
     */
    private static ConsultingService admitPatient(
        PipelineInputs.Builder inputsBuilder, Consumer<String> logger, boolean pulumiMode) {
      final BootedFramework framework = inputsBuilder.bootedFramework();

      final StackMedicalRecordJournal medicalRecordJournal =
          StackMedicalRecordJournal.fromEnvironment(logger);
      final Optional<Path> backendDir = medicalRecordJournal.backendDir();
      final InterventionJournal interventionJournal = new StackInterventionJournal(backendDir);
      final InterventionLedgerWriter ledgerWriter =
          backendDir
              .<InterventionLedgerWriter>map(PulumiInterventionLedgerWriter::new)
              .orElse(intervention -> {});
      framework.context().registerService(MedicalRecordJournal.class, medicalRecordJournal, null);
      framework.context().registerService(InterventionJournal.class, interventionJournal, null);
      framework.context().registerService(InterventionLedgerWriter.class, ledgerWriter, null);

      final HealthSystem healthSystem = framework.awaitService(HealthSystem.class, 5000);
      if (healthSystem == null) {
        throw new IllegalStateException(
            "No HealthSystem published in the OSGi registry within 5s — DefaultHealthSystem did not"
                + " activate (a domain diagnostician @Component, the medical-record journal, the"
                + " intervention journal, or the ledger writer reference is unbound).");
      }
      final ConsultingService doctor = healthSystem.admit(currentPatient(pulumiMode));
      doctor.reviewDrift();
      return doctor;
    }

    /**
     * This run's patient: the Pulumi stack's org/project/stack under the engine, a placeholder
     * otherwise (standalone, or no engine bound).
     */
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

    /**
     * Resolve the systemd runtime-status probe once from the booted registry — the
     * dbus-systemd-edge {@code @Component} that implements {@code SystemdRuntimeProbe}. Wrapped as
     * the snapshot instance and threaded to the readiness sites, so none of them reaches the edge
     * statically.
     */
    private static SeedSystemdAdapterRuntimeStatusSnapshot resolveSystemdRuntimeStatus(
        PipelineInputs.Builder inputsBuilder) {
      final SystemdRuntimeProbe probe =
          inputsBuilder.bootedFramework().awaitService(SystemdRuntimeProbe.class, 5000);
      if (probe == null) {
        throw new IllegalStateException(
            "No SystemdRuntimeProbe published in the OSGi registry within 5s "
                + "(dbus-systemd-edge @Component absent).");
      }
      return new SeedSystemdAdapterRuntimeStatusSnapshot(probe);
    }

    /**
     * Resolve the cluster-readiness contact once from the booted registry — the cluster-edge
     * {@code @Component} that implements {@code ClusterReadinessContact} by shelling kubectl.
     * Threaded to the readiness probe, so the host wraps it in its retry loops without reaching the
     * edge statically.
     */
    private static ClusterReadinessContact resolveClusterReadinessContact(
        PipelineInputs.Builder inputsBuilder) {
      final ClusterReadinessContact contact =
          inputsBuilder.bootedFramework().awaitService(ClusterReadinessContact.class, 5000);
      if (contact == null) {
        throw new IllegalStateException(
            "No ClusterReadinessContact published in the OSGi registry within 5s "
                + "(cluster-edge @Component absent).");
      }
      return contact;
    }

    /**
     * Resolve the readiness authority once from the booted registry — the doctor-core
     * {@code @Component} that implements {@code ReadinessAuthority}. Threaded to the stages that
     * build checkpoint Documents and read verdict actions — so the host never reasons on Severity.
     */
    private static ReadinessAuthority resolveReadinessAuthority(
        PipelineInputs.Builder inputsBuilder) {
      final ReadinessAuthority authority =
          inputsBuilder.bootedFramework().awaitService(ReadinessAuthority.class, 5000);
      if (authority == null) {
        throw new IllegalStateException(
            "No ReadinessAuthority published in the OSGi registry within 5s "
                + "(doctor-core DefaultReadinessAuthority @Component absent).");
      }
      return authority;
    }
  }

  public static final class AwaitingPreflight {
    private final PipelineState state;

    private AwaitingPreflight(PipelineState state) {
      this.state = state;
    }

    public PreflightDone during(String topic, Function<PreflightTopic, PreflightTopic> body) {
      final PreflightTopic stage =
          new PreflightTopic(
              state.inputs.config().localWorktreePath(),
              state.inputs.config().imageBuilderHost(),
              state.inputs.options().cleanWorktreeRequired(),
              state.inputs.readinessLogger(),
              state.inputs.bootedFramework());
      state.runner.runDuring(topic, stage, body, state.inputs.onFailure());
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

    public BboxDone during(String topic, Function<BboxTopic, BboxTopic> body) {
      final BboxTopic stage =
          new BboxTopic(
              state.inputs.bboxOrchestrator(),
              state.inputs.config().localWorktreePath(),
              state.inputs.options().bboxFailOnError(),
              output -> state.builder.bbox = output);
      state.runner.runDuring(topic, stage, body, state.inputs.onFailure());
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

    public IncusDone during(String topic, Function<IncusTopic, IncusTopic> body) {
      final IncusTopic stage =
          new IncusTopic(
              state.inputs.config(),
              state.inputs.policy(),
              state.inputs.bootedFramework(),
              output -> state.builder.bootstrap = output);
      state.runner.runDuring(topic, stage, body, state.inputs.onFailure());
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
        String topic, Function<SystemdAdapterTopic, SystemdAdapterTopic> body) {
      final SeedSystemdAdapterEndpointGate gate =
          SeedSystemdAdapterEndpointGate.live(state.inputs.systemdRuntimeStatus());
      final SystemdAdapterProbe liveProbe =
          cfg -> gate.ensureReachable(cfg, state.inputs.readinessLogger());
      final SystemdAdapterTopic stage =
          new SystemdAdapterTopic(
              state.inputs.config(),
              state.inputs.policy(),
              state.inputs.pulumiMode(),
              state.inputs.readinessLogger(),
              state.inputs.runbook(),
              state.inputs.consultations(),
              Optional.of(state.inputs.doctor()),
              liveProbe,
              output -> state.builder.systemdAdapterLaunch = output,
              state.inputs.readinessAuthority(),
              Optional.of(state.builder.bootstrap().deployment().timestamp()));
      state.runner.runDuring(topic, stage, body, state.inputs.onFailure());
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

    public ResourcesDone during(String topic, Function<ResourcesTopic, ResourcesTopic> body) {
      final ResourcesTopic stage =
          new ResourcesTopic(
              state.inputs.resourceManager(),
              state.inputs.config(),
              state.inputs.policy(),
              state.inputs.options().readinessEnabled(),
              state.inputs.pulumiMode(),
              state.inputs.readinessLogger(),
              state.inputs.runbook(),
              state.inputs.consultations(),
              state.inputs.doctor(),
              state.inputs.systemdRuntimeStatus(),
              state.inputs.clusterReadinessContact(),
              () -> state.builder.bootstrap(),
              () -> state.builder.systemdAdapterLaunch(),
              output -> state.builder.resources = output);
      state.runner.runDuring(topic, stage, body, state.inputs.onFailure());
      return new ResourcesDone(state);
    }
  }

  public static final class ResourcesDone {
    private final PipelineState state;

    private ResourcesDone(PipelineState state) {
      this.state = state;
    }

    public Map<String, Object> collectOutputs() {
      return state
          .inputs
          .outputBuilder()
          .buildOutputs(
              state.inputs.config(),
              state.inputs.policy(),
              state.builder.bootstrap(),
              state.builder.bbox(),
              state.builder.systemdAdapterLaunch(),
              state.builder.resources());
    }
  }
}
