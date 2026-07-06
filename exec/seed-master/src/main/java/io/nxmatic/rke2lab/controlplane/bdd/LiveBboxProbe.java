package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator.ReconciliationResult;

/**
 * The live bbox probe — transposes {@code BboxTopic.reconcileReservations}: delegates to the
 * host-fact {@code BboxReconciliationOrchestrator}, which reads the real bbox secrets from the
 * worktree and reconciles (Pulumi-managed or standalone, per its own mode).
 */
public final class LiveBboxProbe implements BboxProbe {

  @Override
  public ReconciliationResult reconcile(HostFacts hostFacts) {
    return hostFacts
        .bboxOrchestrator()
        .reconcile(hostFacts.config().localWorktreePath(), hostFacts.options().bboxFailOnError());
  }
}
