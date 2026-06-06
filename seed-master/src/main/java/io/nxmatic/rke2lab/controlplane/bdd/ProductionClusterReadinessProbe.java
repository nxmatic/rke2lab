package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier.PhaseOutcome;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The live cluster-readiness probe: each {@link ClusterReadinessPhase} delegates to the matching
 * per-phase check on {@link ClusterBootstrapReadinessVerifier} (the 520-line verifier is reused,
 * not rewritten) and maps the outcome to a {@link Dossier} carrying a typed {@link Symptom} on
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
  public Dossier probe(
      io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig config, ClusterReadinessPhase phase) {
    return switch (phase) {
      case KUBECONFIG_PUBLISHED ->
          toDossier(
              phase,
              ClusterBootstrapReadinessVerifier.checkKubeconfigPublished(config, logger),
              Symptom.KUBECONFIG_MISSING);
      case API_READY ->
          toDossier(
              phase,
              ClusterBootstrapReadinessVerifier.checkApiReady(config, logger),
              Symptom.API_NOT_READY);
      case CONTROLLERS_EFFECTIVE ->
          toDossier(
              phase,
              ClusterBootstrapReadinessVerifier.checkControllersEffective(config, policy, logger),
              Symptom.CONTROLLER_NOT_READY);
    };
  }

  private static Dossier toDossier(
      ClusterReadinessPhase phase, PhaseOutcome outcome, Symptom failureSymptom) {
    final Map<String, Object> details = Map.of("phase", phase.name());
    return outcome.ok()
        ? Dossier.ok(outcome.detail(), details)
        : Dossier.failed(failureSymptom, outcome.detail(), details);
  }
}
