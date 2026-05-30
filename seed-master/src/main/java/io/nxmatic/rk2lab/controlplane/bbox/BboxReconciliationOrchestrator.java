package io.nxmatic.rk2lab.controlplane.bbox;

import java.nio.file.Path;
import java.util.Map;

/**
 * Orchestrates bbox DHCP reservation reconciliation for both Pulumi and standalone execution modes.
 *
 * <p>Encapsulates the decision logic between Pulumi-managed resources and standalone
 * reconciliation, delegating to {@link BboxReconcilerComponent} for the actual work.
 */
public final class BboxReconciliationOrchestrator {

  private final boolean pulumiMode;

  public BboxReconciliationOrchestrator(boolean pulumiMode) {
    this.pulumiMode = pulumiMode;
  }

  /**
   * Reconciles bbox reservations based on execution mode.
   *
   * @param worktreePath path to git worktree containing bbox secrets
   * @param failOnError whether to fail on reconciliation errors
   * @return reconciliation result with URN and summary
   */
  public ReconciliationResult reconcile(Path worktreePath, boolean failOnError) {
    if (pulumiMode) {
      return reconcileForPulumi(worktreePath, failOnError);
    } else {
      return reconcileStandalone(worktreePath, failOnError);
    }
  }

  private ReconciliationResult reconcileForPulumi(Path worktreePath, boolean failOnError) {
    final BboxReconcilerComponent.ReconcileResult result =
        BboxReconcilerComponent.reconcileForPulumi(worktreePath, failOnError, null);
    return new ReconciliationResult(result.resourceUrn(), result.summaryMap());
  }

  private ReconciliationResult reconcileStandalone(Path worktreePath, boolean failOnError) {
    final Map<String, Object> summaryMap =
        BboxReconcilerComponent.reconcileStandalone(worktreePath, failOnError);
    return new ReconciliationResult("", summaryMap);
  }

  /** Result of bbox reconciliation containing URN and summary map. */
  public record ReconciliationResult(Object resourceUrn, Map<String, Object> summaryMap) {}
}
