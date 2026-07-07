package io.nxmatic.rke2lab.controlplane.bbox;

import com.pulumi.deployment.Deployment;
import io.nxmatic.rke2lab.bbox.port.BboxAction;
import io.nxmatic.rke2lab.bbox.port.BboxReconciler;
import io.nxmatic.rke2lab.bbox.port.BboxReservationRequest;
import io.nxmatic.rke2lab.bbox.port.BboxRowOutcome;
import io.nxmatic.rke2lab.controlplane.SeedLog;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Component responsible for bbox DHCP reservation reconciliation. The bbox contact itself lives in
 * the {@code bbox-edge} OSGi component ({@link BboxReconciler}), resolved from the registry and
 * passed in; this class keeps the host orchestration — reading the secrets, enumerating the desired
 * rows from the blueprint, driving the reconciler, and (for the Pulumi path) projecting the
 * outcomes into the resource graph.
 */
public final class BboxReconcilerComponent {

  private BboxReconcilerComponent() {}

  /**
   * Reconcile bbox reservations for Pulumi engine execution.
   *
   * @return reconciliation result containing the resource URN and summary map, or a skipped result
   */
  public static ReconcileResult reconcileForPulumi(
      BboxReconciler reconciler, java.nio.file.Path worktreePath, boolean failOnError) {
    final boolean dryRun = Deployment.getInstance().isDryRun();
    return buildBboxReservationsResource(reconciler, worktreePath, dryRun, failOnError)
        .map(
            resource -> {
              logBboxSummary(resource);
              return new ReconcileResult(resource.urn(), toBboxSummaryMap(resource));
            })
        .orElseGet(() -> new ReconcileResult("", Map.of("status", "skipped")));
  }

  /**
   * Reconcile bbox reservations for standalone execution (no Pulumi engine).
   *
   * @return summary map of reconciliation results
   */
  public static Map<String, Object> reconcileStandalone(
      BboxReconciler reconciler, java.nio.file.Path worktreePath, boolean failOnError) {
    final BboxSecretsReader.BboxCoordinates coordinates;
    try {
      coordinates = BboxSecretsReader.readBboxCoordinates(worktreePath);
    } catch (RuntimeException ex) {
      return handleSecretsError(ex, failOnError, "standalone: skipping reconciliation");
    }

    final List<BboxReservationRequest> requests = new BlueprintRowEnumerator().rows();
    final List<BboxRowOutcome> outcomes;
    try {
      outcomes = reconciler.reconcile(coordinates.uri(), coordinates.password(), false, requests);
    } catch (RuntimeException ex) {
      if (failOnError) {
        throw ex;
      }
      SeedLog.warn(
          "bbox-reconcile",
          "standalone: skipping reconciliation: bbox call failed (" + ex.getMessage() + ")");
      return Map.of("status", "skipped", "reason", ex.getMessage());
    }

    final EnumMap<BboxAction, Integer> counts = countByAction(outcomes);
    checkFailedActions(counts.getOrDefault(BboxAction.FAILED, 0), failOnError);

    final LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("dryRun", false);
    out.put("desiredCount", requests.size());
    out.put("createdCount", counts.get(BboxAction.CREATED));
    out.put("updatedCount", counts.get(BboxAction.UPDATED));
    out.put("matchingCount", counts.get(BboxAction.MATCHING));
    out.put("failedCount", counts.get(BboxAction.FAILED));
    return out;
  }

  private static Optional<BboxReservationsResource> buildBboxReservationsResource(
      BboxReconciler reconciler,
      java.nio.file.Path worktreePath,
      boolean dryRun,
      boolean failOnError) {
    final BboxSecretsReader.BboxCoordinates coordinates;
    try {
      coordinates = BboxSecretsReader.readBboxCoordinates(worktreePath);
    } catch (RuntimeException ex) {
      if (failOnError) {
        throw ex;
      }
      SeedLog.warn(
          "bbox-reconcile",
          "skipping reconciliation: cannot read bbox coordinates (" + ex.getMessage() + ")");
      return Optional.empty();
    }

    try {
      final List<BboxReservationRequest> requests = new BlueprintRowEnumerator().rows();
      final List<BboxRowOutcome> outcomes =
          reconciler.reconcile(coordinates.uri(), coordinates.password(), dryRun, requests);
      final BboxReservationsResource resource =
          new BboxReservationsResource("bbox-reservations", dryRun, outcomes);
      if (failOnError) {
        checkFailedActions(resource.countOf(BboxAction.FAILED), true);
      }
      return Optional.of(resource);
    } catch (RuntimeException ex) {
      if (failOnError) {
        throw ex;
      }
      SeedLog.warn(
          "bbox-reconcile", "skipping reconciliation: bbox call failed (" + ex.getMessage() + ")");
      return Optional.empty();
    }
  }

  private static EnumMap<BboxAction, Integer> countByAction(List<BboxRowOutcome> outcomes) {
    final EnumMap<BboxAction, Integer> counts = new EnumMap<>(BboxAction.class);
    for (BboxAction action : BboxAction.values()) {
      counts.put(action, 0);
    }
    for (BboxRowOutcome outcome : outcomes) {
      counts.merge(outcome.action(), 1, Integer::sum);
    }
    return counts;
  }

  private static Map<String, Object> handleSecretsError(
      RuntimeException ex, boolean failOnError, String logPrefix) {
    if (failOnError) {
      throw ex;
    }
    SeedLog.warn(
        "bbox-reconcile", logPrefix + ": cannot read bbox coordinates (" + ex.getMessage() + ")");
    return Map.of("status", "skipped", "reason", ex.getMessage());
  }

  private static void checkFailedActions(int failedCount, boolean failOnError) {
    if (failOnError && failedCount > 0) {
      throw new IllegalStateException(
          "bbox reconciliation completed with "
              + failedCount
              + " failed operation(s); set rke2lab:bbox.reconcile.failOnError=false to ignore.");
    }
  }

  private static Map<String, Object> toBboxSummaryMap(BboxReservationsResource resource) {
    final LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("dryRun", resource.dryRun());
    out.put("desiredCount", resource.children().size());
    out.put("createdCount", resource.countOf(BboxAction.CREATED));
    out.put("updatedCount", resource.countOf(BboxAction.UPDATED));
    out.put("matchingCount", resource.countOf(BboxAction.MATCHING));
    out.put("wouldCreateCount", resource.countOf(BboxAction.WOULD_CREATE));
    out.put("wouldUpdateCount", resource.countOf(BboxAction.WOULD_UPDATE));
    out.put("failedCount", resource.countOf(BboxAction.FAILED));
    return out;
  }

  private static void logBboxSummary(BboxReservationsResource resource) {
    SeedLog.info(
        "bbox-reconcile",
        "summary: desired="
            + resource.children().size()
            + " created="
            + resource.countOf(BboxAction.CREATED)
            + " updated="
            + resource.countOf(BboxAction.UPDATED)
            + " matching="
            + resource.countOf(BboxAction.MATCHING)
            + " wouldCreate="
            + resource.countOf(BboxAction.WOULD_CREATE)
            + " wouldUpdate="
            + resource.countOf(BboxAction.WOULD_UPDATE)
            + " failed="
            + resource.countOf(BboxAction.FAILED));
  }

  public record ReconcileResult(Object resourceUrn, Map<String, Object> summaryMap) {}
}
