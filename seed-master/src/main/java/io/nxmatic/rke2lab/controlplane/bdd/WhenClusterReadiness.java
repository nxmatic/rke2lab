package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.NestedSteps;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioStage;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * When stage for cluster readiness. First it walks the dependency chain — the systemd-adapter
 * scenario is replayed as nested steps (the cert-manager "follow the chain": cluster-ready depends
 * on systemd-adapter) — then each readiness phase is its own fluent step, chained in canonical
 * order. The phases form a strict chain (kubeconfig → API → controllers): each is a precondition of
 * the next, so a failing step throws and JGiven skips the bodies of the downstream chained steps,
 * marking them SKIPPED in the runbook. Fail-fast is the fluent chain's own semantics — no manual
 * break, and the operator still sees every phase, with the one that broke and the ones not reached.
 */
public class WhenClusterReadiness extends Stage<WhenClusterReadiness> {

  @ExpectedScenarioState BootstrapConfig config;
  @ExpectedScenarioState ClusterReadinessProbe phaseProbe;
  @ExpectedScenarioState SystemdAdapterProbe systemdAdapterProbe;

  @ScenarioStage GivenSystemdAdapter givenSystemdAdapter;
  @ScenarioStage WhenSystemdAdapter whenSystemdAdapter;
  @ScenarioStage ThenSystemdAdapter thenSystemdAdapter;

  @ProvidedScenarioState Map<ClusterReadinessPhase, Dossier> phaseDossiers = new LinkedHashMap<>();

  /** Nested: replay the upstream systemd-adapter scenario as sub-steps of this checkpoint. */
  @NestedSteps
  public WhenClusterReadiness the_systemd_adapter_dependency_is_satisfied() {
    givenSystemdAdapter
        .the_seed_node(config.systemdAdapterDbusHost(), config)
        .probed_by(systemdAdapterProbe);
    whenSystemdAdapter.the_systemd_adapter_probe_runs();
    thenSystemdAdapter.the_dbus_endpoint_responds();
    return self();
  }

  public WhenClusterReadiness the_kubeconfig_is_published() {
    return checking(ClusterReadinessPhase.KUBECONFIG_PUBLISHED);
  }

  public WhenClusterReadiness the_api_is_ready() {
    return checking(ClusterReadinessPhase.API_READY);
  }

  public WhenClusterReadiness the_required_controllers_are_effective() {
    return checking(ClusterReadinessPhase.CONTROLLERS_EFFECTIVE);
  }

  /**
   * Probe one phase and record its dossier. A non-ok phase throws so its step is marked FAILED;
   * because the phases are chained, JGiven then skips the downstream steps' bodies and marks them
   * SKIPPED — the runbook shows exactly where readiness broke. The enum is the single join between
   * the readable step and the probe (and the simulation target), so no phase identity is duplicated
   * as a string.
   */
  private WhenClusterReadiness checking(ClusterReadinessPhase phase) {
    final Dossier dossier = phaseProbe.probe(config, phase);
    phaseDossiers.put(phase, dossier);
    if (!dossier.isOk()) {
      throw new AssertionError(phase.label() + ": " + dossier.summary());
    }
    return self();
  }
}
