package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.NestedSteps;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import com.tngtech.jgiven.annotation.ScenarioStage;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessPhase;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The cluster-readiness checkpoint, grouped: its {@link Given}/{@link When}/{@link Then} stages
 * live as nested static classes so the scenario reads as one unit. It depends on the
 * systemd-adapter scenario — the {@link When} replays {@link SystemdAdapterScenario}'s stages
 * nested (the follow-the-chain dependency edge) before walking the readiness phases.
 */
public final class ClusterReadinessScenario {

  private ClusterReadinessScenario() {}

  /**
   * Given: establishes the bootstrap config, the per-phase probe, and the upstream systemd-adapter
   * probe whose scenario is played nested (the dependency edge — cluster-ready depends on
   * systemd-adapter).
   */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState BootstrapConfig config;
    @ProvidedScenarioState ClusterReadinessProbe phaseProbe;
    @ProvidedScenarioState SystemdAdapterProbe systemdAdapterProbe;

    public Given the_cluster(@Quoted String name, @Hidden BootstrapConfig config) {
      this.config = config;
      return self();
    }

    @Hidden
    public Given with_phase_probe(ClusterReadinessProbe phaseProbe) {
      this.phaseProbe = phaseProbe;
      return self();
    }

    /** The upstream dependency's probe — its scenario is played nested in the When stage. */
    @Hidden
    public Given depending_on_systemd_adapter(SystemdAdapterProbe systemdAdapterProbe) {
      this.systemdAdapterProbe = systemdAdapterProbe;
      return self();
    }
  }

  /**
   * When: first it walks the dependency chain — the systemd-adapter scenario is replayed as nested
   * steps (the cert-manager "follow the chain": cluster-ready depends on systemd-adapter) — then
   * each readiness phase is its own fluent step, chained in canonical order. The phases form a
   * strict chain (kubeconfig → API → controllers): each is a precondition of the next, so a failing
   * step throws and JGiven skips the bodies of the downstream chained steps, marking them SKIPPED
   * in the runbook. Fail-fast is the fluent chain's own semantics — no manual break, and the
   * operator still sees every phase, with the one that broke and the ones not reached.
   */
  public static class When extends Stage<When> {

    @ExpectedScenarioState BootstrapConfig config;
    @ExpectedScenarioState ClusterReadinessProbe phaseProbe;
    @ExpectedScenarioState SystemdAdapterProbe systemdAdapterProbe;

    @ScenarioStage SystemdAdapterScenario.Given givenSystemdAdapter;
    @ScenarioStage SystemdAdapterScenario.When whenSystemdAdapter;
    @ScenarioStage SystemdAdapterScenario.Then thenSystemdAdapter;

    @ProvidedScenarioState
    Map<ClusterReadinessPhase, ObservationView> phaseObservations = new LinkedHashMap<>();

    /** Nested: replay the upstream systemd-adapter scenario as sub-steps of this checkpoint. */
    @NestedSteps
    public When the_systemd_adapter_dependency_is_satisfied() {
      givenSystemdAdapter
          .the_seed_node(config.systemdAdapterDbusHost(), config)
          .probed_by(systemdAdapterProbe);
      whenSystemdAdapter.the_systemd_adapter_probe_runs();
      thenSystemdAdapter.the_dbus_endpoint_responds();
      return self();
    }

    public When the_kubeconfig_is_published() {
      return checking(ClusterReadinessPhase.KUBECONFIG_PUBLISHED);
    }

    public When the_api_is_ready() {
      return checking(ClusterReadinessPhase.API_READY);
    }

    public When the_required_controllers_are_effective() {
      return checking(ClusterReadinessPhase.CONTROLLERS_EFFECTIVE);
    }

    /**
     * Probe one phase and record its observation. A non-ok phase throws so its step is marked
     * FAILED; because the phases are chained, JGiven then skips the downstream steps' bodies and
     * marks them SKIPPED — the runbook shows exactly where readiness broke. The enum is the single
     * join between the readable step and the probe (and the simulation target), so no phase
     * identity is duplicated as a string.
     */
    private When checking(ClusterReadinessPhase phase) {
      final ObservationView observation = phaseProbe.probe(config, phase);
      phaseObservations.put(phase, observation);
      if (!observation.isOk()) {
        throw new AssertionError(phase.label() + ": " + observation.summary());
      }
      return self();
    }
  }

  /**
   * Then: the cluster is ready. A failing phase throws in the When stage (fail-fast), so this step
   * is only ever reached once every phase passed — it is the readable closing assertion of the
   * scenario, not where phase evaluation happens. The failing phase's observation (with its
   * symptom) is captured by the stage via the probe-holder seam, not read back through a stage
   * getter (JGiven intercepts public stage methods as steps), exactly as {@code
   * SystemdAdapterTopic} does.
   */
  public static class Then extends Stage<Then> {

    public Then the_cluster_is_ready() {
      return self();
    }
  }
}
