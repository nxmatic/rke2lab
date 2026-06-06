package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
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
 * on systemd-adapter) — then it runs each cluster phase as its own nested step so the runbook shows
 * which phase passed and which failed. The phases form a strict chain (kubeconfig → API →
 * controllers): each is a precondition of the next, so a failing phase throws and the remaining
 * phases are never played — fail-fast decided by the step itself, no information lost (downstream
 * phases are undefined without their upstream).
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

  /** Nested: each phase renders as its own step, named by the phase label. */
  @NestedSteps
  public WhenClusterReadiness the_readiness_phases_run() {
    for (ClusterReadinessPhase phase : ClusterReadinessPhase.values()) {
      checking(phase);
      // Fail-fast decided by the chain: each phase is a precondition of the next, so once one is
      // not ok we stop — the dependent downstream phases are never played. The break (not the
      // throw) stops the loop: inside @NestedSteps JGiven defers a step's exception (it marks the
      // step failed and re-throws only at finished()), so the throw alone would not unwind here.
      if (!phaseDossiers.get(phase).isOk()) {
        break;
      }
    }
    return self();
  }

  @As("$")
  WhenClusterReadiness checking(ClusterReadinessPhase phase) {
    final Dossier dossier = phaseProbe.probe(config, phase);
    phaseDossiers.put(phase, dossier);
    if (!dossier.isOk()) {
      // Throwing marks this phase's step failed (red) in the runbook; the loop above does the
      // fail-fast stop. The exception is re-raised by JGiven at finished(), failing the scenario.
      throw new AssertionError(phase.label() + ": " + dossier.summary());
    }
    return self();
  }
}
