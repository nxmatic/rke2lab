package io.nxmatic.rk2lab.controlplane.pipeline;

import io.nxmatic.rk2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.pipeline.stages.BboxStage;
import io.nxmatic.rk2lab.controlplane.pipeline.stages.IncusStage;
import io.nxmatic.rk2lab.controlplane.pipeline.stages.PreflightStage;
import io.nxmatic.rk2lab.controlplane.pipeline.stages.ResourcesStage;
import io.nxmatic.rk2lab.controlplane.pipeline.stages.SystemdAdapterStage;
import io.nxmatic.rk2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rk2lab.controlplane.resources.ResourceManager;
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
 *     .reportingReadinessTo(logger)
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

    public AwaitingPreflight reportingReadinessTo(Consumer<String> readinessLogger) {
      state.readinessLogger = readinessLogger;
      return new AwaitingPreflight(state);
    }

    public AwaitingPreflight runningStandalone(Consumer<String> readinessLogger) {
      state.readinessLogger = readinessLogger;
      state.pulumiMode = false;
      return new AwaitingPreflight(state);
    }

    public AwaitingPreflight runningInPulumi(Consumer<String> readinessLogger) {
      state.readinessLogger = readinessLogger;
      state.pulumiMode = true;
      return new AwaitingPreflight(state);
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
      final SystemdAdapterStage stage =
          new SystemdAdapterStage(
              state.config,
              state.pulumiMode,
              state.readinessLogger,
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
      final ResourcesStage stage =
          new ResourcesStage(
              state.resourceManager,
              state.config,
              state.policy,
              state.options.readinessEnabled(),
              state.pulumiMode,
              state.readinessLogger,
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
