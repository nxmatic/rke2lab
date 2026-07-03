package io.nxmatic.rke2lab.controlplane.pipeline;

import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessContact;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterRuntimeStatusSnapshot;
import io.nxmatic.rke2lab.doctor.port.ConsultationLog;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.osgi.runtime.BootedFramework;
import io.nxmatic.rke2lab.pipeline.FluentTopicRunner;
import io.nxmatic.rke2lab.pipeline.OnFailure;
import io.nxmatic.rke2lab.pulumi.edge.LiveGate;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessAuthority;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * The state threaded through {@link ClusterSeedPipeline}'s topics: immutable {@link #inputs}
 * (everything known before the first topic runs), the {@link #builder} that accumulates each
 * topic's output (the builder of the next state), and the shared {@link #runner}. See
 * docs/architecture/patterns/fluent-pipeline-grammar.adoc ("State shape").
 */
final class State {

  final Inputs inputs;

  /**
   * The builder of the next state: each topic folds its output in here, and that accumulated output
   * — combined with the inputs — is what constitutes the state the next topic reads.
   */
  final StateBuilder builder = new StateBuilder();

  final FluentTopicRunner runner = new FluentTopicRunner("pipeline");

  State(Inputs inputs) {
    this.inputs = inputs;
  }

  /**
   * Everything the pipeline is configured with, known before the first topic runs and never changed
   * after. Immutable by construction — every field is {@code @NonNull} (genuinely-optional ones are
   * {@link Optional}), so a topic reads any input without a guard. Assembled once via {@link
   * Builder} at the pre-execution → execution boundary (the {@code running…()} transition).
   */
  record Inputs(
      BootstrapConfig config,
      ControlplanePolicy policy,
      BootstrapOptions options,
      Consumer<String> readinessLogger,
      boolean pulumiMode,
      LiveGate gate,
      BootedFramework bootedFramework,
      ConsultingService doctor,
      SeedSystemdAdapterRuntimeStatusSnapshot systemdRuntimeStatus,
      ClusterReadinessContact clusterReadinessContact,
      ReadinessAuthority readinessAuthority,
      BboxReconciliationOrchestrator bboxOrchestrator,
      ResourceManager resourceManager,
      OutputBuilder outputBuilder,
      OnFailure onFailure,
      Optional<ReportModel> runbook,
      Optional<ConsultationLog> consultations) {

    static Builder forCluster(BootstrapConfig config, ControlplanePolicy policy) {
      return new Builder(config, policy);
    }

    /**
     * Accumulates the inputs across the pre-execution type-state chain ({@code forCluster} → {@code
     * withOptions} → {@code using} → the optional setters → {@code running…}). Fields are {@link
     * MonotonicNonNull} (set once by their transition); {@code build()} freezes the immutable
     * record, failing fast with the field name if a required input was never supplied. The
     * genuinely-optional fields default to empty / no-op.
     */
    static final class Builder {
      private final BootstrapConfig config;
      private final ControlplanePolicy policy;
      private @MonotonicNonNull BootstrapOptions options;
      private @MonotonicNonNull Consumer<String> readinessLogger;
      private boolean pulumiMode;
      private @MonotonicNonNull LiveGate gate;
      private @MonotonicNonNull BootedFramework bootedFramework;
      private @MonotonicNonNull ConsultingService doctor;
      private @MonotonicNonNull SeedSystemdAdapterRuntimeStatusSnapshot systemdRuntimeStatus;
      private @MonotonicNonNull ClusterReadinessContact clusterReadinessContact;
      private @MonotonicNonNull ReadinessAuthority readinessAuthority;
      private @MonotonicNonNull BboxReconciliationOrchestrator bboxOrchestrator;
      private @MonotonicNonNull ResourceManager resourceManager;
      private @MonotonicNonNull OutputBuilder outputBuilder;
      private OnFailure onFailure = OnFailure.noop();
      private Optional<ReportModel> runbook = Optional.empty();
      private Optional<ConsultationLog> consultations = Optional.empty();

      private Builder(BootstrapConfig config, ControlplanePolicy policy) {
        this.config = config;
        this.policy = policy;
      }

      Builder options(BootstrapOptions options) {
        this.options = options;
        return this;
      }

      Builder using(
          BboxReconciliationOrchestrator bboxOrchestrator,
          ResourceManager resourceManager,
          OutputBuilder outputBuilder) {
        this.bboxOrchestrator = bboxOrchestrator;
        this.resourceManager = resourceManager;
        this.outputBuilder = outputBuilder;
        return this;
      }

      Builder onFailure(OnFailure onFailure) {
        this.onFailure = onFailure;
        return this;
      }

      Builder bootedFramework(BootedFramework bootedFramework) {
        this.bootedFramework = bootedFramework;
        return this;
      }

      Builder recordingInto(ReportModel runbook, ConsultationLog consultations) {
        this.runbook = Optional.of(runbook);
        this.consultations = Optional.of(consultations);
        return this;
      }

      Builder readinessLogger(Consumer<String> readinessLogger) {
        this.readinessLogger = readinessLogger;
        return this;
      }

      Builder pulumiMode(boolean pulumiMode) {
        this.pulumiMode = pulumiMode;
        return this;
      }

      Builder gate(LiveGate gate) {
        this.gate = gate;
        return this;
      }

      Builder doctor(ConsultingService doctor) {
        this.doctor = doctor;
        return this;
      }

      Builder systemdRuntimeStatus(SeedSystemdAdapterRuntimeStatusSnapshot systemdRuntimeStatus) {
        this.systemdRuntimeStatus = systemdRuntimeStatus;
        return this;
      }

      Builder clusterReadinessContact(ClusterReadinessContact clusterReadinessContact) {
        this.clusterReadinessContact = clusterReadinessContact;
        return this;
      }

      Builder readinessAuthority(ReadinessAuthority readinessAuthority) {
        this.readinessAuthority = readinessAuthority;
        return this;
      }

      BootedFramework bootedFramework() {
        return Objects.requireNonNull(bootedFramework, "bootedFramework");
      }

      Inputs build() {
        return new Inputs(
            config,
            policy,
            Objects.requireNonNull(options, "options"),
            Objects.requireNonNull(readinessLogger, "readinessLogger"),
            pulumiMode,
            Objects.requireNonNull(gate, "gate"),
            Objects.requireNonNull(bootedFramework, "bootedFramework"),
            Objects.requireNonNull(doctor, "doctor"),
            Objects.requireNonNull(systemdRuntimeStatus, "systemdRuntimeStatus"),
            Objects.requireNonNull(clusterReadinessContact, "clusterReadinessContact"),
            Objects.requireNonNull(readinessAuthority, "readinessAuthority"),
            Objects.requireNonNull(bboxOrchestrator, "bboxOrchestrator"),
            Objects.requireNonNull(resourceManager, "resourceManager"),
            Objects.requireNonNull(outputBuilder, "outputBuilder"),
            onFailure,
            runbook,
            consultations);
      }
    }
  }

  /**
   * The builder of the next state — one field per topic output, set once by that topic's sink and
   * read by a later topic (or the terminal {@code collectOutputs}). It is not a passive bag of
   * results: each output folded in advances the construction of the next topic's state. Each field
   * is {@link MonotonicNonNull} — null until its producing topic runs — and read through a guarded
   * accessor so a premature read fails fast with the field name rather than a distant NPE. See
   * docs/fluent-pipeline-grammar.adoc ("State shape").
   */
  static final class StateBuilder {
    BboxReconciliationOrchestrator.@MonotonicNonNull ReconciliationResult bbox;
    IncusResourceBootstrap.@MonotonicNonNull BootstrapResult bootstrap;
    @MonotonicNonNull Map<String, Object> systemdAdapterLaunch;
    ResourceManager.@MonotonicNonNull ResourceCreationResult resources;

    BboxReconciliationOrchestrator.ReconciliationResult bbox() {
      return Objects.requireNonNull(bbox, "bbox output (bbox topic not yet run)");
    }

    IncusResourceBootstrap.BootstrapResult bootstrap() {
      return Objects.requireNonNull(bootstrap, "bootstrap output (incus topic not yet run)");
    }

    Map<String, Object> systemdAdapterLaunch() {
      return Objects.requireNonNull(
          systemdAdapterLaunch, "systemdAdapterLaunch output (systemd-adapter topic not yet run)");
    }

    ResourceManager.ResourceCreationResult resources() {
      return Objects.requireNonNull(resources, "resources output (resources topic not yet run)");
    }
  }
}
