package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.bbox.port.BboxReconciler;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator.ReconciliationResult;

/**
 * The live bbox probe — delegates to the host-fact {@code BboxReconciliationOrchestrator}, driving
 * the {@link BboxReconciler} edge (resolved from the registry by the stage) over the real bbox
 * secrets read from the worktree (Pulumi-managed or standalone, per the orchestrator's mode).
 */
public final class LiveBboxProbe implements BboxProbe {

  @Override
  public ReconciliationResult reconcile(HostFacts hostFacts, BboxReconciler reconciler) {
    return hostFacts
        .bboxOrchestrator()
        .reconcile(
            reconciler,
            hostFacts.config().localWorktreePath(),
            hostFacts.options().bboxFailOnError());
  }
}
