package io.nxmatic.rke2lab.controlplane.bdd;

import java.util.Map;

/**
 * The canned cluster-readiness probe behind a fault simulation: every phase passes except the one
 * the operator targeted, which fails with the given {@link Symptom}. Lets a preview render the
 * runbook for "what if the {@code api-ready} phase times out?" without touching live infrastructure
 * — the same preview-only facility as {@link SimulatedSystemdAdapterProbe}, applied per phase.
 */
public final class SimulatedClusterReadinessProbe {

  private SimulatedClusterReadinessProbe() {}

  /** Fails {@code failingPhase} with {@code symptom}; all earlier/other phases report ok. */
  public static ClusterReadinessProbe failingAt(
      ClusterReadinessPhase failingPhase, Symptom symptom) {
    return (config, phase) ->
        phase == failingPhase
            ? Dossier.failed(
                symptom,
                phase.label() + " failed (simulated incident: " + symptom.id() + ")",
                Map.of("source", "fault-simulation", "phase", phase.name()))
            : Dossier.ok(phase.label() + " ok", Map.of("phase", phase.name()));
  }
}
