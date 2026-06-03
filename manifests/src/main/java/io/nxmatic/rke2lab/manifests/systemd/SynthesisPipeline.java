package io.nxmatic.rke2lab.manifests.systemd;

import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import io.nxmatic.rke2lab.manifests.systemd.stages.BootstrapStage;
import io.nxmatic.rke2lab.manifests.systemd.stages.NetworkStage;
import io.nxmatic.rke2lab.manifests.systemd.stages.StorageStage;
import io.nxmatic.rke2lab.manifests.systemd.stages.ToolsStage;
import java.util.function.Function;

/**
 * Fluent synthesis pipeline for bootstrap infrastructure systemd units.
 *
 * <p>Enforces proper ordering of unit synthesis across four topics: tools installation, bootstrap
 * environment, network infrastructure, and storage/system configuration.
 *
 * <p>See docs/fluent-pipeline-grammar.adoc for the grammar this follows.
 *
 * <pre>
 * SynthesisPipeline.forChart(systemdChart, context)
 *     .onFailure((topic, cause) -&gt; LOG.error("Synthesis failed at {}", topic, cause))
 *     .during("tools installation", tools -&gt; tools
 *         .nixInstall()
 *         .floxInstall())
 *     .then()
 *     .during("bootstrap", bootstrap -&gt; bootstrap
 *         .bootstrapEnv()
 *         .configInstall()
 *         .install()
 *         .systemdLink()
 *         .cachixWatchStore())
 *     .then()
 *     .during("network infrastructure", network -&gt; network
 *         .routeCleanup()
 *         .networkConfig()
 *         .networkWait()
 *         .networkDebug())
 *     .then()
 *     .during("storage and system", storage -&gt; storage
 *         .remountShared()
 *         .containerdZfsMountConfig()
 *         .dbusTcpSystemBus()
 *         .zfsEarlyUmount()
 *         .vipKubeconfig())
 *     .complete();
 * </pre>
 */
public final class SynthesisPipeline {

  private SynthesisPipeline() {}

  public static ConfiguringPipeline forChart(
      SystemdChart systemdChart, io.nxmatic.rke2lab.manifests.SystemdSynthesisContext context) {
    return new ConfiguringPipeline(new PipelineState(systemdChart, context));
  }

  public static final class ConfiguringPipeline {
    private final PipelineState state;

    private ConfiguringPipeline(PipelineState state) {
      this.state = state;
    }

    /** Optional: register a per-topic failure handler. Defaults to no-op when not called. */
    public AwaitingTools onFailure(SynthesisOnFailure handler) {
      state.onFailure = handler;
      return new AwaitingTools(state);
    }

    public AwaitingTools begin() {
      return new AwaitingTools(state);
    }
  }

  public static final class AwaitingTools {
    private final PipelineState state;

    private AwaitingTools(PipelineState state) {
      this.state = state;
    }

    public ToolsDone during(String topic, Function<ToolsStage, ToolsStage> body) {
      final ToolsStage stage = new ToolsStage(state.systemdChart, state.context);
      SynthesisTopicRunner.runDuring("synthesis", topic, stage, body, state.onFailure);
      state.toolsStage = stage;
      return new ToolsDone(state);
    }
  }

  public static final class ToolsDone {
    private final PipelineState state;

    private ToolsDone(PipelineState state) {
      this.state = state;
    }

    public AwaitingBootstrap then() {
      return new AwaitingBootstrap(state);
    }
  }

  public static final class AwaitingBootstrap {
    private final PipelineState state;

    private AwaitingBootstrap(PipelineState state) {
      this.state = state;
    }

    public BootstrapDone during(String topic, Function<BootstrapStage, BootstrapStage> body) {
      final BootstrapStage stage =
          new BootstrapStage(state.systemdChart, state.context, state.toolsStage);
      SynthesisTopicRunner.runDuring("synthesis", topic, stage, body, state.onFailure);
      state.bootstrapStage = stage;
      return new BootstrapDone(state);
    }
  }

  public static final class BootstrapDone {
    private final PipelineState state;

    private BootstrapDone(PipelineState state) {
      this.state = state;
    }

    public AwaitingNetwork then() {
      return new AwaitingNetwork(state);
    }
  }

  public static final class AwaitingNetwork {
    private final PipelineState state;

    private AwaitingNetwork(PipelineState state) {
      this.state = state;
    }

    public NetworkDone during(String topic, Function<NetworkStage, NetworkStage> body) {
      final NetworkStage stage =
          new NetworkStage(state.systemdChart, state.context, state.bootstrapStage);
      SynthesisTopicRunner.runDuring("synthesis", topic, stage, body, state.onFailure);
      return new NetworkDone(state);
    }
  }

  public static final class NetworkDone {
    private final PipelineState state;

    private NetworkDone(PipelineState state) {
      this.state = state;
    }

    public AwaitingStorage then() {
      return new AwaitingStorage(state);
    }
  }

  public static final class AwaitingStorage {
    private final PipelineState state;

    private AwaitingStorage(PipelineState state) {
      this.state = state;
    }

    public StorageDone during(String topic, Function<StorageStage, StorageStage> body) {
      final StorageStage stage =
          new StorageStage(
              state.systemdChart, state.context, state.toolsStage, state.bootstrapStage);
      SynthesisTopicRunner.runDuring("synthesis", topic, stage, body, state.onFailure);
      return new StorageDone(state);
    }
  }

  public static final class StorageDone {
    private final PipelineState state;

    private StorageDone(PipelineState state) {
      this.state = state;
    }

    /** Terminal verb: complete the synthesis pipeline with no return value. */
    public void complete() {
      // Pipeline complete, all stages executed
    }
  }

  private static final class PipelineState {
    final SystemdChart systemdChart;
    final io.nxmatic.rke2lab.manifests.SystemdSynthesisContext context;
    SynthesisOnFailure onFailure;
    // Stage references passed between pipeline stages for cross-stage dependencies
    ToolsStage toolsStage;
    BootstrapStage bootstrapStage;

    PipelineState(
        SystemdChart systemdChart, io.nxmatic.rke2lab.manifests.SystemdSynthesisContext context) {
      this.systemdChart = systemdChart;
      this.context = context;
    }
  }
}
