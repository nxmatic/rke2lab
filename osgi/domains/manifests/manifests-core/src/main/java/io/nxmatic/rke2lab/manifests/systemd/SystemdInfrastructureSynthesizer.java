// @codebase
package io.nxmatic.rke2lab.manifests.systemd;

import io.nxmatic.rke2lab.manifests.SystemdSynthesisContext;
import io.nxmatic.rke2lab.manifests.systemd.stages.NetworkTopic;
import io.nxmatic.rke2lab.manifests.systemd.stages.Rke2InstallTopic;
import io.nxmatic.rke2lab.manifests.systemd.stages.StorageTopic;
import io.nxmatic.rke2lab.manifests.systemd.stages.ToolsTopic;
import io.nxmatic.rke2lab.pipeline.FluentTopicRunner;
import io.nxmatic.rke2lab.pipeline.OnFailure;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdChart;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Synthesizes a node's cross-cutting systemd infrastructure units via a fluent pipeline.
 *
 * <p>These units belong to no specific domain: tooling (Nix/Flox), the RKE2 install (env, config,
 * install, systemd linking), network setup, storage/system configuration. They apply to both node
 * roles (rke2-server and rke2-agent).
 *
 * <p>Uses the fluent pipeline grammar to enforce ordering across four topics: tools, rke2-install,
 * network, and storage/system.
 *
 * <p>See docs/fluent-pipeline-grammar.adoc for the pattern this follows.
 */
public final class SystemdInfrastructureSynthesizer {

  static final Logger LOG = LoggerFactory.getLogger(SystemdInfrastructureSynthesizer.class);

  final SystemdChart systemdChart;
  final SystemdSynthesisContext context;

  public SystemdInfrastructureSynthesizer(
      SystemdChart systemdChart, SystemdSynthesisContext context) {
    this.systemdChart = systemdChart;
    this.context = context;
  }

  /**
   * Synthesizes all node systemd infrastructure units via the fluent pipeline.
   *
   * <p>Enforces ordering: tools → rke2-install → network → storage/system.
   */
  public void synthesizeAll() {
    // Local pipeline for node systemd infrastructure units (fluent grammar, see
    // docs/fluent-pipeline-grammar.adoc).
    // Structure: tools → rke2-install → network → storage/system
    final class SynthesisPipeline {
      final State state = new State();

      final FluentTopicRunner runner = new FluentTopicRunner("synthesis");

      final class State {
        @Nullable OnFailure onFailure;
        // Topic references threaded for cross-topic dependencies.
        @Nullable ToolsTopic toolsTopic;
        @Nullable Rke2InstallTopic rke2InstallTopic;

        OnFailure onFailure() {
          return Objects.requireNonNull(onFailure, "onFailure not yet set");
        }

        ToolsTopic toolsTopic() {
          return Objects.requireNonNull(toolsTopic, "tools topic not yet produced");
        }

        Rke2InstallTopic rke2InstallTopic() {
          return Objects.requireNonNull(rke2InstallTopic, "rke2-install topic not yet produced");
        }
      }

      AwaitingTools onFailure(OnFailure handler) {
        state.onFailure = handler;
        return new AwaitingTools();
      }

      final class AwaitingTools {
        ToolsDone during(String topic, Function<ToolsTopic, ToolsTopic> body) {
          final ToolsTopic toolsTopic = new ToolsTopic(systemdChart, context);
          runner.runDuring(topic, toolsTopic, body, state.onFailure());
          state.toolsTopic = toolsTopic;
          return new ToolsDone();
        }
      }

      final class ToolsDone {
        AwaitingRke2Install then() {
          return new AwaitingRke2Install();
        }
      }

      final class AwaitingRke2Install {
        Rke2InstallDone during(String topic, Function<Rke2InstallTopic, Rke2InstallTopic> body) {
          final Rke2InstallTopic rke2InstallTopic =
              new Rke2InstallTopic(systemdChart, context, state.toolsTopic());
          runner.runDuring(topic, rke2InstallTopic, body, state.onFailure());
          state.rke2InstallTopic = rke2InstallTopic;
          return new Rke2InstallDone();
        }
      }

      final class Rke2InstallDone {
        AwaitingNetwork then() {
          return new AwaitingNetwork();
        }
      }

      final class AwaitingNetwork {
        NetworkDone during(String topic, Function<NetworkTopic, NetworkTopic> body) {
          final NetworkTopic networkTopic =
              new NetworkTopic(systemdChart, context, state.rke2InstallTopic());
          runner.runDuring(topic, networkTopic, body, state.onFailure());
          return new NetworkDone();
        }
      }

      final class NetworkDone {
        AwaitingStorage then() {
          return new AwaitingStorage();
        }
      }

      final class AwaitingStorage {
        StorageDone during(String topic, Function<StorageTopic, StorageTopic> body) {
          final StorageTopic storageTopic =
              new StorageTopic(systemdChart, context, state.toolsTopic(), state.rke2InstallTopic());
          runner.runDuring(topic, storageTopic, body, state.onFailure());
          return new StorageDone();
        }
      }

      final class StorageDone {
        // Terminal verb: complete the synthesis pipeline with no return value.
        void complete() {
          // Pipeline complete, all topics executed.
        }
      }
    }

    new SynthesisPipeline()
        .onFailure((topic, cause) -> LOG.error("Synthesis failed at topic: {}", topic, cause))
        .during("tools installation", tools -> tools.nixInstall().floxInstall())
        .then()
        .during(
            "rke2 install",
            rke2Install ->
                rke2Install
                    .bootstrapEnv()
                    .configInstall()
                    .install()
                    .systemdLink()
                    .cachixWatchStore())
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
