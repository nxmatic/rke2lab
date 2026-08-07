// @codebase
package io.nxmatic.rke2lab.manifests.systemd;

import io.nxmatic.rke2lab.manifests.SystemdSynthesisContext;
import io.nxmatic.rke2lab.manifests.internal.synthesis.OnFailure;
import io.nxmatic.rke2lab.manifests.internal.synthesis.PhaseRunner;
import io.nxmatic.rke2lab.manifests.systemd.phases.NetworkPhase;
import io.nxmatic.rke2lab.manifests.systemd.phases.Rke2InstallPhase;
import io.nxmatic.rke2lab.manifests.systemd.phases.StoragePhase;
import io.nxmatic.rke2lab.manifests.systemd.phases.ToolsPhase;
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
 * <p>Uses the fluent pipeline grammar to enforce ordering across four phases: tools, rke2-install,
 * network, and storage/system.
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
    // Local pipeline for node systemd infrastructure units (fluent synthesis grammar).
    // Structure: tools → rke2-install → network → storage/system
    final class SynthesisPipeline {
      final State state = new State();

      final PhaseRunner runner = new PhaseRunner("synthesis");

      final class State {
        @Nullable OnFailure onFailure;
        // Ambient inherited from the enclosing synthesizer, held by the owner (the State) so every
        // phase reads it through the SAME read-face as the flux: state::systemdChart /
        // state::context.
        // The State is the single source of truth a phase stays wired to — never the outer fields.
        final SystemdChart systemdChart = SystemdInfrastructureSynthesizer.this.systemdChart;
        final SystemdSynthesisContext context = SystemdInfrastructureSynthesizer.this.context;
        // Service outputs, set once by each producing phase's sink, read by later phases through
        // Supplier read-faces (state::nixInstall, …) — never by holding the producing phase.
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
        ToolsDone during(String phase, Function<ToolsPhase, ToolsPhase> body) {
          final ToolsPhase toolsPhase =
              new ToolsPhase(
                  state::systemdChart,
                  state::context,
                  new ToolsPhase.Sink() {
                    @Override
                    public void nixInstall(SystemdService service) {
                      state.nixInstall = service;
                    }

                    @Override
                    public void floxInstall(SystemdService service) {
                      state.floxInstall = service;
                    }
                  });
          runner.runDuring(phase, toolsPhase, body, state.onFailure());
          return new ToolsDone();
        }
      }

      final class ToolsDone {
        AwaitingRke2Install then() {
          return new AwaitingRke2Install();
        }
      }

      final class AwaitingRke2Install {
        Rke2InstallDone during(String phase, Function<Rke2InstallPhase, Rke2InstallPhase> body) {
          final Rke2InstallPhase rke2InstallPhase =
              new Rke2InstallPhase(
                  state::systemdChart,
                  state::context,
                  state::nixInstall,
                  state::floxInstall,
                  new Rke2InstallPhase.Sink() {
                    @Override
                    public void bootstrapEnv(SystemdService service) {
                      state.bootstrapEnv = service;
                    }

                    @Override
                    public void install(SystemdService service) {
                      state.install = service;
                    }
                  });
          runner.runDuring(phase, rke2InstallPhase, body, state.onFailure());
          return new Rke2InstallDone();
        }
      }

      final class Rke2InstallDone {
        AwaitingNetwork then() {
          return new AwaitingNetwork();
        }
      }

      final class AwaitingNetwork {
        NetworkDone during(String phase, Function<NetworkPhase, NetworkPhase> body) {
          final NetworkPhase networkPhase =
              new NetworkPhase(state::systemdChart, state::context, state::install);
          runner.runDuring(phase, networkPhase, body, state.onFailure());
          return new NetworkDone();
        }
      }

      final class NetworkDone {
        AwaitingStorage then() {
          return new AwaitingStorage();
        }
      }

      final class AwaitingStorage {
        StorageDone during(String phase, Function<StoragePhase, StoragePhase> body) {
          final StoragePhase storagePhase =
              new StoragePhase(
                  state::systemdChart,
                  state::context,
                  state::floxInstall,
                  state::bootstrapEnv,
                  state::install);
          runner.runDuring(phase, storagePhase, body, state.onFailure());
          return new StorageDone();
        }
      }

      final class StorageDone {
        // Terminal verb: complete the synthesis pipeline with no return value.
        void complete() {
          // Pipeline complete, all phases executed.
        }
      }
    }

    new SynthesisPipeline()
        .onFailure((phase, cause) -> LOG.error("Synthesis failed at phase: {}", phase, cause))
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
                    .zfsEarlyUmount())
        .complete();

    // Note: rke2lab.target dependencies are set in DefaultManifestSynthesisService
    // after all targets and services are created
  }
}
