package io.nxmatic.rk2lab.controlplane.bbox;

import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.Resource;
import io.nxmatic.bbox.reconcile.Action;
import io.nxmatic.bbox.reconcile.RowOutcome;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pulumi component resource representing one DHCP reservation on the bbox for one RKE2 node.
 *
 * <p>One {@link BboxReservationResource} is registered per canonical RKE2 row by the parent {@link
 * BboxReservationsResource}, so {@code pulumi preview} / {@code pulumi up} display a row for every
 * cluster/node pair (e.g. {@code bioskop-master}, {@code nikopol-peer1}). Inputs are the immutable
 * identity of the row ({@code cluster}, {@code node}, {@code mac}, {@code ip}, {@code hostname});
 * outputs surface the reconciliation outcome reported by the library's {@code
 * ReservationReconciler} ({@code action}, optional {@code bboxId}, optional previous values,
 * optional failure message).
 *
 * <p>The {@link RowOutcome} comes from {@code java-bbox-api-client}'s reconcile package and doesn't
 * carry rke2lab's {@code (cluster, node)} identity — that's why the child resource takes both a
 * {@link DesiredRow} (for naming and rke2lab-side metadata) and the {@link RowOutcome} (for the
 * bbox-side result).
 */
public final class BboxReservationResource extends ComponentResource {

  private static final String TYPE_TOKEN = "rk2lab:controlplane:BboxReservation";

  private final DesiredRow row;
  private final RowOutcome outcome;

  public BboxReservationResource(DesiredRow row, RowOutcome outcome, Resource parent) {
    super(
        TYPE_TOKEN, row.resourceName(), ComponentResourceOptions.builder().parent(parent).build());

    this.row = row;
    this.outcome = outcome;
    registerOutputs(asOutputs(row, outcome));
  }

  public DesiredRow row() {
    return row;
  }

  public RowOutcome outcome() {
    return outcome;
  }

  public Action action() {
    return outcome.action();
  }

  private static Map<String, Output<?>> asOutputs(DesiredRow row, RowOutcome outcome) {
    final LinkedHashMap<String, Output<?>> out = new LinkedHashMap<>();
    out.put("cluster", Output.of(row.cluster()));
    out.put("node", Output.of(row.node()));
    out.put("mac", Output.of(outcome.mac()));
    out.put("ip", Output.of(outcome.ip()));
    out.put("hostname", Output.of(outcome.hostname()));
    out.put("action", Output.of(outcome.action().name()));
    outcome.bboxId().ifPresent(id -> out.put("bboxId", Output.of(id)));
    outcome.previousIp().ifPresent(ip -> out.put("previousIp", Output.of(ip)));
    outcome
        .previousHostname()
        .ifPresent(hostname -> out.put("previousHostname", Output.of(hostname)));
    outcome.failureMessage().ifPresent(msg -> out.put("failureMessage", Output.of(msg)));
    return out;
  }
}
