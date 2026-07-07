package io.nxmatic.rke2lab.controlplane.bbox;

import io.nxmatic.rke2lab.bbox.port.BboxReconciler;
import java.nio.file.Path;
import java.util.Map;

/**
 * Orchestrates bbox DHCP reservation reconciliation for both Pulumi and standalone execution modes.
 *
 * <p>Encapsulates the decision logic between Pulumi-managed resources and standalone
 * reconciliation, delegating to {@link BboxReconcilerComponent}. The actual bbox contact is the
 * {@link BboxReconciler} OSGi edge, resolved from the registry by the caller and passed to {@link
 * #reconcile} — this orchestrator holds no contact of its own.
 */
public final class BboxReconciliationOrchestrator {

  private final boolean pulumiMode;

  public BboxReconciliationOrchestrator(boolean pulumiMode) {
    this.pulumiMode = pulumiMode;
  }

  /**
   * Reconciles bbox reservations based on execution mode, driving the injected {@link
   * BboxReconciler} edge.
   *
   * @param reconciler the bbox contact resolved from the OSGi registry
   * @param worktreePath path to git worktree containing bbox secrets
   * @param failOnError whether to fail on reconciliation errors
   * @return reconciliation result with URN and summary
   */
  public ReconciliationResult reconcile(
      BboxReconciler reconciler, Path worktreePath, boolean failOnError) {
    if (pulumiMode) {
      final BboxReconcilerComponent.ReconcileResult result =
          BboxReconcilerComponent.reconcileForPulumi(reconciler, worktreePath, failOnError);
      return new ReconciliationResult(result.resourceUrn(), result.summaryMap());
    }
    final Map<String, Object> summaryMap =
        BboxReconcilerComponent.reconcileStandalone(reconciler, worktreePath, failOnError);
    return new ReconciliationResult("", summaryMap);
  }

  /** Result of bbox reconciliation containing URN and summary map. */
  public record ReconciliationResult(Object resourceUrn, Map<String, Object> summaryMap) {}
}
