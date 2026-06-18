package io.nxmatic.rke2lab.controlplane.bdd;

import java.util.Map;

/** Canned cluster-readiness phase outcomes for the DSL-first nested scenario. */
final class FakeClusterReadinessProbes {

  private FakeClusterReadinessProbes() {}

  /** Every phase passes. */
  static ClusterReadinessProbe allPhasesReady() {
    return (config, phase) -> Observation.ok(phase.label() + " ok", Map.of("phase", phase.name()));
  }
}
