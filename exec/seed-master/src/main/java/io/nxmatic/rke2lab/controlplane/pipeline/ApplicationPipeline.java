package io.nxmatic.rke2lab.controlplane.pipeline;

import com.pulumi.Context;
import com.pulumi.Pulumi;
import io.nxmatic.rke2lab.controlplane.SeedLog;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.pipeline.stages.ClusterSeedTopic;
import io.nxmatic.rke2lab.controlplane.pipeline.stages.EnvironmentTopic;
import io.nxmatic.rke2lab.controlplane.pipeline.stages.OutputsTopic;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager;
import io.nxmatic.rke2lab.pipeline.FluentTopicRunner;
import io.nxmatic.rke2lab.pipeline.OnFailure;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Top-level entry pipeline. Wraps Pulumi-vs-standalone dispatch, configuration loading, the
 * cluster-seed workflow, and output export as four topics. See docs/fluent-pipeline-grammar.adoc.
 *
 * <pre>
 * ApplicationPipeline.run(launch -&gt; launch
 *     .onFailure(SeedLog::error)
 *     .during("environment", env -&gt; env
 *         .installLogSink()
 *         .loadBootstrapConfig()
 *         .loadControlplanePolicy()
 *         .loadOptions())
 *     .then()
 *     .during("cluster seed", seed -&gt; seed.seedCluster())
 *     .then()
 *     .during("outputs", outputs -&gt; outputs.exportOrPrint())
 *     .complete());
 * </pre>
 */
public final class ApplicationPipeline {

  private ApplicationPipeline() {}

  public static void run(Function<Launch, Completed> body) {
    if (isPulumiEngineAvailable()) {
      Pulumi.run(context -> body.apply(new Launch(Optional.of(context))));
    } else {
      body.apply(new Launch(Optional.empty()));
    }
  }

  private static boolean isPulumiEngineAvailable() {
    final String monitor = System.getenv("PULUMI_MONITOR");
    return monitor != null && !monitor.isBlank();
  }

  static final class State {
    final Optional<Context> pulumiContext;
    final boolean pulumiMode;
    final BboxReconciliationOrchestrator bboxOrchestrator;
    final ResourceManager resourceManager;
    final OutputBuilder outputBuilder;

    // Outputs each topic produces, set once by that topic's sink and read by a later topic — the
    // builder of the next state (see docs/fluent-pipeline-grammar.adoc, "State shape").
    @MonotonicNonNull AutoCloseable logSinkCloseable;
    @MonotonicNonNull BootstrapConfig bootstrapConfig;
    @MonotonicNonNull ControlplanePolicy controlplanePolicy;
    @MonotonicNonNull BootstrapOptions options;
    @MonotonicNonNull Map<String, Object> outputs;
    OnFailure onFailure = OnFailure.noop();
    final FluentTopicRunner runner = new FluentTopicRunner("pipeline");

    State(Optional<Context> pulumiContext) {
      this.pulumiContext = pulumiContext;
      this.pulumiMode = pulumiContext.isPresent();
      this.bboxOrchestrator = new BboxReconciliationOrchestrator(pulumiMode);
      this.resourceManager = new ResourceManager();
      this.outputBuilder = new OutputBuilder();
    }

    BootstrapConfig bootstrapConfig() {
      return Objects.requireNonNull(
          bootstrapConfig, "bootstrapConfig (environment topic not yet run)");
    }

    ControlplanePolicy controlplanePolicy() {
      return Objects.requireNonNull(
          controlplanePolicy, "controlplanePolicy (environment topic not yet run)");
    }

    BootstrapOptions options() {
      return Objects.requireNonNull(options, "options (environment topic not yet run)");
    }

    Map<String, Object> outputs() {
      return Objects.requireNonNull(outputs, "outputs (cluster-seed topic not yet run)");
    }
  }

  public static final class Launch {
    private final State state;

    Launch(Optional<Context> pulumiContext) {
      this.state = new State(pulumiContext);
    }

    /** Optional: register a per-topic failure handler. Defaults to no-op when not called. */
    public Launch onFailure(OnFailure handler) {
      state.onFailure = handler;
      return this;
    }

    public EnvironmentDone during(String topic, Function<EnvironmentTopic, EnvironmentTopic> body) {
      final EnvironmentTopic stage =
          new EnvironmentTopic(
              state.pulumiContext,
              new EnvironmentTopic.Sink() {
                @Override
                public void logSink(AutoCloseable closeable) {
                  state.logSinkCloseable = closeable;
                }

                @Override
                public void bootstrapConfig(BootstrapConfig config) {
                  state.bootstrapConfig = config;
                }

                @Override
                public void controlplanePolicy(ControlplanePolicy policy) {
                  state.controlplanePolicy = policy;
                }

                @Override
                public void options(BootstrapOptions options) {
                  state.options = options;
                }
              });
      state.runner.runDuring(topic, stage, body, state.onFailure);
      return new EnvironmentDone(state);
    }
  }

  public static final class EnvironmentDone {
    private final State state;

    private EnvironmentDone(State state) {
      this.state = state;
    }

    public AwaitingClusterSeed then() {
      return new AwaitingClusterSeed(state);
    }
  }

  public static final class AwaitingClusterSeed {
    private final State state;

    private AwaitingClusterSeed(State state) {
      this.state = state;
    }

    public ClusterSeedDone during(String topic, Function<ClusterSeedTopic, ClusterSeedTopic> body) {
      final ClusterSeedTopic stage =
          new ClusterSeedTopic(
              state.pulumiMode,
              state::bootstrapConfig,
              state::controlplanePolicy,
              state::options,
              () -> state.onFailure,
              message -> SeedLog.info("readiness", message),
              state.bboxOrchestrator,
              state.resourceManager,
              state.outputBuilder,
              outputs -> state.outputs = outputs);
      state.runner.runDuring(topic, stage, body, state.onFailure);
      return new ClusterSeedDone(state);
    }
  }

  public static final class ClusterSeedDone {
    private final State state;

    private ClusterSeedDone(State state) {
      this.state = state;
    }

    public AwaitingOutputs then() {
      return new AwaitingOutputs(state);
    }
  }

  public static final class AwaitingOutputs {
    private final State state;

    private AwaitingOutputs(State state) {
      this.state = state;
    }

    public OutputsDone during(String topic, Function<OutputsTopic, OutputsTopic> body) {
      final OutputsTopic stage = new OutputsTopic(state.pulumiContext, state::outputs);
      state.runner.runDuring(topic, stage, body, state.onFailure);
      return new OutputsDone(state);
    }
  }

  public static final class OutputsDone {
    private final State state;

    private OutputsDone(State state) {
      this.state = state;
    }

    public Completed complete() {
      if (state.logSinkCloseable != null) {
        try {
          state.logSinkCloseable.close();
        } catch (Exception ignored) {
          // log sink close is best-effort
        }
      }
      return Completed.INSTANCE;
    }
  }

  public static final class Completed {
    private static final Completed INSTANCE = new Completed();

    private Completed() {}
  }
}
