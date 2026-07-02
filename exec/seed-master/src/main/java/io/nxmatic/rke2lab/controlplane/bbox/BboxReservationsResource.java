package io.nxmatic.rke2lab.controlplane.bbox;

import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import io.nxmatic.bbox.api.BboxApiClient;
import io.nxmatic.bbox.reconcile.Action;
import io.nxmatic.bbox.reconcile.ReservationReconciler;
import io.nxmatic.bbox.reconcile.ReservationReconciler.Mode;
import io.nxmatic.bbox.reconcile.RowOutcome;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parent component resource for bbox DHCP reservations.
 *
 * <p>Opens a single bbox session via {@link BboxApiClient}, hands it to the library's {@link
 * ReservationReconciler} (which fetches the reservation table once into its snapshot), then
 * registers one {@link BboxReservationResource} child per canonical RKE2 row. {@code pulumi
 * preview} / {@code pulumi up} display the parent followed by a child for every {@code (cluster,
 * node)} pair, so blueprint-driven changes show as per-row diffs.
 *
 * <p>Aggregate counts (created / updated / matching / failed plus {@code dryRun}) are registered as
 * outputs on the parent for downstream resources.
 */
public final class BboxReservationsResource extends ComponentResource {

  private static final String TYPE_TOKEN = "rke2lab:controlplane:BboxReservations";

  private final boolean dryRun;
  private final List<BboxReservationResource> children;
  private final Map<Action, Integer> actionCounts;

  /**
   * @param name parent resource name (typically {@code "bbox-reservations"}).
   * @param bboxBaseUri base URI for the bbox API (from {@code .secrets:lan.bbox.uri}).
   * @param adminPassword admin password (from {@code .secrets:lan.bbox.password}).
   * @param dryRun when {@code true}, no writes are issued — matches {@code pulumi preview}.
   */
  public BboxReservationsResource(
      String name, URI bboxBaseUri, String adminPassword, boolean dryRun) {
    super(TYPE_TOKEN, name, ComponentResourceOptions.builder().build());

    this.dryRun = dryRun;
    final List<DesiredRow> rows = new BlueprintRowEnumerator().rows();
    final List<BboxReservationResource> registered = new ArrayList<>(rows.size());
    final EnumMap<Action, Integer> counts = new EnumMap<>(Action.class);
    for (Action action : Action.values()) {
      counts.put(action, 0);
    }

    final Mode mode = dryRun ? Mode.DRY_RUN : Mode.APPLY;
    try (BboxApiClient client = openClient(bboxBaseUri, adminPassword)) {
      final ReservationReconciler reconciler = new ReservationReconciler(client);
      for (DesiredRow row : rows) {
        final RowOutcome outcome = reconciler.apply(row.reservation(), mode);
        counts.merge(outcome.action(), 1, (a, b) -> a + b);
        registered.add(new BboxReservationResource(row, outcome, this));
      }
    }

    this.children = List.copyOf(registered);
    this.actionCounts = new EnumMap<>(counts);
    registerOutputs(buildOutputs());
  }

  public List<BboxReservationResource> children() {
    return children;
  }

  /** Aggregate count for a given action across all rows. */
  public int countOf(Action action) {
    return actionCounts.getOrDefault(action, 0);
  }

  public boolean dryRun() {
    return dryRun;
  }

  private static BboxApiClient openClient(URI bboxBaseUri, String adminPassword) {
    try {
      return BboxApiClient.open(bboxBaseUri, adminPassword);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to open bbox session: " + ex.getMessage(), ex);
    }
  }

  private Map<String, Output<?>> buildOutputs() {
    final LinkedHashMap<String, Output<?>> out = new LinkedHashMap<>();
    out.put("dryRun", Output.of(dryRun));
    out.put("desiredCount", Output.of(children.size()));
    out.put("createdCount", Output.of(countOf(Action.CREATED)));
    out.put("updatedCount", Output.of(countOf(Action.UPDATED)));
    out.put("matchingCount", Output.of(countOf(Action.MATCHING)));
    out.put("wouldCreateCount", Output.of(countOf(Action.WOULD_CREATE)));
    out.put("wouldUpdateCount", Output.of(countOf(Action.WOULD_UPDATE)));
    out.put("failedCount", Output.of(countOf(Action.FAILED)));
    return out;
  }
}
