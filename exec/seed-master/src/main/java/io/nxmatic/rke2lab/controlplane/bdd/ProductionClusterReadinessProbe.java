package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier.PhaseOutcome;
import io.nxmatic.rke2lab.doctor.port.ClusterReadinessPhase;
import io.nxmatic.rke2lab.doctor.port.Observation;
import io.nxmatic.rke2lab.doctor.port.Symptom;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The live cluster-readiness probe: each {@link ClusterReadinessPhase} delegates to the matching
 * per-phase check on {@link ClusterBootstrapReadinessVerifier} (the 520-line verifier is reused,
 * not rewritten) and maps the outcome to an {@link Observation} carrying a typed {@link Symptom} on
 * failure. The dependency direction is {@code bdd → readiness} only, so no package cycle.
 *
 * <p>Symptoms are typed and named in the runbook from Increment D; no specialist treats them yet,
 * so the doctor produces an empty plan (symptom seen, no treatment offered).
 */
public final class ProductionClusterReadinessProbe implements ClusterReadinessProbe {

  private final ControlplanePolicy policy;
  private final Consumer<String> logger;

  public ProductionClusterReadinessProbe(ControlplanePolicy policy, Consumer<String> logger) {
    this.policy = policy;
    this.logger = logger;
  }

  @Override
  public Observation probe(BootstrapConfig config, ClusterReadinessPhase phase) {
    return switch (phase) {
      case KUBECONFIG_PUBLISHED ->
          toObservation(
              phase,
              ClusterBootstrapReadinessVerifier.checkKubeconfigPublished(config, logger),
              Symptom.KUBECONFIG_MISSING);
      case API_READY ->
          toObservation(
              phase,
              ClusterBootstrapReadinessVerifier.checkApiReady(config, logger),
              Symptom.API_NOT_READY);
      case CONTROLLERS_EFFECTIVE ->
          toObservation(
              phase,
              ClusterBootstrapReadinessVerifier.checkControllersEffective(config, policy, logger),
              Symptom.CONTROLLER_NOT_READY);
    };
  }

  private static Observation toObservation(
      ClusterReadinessPhase phase, PhaseOutcome outcome, Symptom failureSymptom) {
    final Map<String, Object> details = Map.of("phase", phase.name());
    return outcome.ok()
        ? Observation.ok(outcome.detail(), details)
        : Observation.failed(failureSymptom, outcome.detail(), details);
  }
}
