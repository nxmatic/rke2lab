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
 * on systemd-adapter) — then it runs each cluster phase, recording the per-phase dossier. The first
 * non-ok phase stops the walk and is remembered as the failing phase, exactly as the real verifier
 * fails fast.
 */
public class WhenClusterReadiness extends Stage<WhenClusterReadiness> {

  @ExpectedScenarioState BootstrapConfig config;
  @ExpectedScenarioState ClusterReadinessProbe phaseProbe;
  @ExpectedScenarioState SystemdAdapterProbe systemdAdapterProbe;

  @ScenarioStage GivenSystemdAdapter givenSystemdAdapter;
  @ScenarioStage WhenSystemdAdapter whenSystemdAdapter;
  @ScenarioStage ThenSystemdAdapter thenSystemdAdapter;

  @ProvidedScenarioState Map<ClusterReadinessPhase, Dossier> phaseDossiers = new LinkedHashMap<>();
  @ProvidedScenarioState ClusterReadinessPhase failingPhase;
  @ProvidedScenarioState Dossier failingDossier;

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

  public WhenClusterReadiness the_readiness_phases_run() {
    for (ClusterReadinessPhase phase : ClusterReadinessPhase.values()) {
      final Dossier dossier = phaseProbe.probe(config, phase);
      phaseDossiers.put(phase, dossier);
      if (!dossier.isOk()) {
        failingPhase = phase;
        failingDossier = dossier;
        return self(); // fail fast, like the real verifier
      }
    }
    return self();
  }
}
