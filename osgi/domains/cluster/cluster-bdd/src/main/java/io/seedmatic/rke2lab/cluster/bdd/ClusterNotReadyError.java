package io.seedmatic.rke2lab.cluster.bdd;

import io.seedmatic.rke2lab.cluster.contract.ClusterReadinessPhase;
import io.seedmatic.rke2lab.doctor.contract.SymptomKind;
import io.seedmatic.rke2lab.doctor.contract.Symptomatic;
import java.util.Map;

/**
 * A cluster readiness phase (kubeconfig published, API ready, controllers effective) is not ready.
 * {@link Symptomatic}: carries the failing {@link ClusterReadinessPhase} as a typed member and the
 * phase-specific {@link SymptomKind} (kubeconfig-missing / api-not-ready / controller-not-ready)
 * for the doctor's routing.
 */
public final class ClusterNotReadyError extends AssertionError implements Symptomatic {

  private final ClusterReadinessPhase phase;
  private final SymptomKind symptom;

  public ClusterNotReadyError(ClusterReadinessPhase phase, SymptomKind symptom) {
    super(phase.label() + ": not ready");
    this.phase = phase;
    this.symptom = symptom;
  }

  @Override
  public SymptomKind symptom() {
    return symptom;
  }

  @Override
  public Map<String, Object> recoveryContext() {
    return Map.of("phase", phase.name());
  }

  public ClusterReadinessPhase phase() {
    return phase;
  }
}
