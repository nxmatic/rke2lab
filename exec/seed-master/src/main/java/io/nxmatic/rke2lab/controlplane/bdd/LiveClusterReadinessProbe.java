package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.cluster.port.ClusterReadinessContact;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessPhase;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier.PhaseOutcome;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterRuntimeStatusSnapshot;
import io.nxmatic.rke2lab.seed.broker.port.SymptomKind;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The live cluster-readiness probe: each {@link ClusterReadinessPhase} delegates to the matching
 * per-phase check on {@link ClusterBootstrapReadinessVerifier} (which owns the host orchestration —
 * the systemd gate, the kubeconfig NIO poll, the policy→controller projection, the retry loops) and
 * maps the outcome to an {@link ObservationView} carrying a typed {@link SymptomKind} on failure.
 * The dependency direction is {@code bdd → readiness} only, so no package cycle.
 *
 * <p>The two kubectl-backed phases (API readiness, controller effectiveness) are satisfied by the
 * injected {@link ClusterReadinessContact} edge, resolved once from the OSGi registry (the
 * cluster-edge {@code @Component}). This probe never reaches the edge statically — it is passed the
 * contact, then threads it to the verifier's per-phase checks.
 *
 * <p>Symptoms are typed and named in the runbook from Increment D; no specialist treats them yet,
 * so the doctor produces an empty plan (symptom seen, no treatment offered).
 */
public final class LiveClusterReadinessProbe implements ClusterReadinessProbe {

  private final ClusterBootstrapReadinessVerifier verifier;

  public LiveClusterReadinessProbe(
      ControlplanePolicy policy,
      SeedSystemdAdapterRuntimeStatusSnapshot runtimeStatus,
      ClusterReadinessContact contact,
      Consumer<String> logger) {
    this.verifier = new ClusterBootstrapReadinessVerifier(contact, policy, runtimeStatus, logger);
  }

  @Override
  public ObservationView probe(BootstrapConfig config, ClusterReadinessPhase phase) {
    return switch (phase) {
      case KUBECONFIG_PUBLISHED ->
          toObservation(
              phase, verifier.checkKubeconfigPublished(config), SymptomKind.KUBECONFIG_MISSING);
      case API_READY ->
          toObservation(phase, verifier.checkApiReady(config), SymptomKind.API_NOT_READY);
      case CONTROLLERS_EFFECTIVE ->
          toObservation(
              phase, verifier.checkControllersEffective(config), SymptomKind.CONTROLLER_NOT_READY);
    };
  }

  private static ObservationView toObservation(
      ClusterReadinessPhase phase, PhaseOutcome outcome, SymptomKind failureSymptom) {
    final Map<String, Object> details = Map.of("phase", phase.name());
    return outcome.ok()
        ? ObservationView.ok(outcome.detail(), details)
        : ObservationView.failed(failureSymptom, outcome.detail(), details);
  }
}
