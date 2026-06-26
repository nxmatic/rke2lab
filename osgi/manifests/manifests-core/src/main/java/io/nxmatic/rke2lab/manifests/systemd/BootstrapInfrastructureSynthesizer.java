// @codebase
package io.nxmatic.rke2lab.manifests.systemd;

import io.nxmatic.rke2lab.manifests.SystemdSynthesisContext;
import io.nxmatic.rke2lab.manifests.systemd.stages.BootstrapStage;
import io.nxmatic.rke2lab.manifests.systemd.stages.NetworkStage;
import io.nxmatic.rke2lab.manifests.systemd.stages.StorageStage;
import io.nxmatic.rke2lab.manifests.systemd.stages.ToolsStage;
import io.nxmatic.rke2lab.pipeline.FluentTopicRunner;
import io.nxmatic.rke2lab.pipeline.OnFailure;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdChart;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Synthesizes bootstrap and infrastructure systemd units via a fluent pipeline.
 *
 * <p>These units are cross-cutting infrastructure that don't belong to any specific domain:
 * bootstrap environment, Nix/Flox installation, networking setup, storage configuration, etc.
 *
 * <p>Uses the fluent pipeline grammar to enforce proper ordering across four topics: tools
 * installation, bootstrap environment, network infrastructure, and storage/system configuration.
 *
 * <p>See docs/fluent-pipeline-grammar.adoc for the pattern this follows.
 */
public final class BootstrapInfrastructureSynthesizer {

  static final Logger LOG = LoggerFactory.getLogger(BootstrapInfrastructureSynthesizer.class);

  final SystemdChart systemdChart;
  final SystemdSynthesisContext context;

  public BootstrapInfrastructureSynthesizer(
      SystemdChart systemdChart, SystemdSynthesisContext context) {
    this.systemdChart = systemdChart;
    this.context = context;
  }

  /**
   * Synthesizes all bootstrap and infrastructure units via the fluent pipeline.
   *
   * <p>Enforces proper ordering: tools → bootstrap → network → storage/system.
   */
  public void synthesizeAll() {
    // Local pipeline for bootstrap infrastructure systemd units (fluent grammar, see
    // docs/fluent-pipeline-grammar.adoc).
    // Structure: tools → bootstrap → network → storage/system
    final class SynthesisPipeline {
      final State state = new State();

      final class State {
        OnFailure onFailure;
        // Stage references threaded for cross-stage dependencies.
        ToolsStage toolsStage;
        BootstrapStage bootstrapStage;
      }

      AwaitingTools onFailure(OnFailure handler) {
        state.onFailure = handler;
        return new AwaitingTools();
      }

      final class AwaitingTools {
        ToolsDone during(String topic, Function<ToolsStage, ToolsStage> body) {
          final ToolsStage stage = new ToolsStage(systemdChart, context);
          FluentTopicRunner.runDuring("synthesis", topic, stage, body, state.onFailure);
          state.toolsStage = stage;
          return new ToolsDone();
        }
      }

      final class ToolsDone {
        AwaitingBootstrap then() {
          return new AwaitingBootstrap();
        }
      }

      final class AwaitingBootstrap {
        BootstrapDone during(String topic, Function<BootstrapStage, BootstrapStage> body) {
          final BootstrapStage stage = new BootstrapStage(systemdChart, context, state.toolsStage);
          FluentTopicRunner.runDuring("synthesis", topic, stage, body, state.onFailure);
          state.bootstrapStage = stage;
          return new BootstrapDone();
        }
      }

      final class BootstrapDone {
        AwaitingNetwork then() {
          return new AwaitingNetwork();
        }
      }

      final class AwaitingNetwork {
        NetworkDone during(String topic, Function<NetworkStage, NetworkStage> body) {
          final NetworkStage stage = new NetworkStage(systemdChart, context, state.bootstrapStage);
          FluentTopicRunner.runDuring("synthesis", topic, stage, body, state.onFailure);
          return new NetworkDone();
        }
      }

      final class NetworkDone {
        AwaitingStorage then() {
          return new AwaitingStorage();
        }
      }

      final class AwaitingStorage {
        StorageDone during(String topic, Function<StorageStage, StorageStage> body) {
          final StorageStage stage =
              new StorageStage(systemdChart, context, state.toolsStage, state.bootstrapStage);
          FluentTopicRunner.runDuring("synthesis", topic, stage, body, state.onFailure);
          return new StorageDone();
        }
      }

      final class StorageDone {
        // Terminal verb: complete the synthesis pipeline with no return value.
        void complete() {
          // Pipeline complete, all stages executed.
        }
      }
    }

    new SynthesisPipeline()
        .onFailure((topic, cause) -> LOG.error("Synthesis failed at topic: {}", topic, cause))
        .during("tools installation", tools -> tools.nixInstall().floxInstall())
        .then()
        .during(
            "bootstrap",
            bootstrap ->
                bootstrap.bootstrapEnv().configInstall().install().systemdLink().cachixWatchStore())
        .then()
        .during(
            "network infrastructure",
            network -> network.routeCleanup().networkConfig().networkWait().networkDebug())
        .then()
        .during(
            "storage and system",
            storage ->
                storage
                    .remountShared()
                    .containerdZfsMountConfig()
                    .dbusTcpSystemBus()
                    .zfsEarlyUmount()
                    .vipKubeconfig())
        .complete();

    // Note: rke2lab.target dependencies are set in DefaultManifestSynthesisService
    // after all targets and services are created
  }
}
