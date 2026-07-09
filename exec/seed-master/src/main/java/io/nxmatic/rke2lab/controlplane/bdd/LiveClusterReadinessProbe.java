package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.cluster.port.ClusterReadinessContact;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessPhase;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier.PhaseOutcome;
import io.nxmatic.rke2lab.controlplane.resources.SeedNodeBootstrapWatcher;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterRuntimeStatusSnapshot;
import io.nxmatic.rke2lab.seed.broker.port.SymptomKind;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The live cluster-readiness probe: each {@link ClusterReadinessPhase} maps to a check and yields
 * an {@link ObservationView} carrying a typed {@link SymptomKind} on failure. The dependency
 * direction is {@code bdd → readiness} only, so no package cycle.
 *
 * <p>The three cluster phases (kubeconfig poll, API readiness, controller effectiveness) are pure
 * cluster reasoning, delegated to {@link ClusterBootstrapReadinessVerifier} over the injected
 * {@link ClusterReadinessContact} edge. The seed-node systemd/bootstrap gate that must converge
 * BEFORE the kubeconfig can appear is a HOST, systemd-domain concern — it lives HERE, in front of
 * the kubeconfig phase, not in the cluster verifier (which stays systemd-free so it can descend to
 * the domain).
 *
 * <p>Symptoms are typed and named in the runbook from Increment D; no specialist treats them yet,
 * so the doctor produces an empty plan (symptom seen, no treatment offered).
 */
public final class LiveClusterReadinessProbe implements ClusterReadinessProbe {

  private static final Duration GATE_RETRY_INTERVAL = Duration.ofSeconds(2);
  private static final Duration GATE_PROGRESS_LOG_INTERVAL = Duration.ofSeconds(30);

  private final ClusterBootstrapReadinessVerifier verifier;
  private final SeedSystemdAdapterRuntimeStatusSnapshot runtimeStatus;
  private final Consumer<String> logger;

  public LiveClusterReadinessProbe(
      ControlplanePolicy policy,
      SeedSystemdAdapterRuntimeStatusSnapshot runtimeStatus,
      ClusterReadinessContact contact,
      Consumer<String> logger) {
    this.verifier = new ClusterBootstrapReadinessVerifier(contact, policy, logger);
    this.runtimeStatus = runtimeStatus;
    this.logger = logger;
  }

  @Override
  public ObservationView probe(BootstrapConfig config, ClusterReadinessPhase phase) {
    return switch (phase) {
      case KUBECONFIG_PUBLISHED ->
          toObservation(phase, kubeconfigPublished(config), SymptomKind.KUBECONFIG_MISSING);
      case API_READY ->
          toObservation(phase, verifier.checkApiReady(config), SymptomKind.API_NOT_READY);
      case CONTROLLERS_EFFECTIVE ->
          toObservation(
              phase, verifier.checkControllersEffective(config), SymptomKind.CONTROLLER_NOT_READY);
    };
  }

  /**
   * The seed-node systemd/bootstrap gate (host, systemd domain) must converge before the kubeconfig
   * can appear; only then does the cluster verifier poll for it. Two host concerns kept in order,
   * out of the cluster reasoning.
   */
  private PhaseOutcome kubeconfigPublished(BootstrapConfig config) {
    if (!SeedNodeBootstrapWatcher.waitForBootstrapPreconditions(
        config,
        runtimeStatus,
        new SeedNodeBootstrapWatcher.WaitConfig(
            config.readinessTimeout(), GATE_RETRY_INTERVAL, GATE_PROGRESS_LOG_INTERVAL),
        logger)) {
      return new PhaseOutcome(
          false,
          "seed node bootstrap gate did not converge (systemd jobs/services + rke2"
              + " preconditions) for "
              + config.nodeName()
              + " in project "
              + config.incusProject());
    }
    return verifier.checkKubeconfigPublished(config);
  }

  private static ObservationView toObservation(
      ClusterReadinessPhase phase, PhaseOutcome outcome, SymptomKind failureSymptom) {
    final Map<String, Object> details = Map.of("phase", phase.name());
    return outcome.ok()
        ? ObservationView.ok(outcome.detail(), details)
        : ObservationView.failed(failureSymptom, outcome.detail(), details);
  }
}
