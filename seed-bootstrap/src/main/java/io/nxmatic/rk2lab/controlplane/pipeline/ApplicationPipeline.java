package io.nxmatic.rk2lab.controlplane.pipeline;

import com.pulumi.Context;
import com.pulumi.Pulumi;
import io.nxmatic.rk2lab.controlplane.SeedLog;
import io.nxmatic.rk2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.pipeline.stages.BootstrapStage;
import io.nxmatic.rk2lab.controlplane.pipeline.stages.EnvironmentStage;
import io.nxmatic.rk2lab.controlplane.pipeline.stages.OutputsStage;
import io.nxmatic.rk2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rk2lab.controlplane.resources.ResourceManager;
import java.util.Map;
import java.util.function.Function;

/**
 * Top-level entry pipeline. Wraps Pulumi-vs-standalone dispatch, configuration loading, the
 * bootstrap workflow, and output export as four topics. See docs/fluent-pipeline-grammar.adoc.
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
 *     .during("bootstrap", bootstrap -&gt; bootstrap.runBootstrapPipeline())
 *     .then()
 *     .during("outputs", outputs -&gt; outputs.exportOrPrint())
 *     .complete());
 * </pre>
 */
public final class ApplicationPipeline {

  private ApplicationPipeline() {}

  public static void run(Function<Launch, Completed> body) {
    if (isPulumiEngineAvailable()) {
      Pulumi.run(context -> body.apply(new Launch(context)));
    } else {
      body.apply(new Launch(null));
    }
  }

  private static boolean isPulumiEngineAvailable() {
    final String monitor = System.getenv("PULUMI_MONITOR");
    return monitor != null && !monitor.isBlank();
  }

  static final class State {
    final Context pulumiContext;
    final boolean pulumiMode;
    final BboxReconciliationOrchestrator bboxOrchestrator;
    final ResourceManager resourceManager;
    final OutputBuilder outputBuilder;

    AutoCloseable logSinkCloseable;
    BootstrapConfig bootstrapConfig;
    ControlplanePolicy controlplanePolicy;
    BootstrapOptions options;
    Map<String, Object> outputs;
    OnFailure onFailure;

    State(Context pulumiContext) {
      this.pulumiContext = pulumiContext;
      this.pulumiMode = pulumiContext != null;
      this.bboxOrchestrator = new BboxReconciliationOrchestrator(pulumiMode);
      this.resourceManager = new ResourceManager();
      this.outputBuilder = new OutputBuilder();
    }
  }

  public static final class Launch {
    private final State state;

    Launch(Context pulumiContext) {
      this.state = new State(pulumiContext);
    }

    /** Optional: register a per-topic failure handler. Defaults to no-op when not called. */
    public Launch onFailure(OnFailure handler) {
      state.onFailure = handler;
      return this;
    }

    public EnvironmentDone during(String topic, Function<EnvironmentStage, EnvironmentStage> body) {
      final EnvironmentStage stage =
          new EnvironmentStage(
              state.pulumiContext,
              new EnvironmentStage.Sink() {
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
      TopicRunner.runDuring("pipeline", topic, stage, body, state.onFailure);
      return new EnvironmentDone(state);
    }
  }

  public static final class EnvironmentDone {
    private final State state;

    private EnvironmentDone(State state) {
      this.state = state;
    }

    public AwaitingBootstrap then() {
      return new AwaitingBootstrap(state);
    }
  }

  public static final class AwaitingBootstrap {
    private final State state;

    private AwaitingBootstrap(State state) {
      this.state = state;
    }

    public BootstrapDone during(String topic, Function<BootstrapStage, BootstrapStage> body) {
      final BootstrapStage stage =
          new BootstrapStage(
              state.pulumiMode,
              () -> state.bootstrapConfig,
              () -> state.controlplanePolicy,
              () -> state.options,
              () -> state.onFailure,
              message -> SeedLog.info("readiness", message),
              state.bboxOrchestrator,
              state.resourceManager,
              state.outputBuilder,
              outputs -> state.outputs = outputs);
      TopicRunner.runDuring("pipeline", topic, stage, body, state.onFailure);
      return new BootstrapDone(state);
    }
  }

  public static final class BootstrapDone {
    private final State state;

    private BootstrapDone(State state) {
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

    public OutputsDone during(String topic, Function<OutputsStage, OutputsStage> body) {
      final OutputsStage stage = new OutputsStage(state.pulumiContext, () -> state.outputs);
      TopicRunner.runDuring("pipeline", topic, stage, body, state.onFailure);
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
