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
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdService;
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
        // Ambient inherited from the enclosing synthesizer, held by the owner (the State) so every
        // topic reads it through the SAME read-face as the flux: state::systemdChart /
        // state::context.
        // The State is the single source of truth a topic stays wired to — never the outer fields.
        final SystemdChart systemdChart = SystemdInfrastructureSynthesizer.this.systemdChart;
        final SystemdSynthesisContext context = SystemdInfrastructureSynthesizer.this.context;
        // Service outputs, set once by each producing topic's sink, read by later topics through
        // Supplier read-faces (state::nixInstall, …) — never by holding the producing topic.
        @Nullable SystemdService nixInstall;
        @Nullable SystemdService floxInstall;
        @Nullable SystemdService bootstrapEnv;
        @Nullable SystemdService install;

        SystemdChart systemdChart() {
          return systemdChart;
        }

        SystemdSynthesisContext context() {
          return context;
        }

        OnFailure onFailure() {
          return Objects.requireNonNull(onFailure, "onFailure not yet set");
        }

        SystemdService nixInstall() {
          return Objects.requireNonNull(nixInstall, "nix-install not yet produced");
        }

        SystemdService floxInstall() {
          return Objects.requireNonNull(floxInstall, "flox-install not yet produced");
        }

        SystemdService bootstrapEnv() {
          return Objects.requireNonNull(bootstrapEnv, "bootstrap-env not yet produced");
        }

        SystemdService install() {
          return Objects.requireNonNull(install, "install not yet produced");
        }
      }

      AwaitingTools onFailure(OnFailure handler) {
        state.onFailure = handler;
        return new AwaitingTools();
      }

      final class AwaitingTools {
        ToolsDone during(String topic, Function<ToolsTopic, ToolsTopic> body) {
          final ToolsTopic toolsTopic =
              new ToolsTopic(
                  state::systemdChart,
                  state::context,
                  new ToolsTopic.Sink() {
                    @Override
                    public void nixInstall(SystemdService service) {
                      state.nixInstall = service;
                    }

                    @Override
                    public void floxInstall(SystemdService service) {
                      state.floxInstall = service;
                    }
                  });
          runner.runDuring(topic, toolsTopic, body, state.onFailure());
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
              new Rke2InstallTopic(
                  state::systemdChart,
                  state::context,
                  state::nixInstall,
                  state::floxInstall,
                  new Rke2InstallTopic.Sink() {
                    @Override
                    public void bootstrapEnv(SystemdService service) {
                      state.bootstrapEnv = service;
                    }

                    @Override
                    public void install(SystemdService service) {
                      state.install = service;
                    }
                  });
          runner.runDuring(topic, rke2InstallTopic, body, state.onFailure());
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
              new NetworkTopic(state::systemdChart, state::context, state::install);
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
              new StorageTopic(
                  state::systemdChart,
                  state::context,
                  state::floxInstall,
                  state::bootstrapEnv,
                  state::install);
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
