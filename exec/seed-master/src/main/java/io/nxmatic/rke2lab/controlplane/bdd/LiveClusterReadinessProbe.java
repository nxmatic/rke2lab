package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.cluster.port.ClusterReadinessContact;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessPhase;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier.PhaseOutcome;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterRuntimeStatusSnapshot;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The live cluster-readiness probe: each {@link ClusterReadinessPhase} delegates to the matching
 * per-phase check on {@link ClusterBootstrapReadinessVerifier} (which owns the host orchestration —
 * the systemd gate, the kubeconfig NIO poll, the policy→controller projection, the retry loops) and
 * maps the outcome to an {@link Observation} carrying a typed {@link Symptom} on failure. The
 * dependency direction is {@code bdd → readiness} only, so no package cycle.
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

  private final ControlplanePolicy policy;
  private final SeedSystemdAdapterRuntimeStatusSnapshot runtimeStatus;
  private final ClusterReadinessContact contact;
  private final Consumer<String> logger;

  public LiveClusterReadinessProbe(
      ControlplanePolicy policy,
      SeedSystemdAdapterRuntimeStatusSnapshot runtimeStatus,
      ClusterReadinessContact contact,
      Consumer<String> logger) {
    this.policy = policy;
    this.runtimeStatus = runtimeStatus;
    this.contact = contact;
    this.logger = logger;
  }

  @Override
  public Observation probe(BootstrapConfig config, ClusterReadinessPhase phase) {
    return switch (phase) {
      case KUBECONFIG_PUBLISHED ->
          toObservation(
              phase,
              ClusterBootstrapReadinessVerifier.checkKubeconfigPublished(
                  config, runtimeStatus, logger),
              Symptom.KUBECONFIG_MISSING);
      case API_READY ->
          toObservation(
              phase,
              ClusterBootstrapReadinessVerifier.checkApiReady(config, contact, logger),
              Symptom.API_NOT_READY);
      case CONTROLLERS_EFFECTIVE ->
          toObservation(
              phase,
              ClusterBootstrapReadinessVerifier.checkControllersEffective(
                  config, contact, policy, logger),
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
