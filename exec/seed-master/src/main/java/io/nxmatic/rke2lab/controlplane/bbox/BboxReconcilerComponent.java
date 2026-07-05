package io.nxmatic.rke2lab.controlplane.bbox;

import com.pulumi.deployment.Deployment;
import io.nxmatic.bbox.api.BboxApiClient;
import io.nxmatic.bbox.reconcile.Action;
import io.nxmatic.bbox.reconcile.ReservationReconciler;
import io.nxmatic.bbox.reconcile.ReservationReconciler.Mode;
import io.nxmatic.rke2lab.controlplane.SeedLog;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Component responsible for bbox DHCP reservation reconciliation. */
public final class BboxReconcilerComponent {

  private BboxReconcilerComponent() {}

  /**
   * Reconcile bbox reservations for Pulumi engine execution.
   *
   * @return reconciliation result containing the resource URN and summary map, or null if skipped
   */
  public static ReconcileResult reconcileForPulumi(Path worktreePath, boolean failOnError) {
    final boolean dryRun = Deployment.getInstance().isDryRun();
    return buildBboxReservationsResource(worktreePath, dryRun, failOnError)
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
  public static Map<String, Object> reconcileStandalone(Path worktreePath, boolean failOnError) {
    final BboxSecretsReader.BboxCoordinates coordinates;
    try {
      coordinates = BboxSecretsReader.readBboxCoordinates(worktreePath);
    } catch (RuntimeException ex) {
      return handleSecretsError(ex, failOnError, "standalone: skipping reconciliation");
    }

    final List<DesiredRow> rows = new BlueprintRowEnumerator().rows();
    final EnumMap<Action, Integer> counts = new EnumMap<>(Action.class);
    for (Action action : Action.values()) {
      counts.put(action, 0);
    }

    try (BboxApiClient client = BboxApiClient.open(coordinates.uri(), coordinates.password())) {
      final ReservationReconciler reconciler = new ReservationReconciler(client);
      for (DesiredRow row : rows) {
        final Action action = reconciler.apply(row.reservation(), Mode.APPLY).action();
        counts.merge(action, 1, (a, b) -> a + b);
      }
    } catch (Exception ex) {
      if (failOnError) {
        if (ex instanceof RuntimeException re) {
          throw re;
        }
        throw new IllegalStateException(
            "bbox standalone reconciliation failed: " + ex.getMessage(), ex);
      }
      SeedLog.warn(
          "bbox-reconcile",
          "standalone: skipping reconciliation: bbox call failed (" + ex.getMessage() + ")");
      return Map.of("status", "skipped", "reason", ex.getMessage());
    }

    checkFailedActions(counts.getOrDefault(Action.FAILED, 0), failOnError);

    final LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("dryRun", false);
    out.put("desiredCount", rows.size());
    out.put("createdCount", counts.get(Action.CREATED));
    out.put("updatedCount", counts.get(Action.UPDATED));
    out.put("matchingCount", counts.get(Action.MATCHING));
    out.put("failedCount", counts.get(Action.FAILED));
    return out;
  }

  private static Optional<BboxReservationsResource> buildBboxReservationsResource(
      Path worktreePath, boolean dryRun, boolean failOnError) {
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
      final BboxReservationsResource resource =
          new BboxReservationsResource(
              "bbox-reservations", coordinates.uri(), coordinates.password(), dryRun);
      if (failOnError) {
        checkFailedActions(resource.countOf(Action.FAILED), true);
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
    out.put("createdCount", resource.countOf(Action.CREATED));
    out.put("updatedCount", resource.countOf(Action.UPDATED));
    out.put("matchingCount", resource.countOf(Action.MATCHING));
    out.put("wouldCreateCount", resource.countOf(Action.WOULD_CREATE));
    out.put("wouldUpdateCount", resource.countOf(Action.WOULD_UPDATE));
    out.put("failedCount", resource.countOf(Action.FAILED));
    return out;
  }

  private static void logBboxSummary(BboxReservationsResource resource) {
    SeedLog.info(
        "bbox-reconcile",
        "summary: desired="
            + resource.children().size()
            + " created="
            + resource.countOf(Action.CREATED)
            + " updated="
            + resource.countOf(Action.UPDATED)
            + " matching="
            + resource.countOf(Action.MATCHING)
            + " wouldCreate="
            + resource.countOf(Action.WOULD_CREATE)
            + " wouldUpdate="
            + resource.countOf(Action.WOULD_UPDATE)
            + " failed="
            + resource.countOf(Action.FAILED));
  }

  public record ReconcileResult(Object resourceUrn, Map<String, Object> summaryMap) {}
}
