package io.nxmatic.rke2lab.controlplane.bbox;

import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import io.nxmatic.rke2lab.bbox.port.BboxAction;
import io.nxmatic.rke2lab.bbox.port.BboxRowOutcome;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parent component resource for bbox DHCP reservations.
 *
 * <p>The reconciliation ran already — through the {@code BboxReconciler} OSGi edge, host-side — so
 * this resource takes the flat {@link BboxRowOutcome}s and registers one {@link
 * BboxReservationResource} child per row. {@code pulumi preview} / {@code pulumi up} display the
 * parent followed by a child for every {@code (cluster, node)} pair, so blueprint-driven changes
 * show as per-row diffs. Aggregate counts (created / updated / matching / failed plus {@code
 * dryRun}) are registered as outputs on the parent for downstream resources.
 */
public final class BboxReservationsResource extends ComponentResource {

  private static final String TYPE_TOKEN = "rke2lab:controlplane:BboxReservations";

  private final boolean dryRun;
  private final List<BboxReservationResource> children;
  private final Map<BboxAction, Integer> actionCounts;

  /**
   * @param name parent resource name (typically {@code "bbox-reservations"}).
   * @param dryRun whether the outcomes were produced in dry-run (no writes — matches {@code pulumi
   *     preview}).
   * @param outcomes the flat reconciliation outcomes, one per canonical row, from the bbox edge.
   */
  public BboxReservationsResource(String name, boolean dryRun, List<BboxRowOutcome> outcomes) {
    super(TYPE_TOKEN, name, ComponentResourceOptions.builder().build());

    this.dryRun = dryRun;
    final List<BboxReservationResource> registered = new ArrayList<>(outcomes.size());
    final EnumMap<BboxAction, Integer> counts = new EnumMap<>(BboxAction.class);
    for (BboxAction action : BboxAction.values()) {
      counts.put(action, 0);
    }

    for (BboxRowOutcome outcome : outcomes) {
      counts.merge(outcome.action(), 1, Integer::sum);
      registered.add(new BboxReservationResource(outcome, this));
    }

    this.children = List.copyOf(registered);
    this.actionCounts = new EnumMap<>(counts);
    registerOutputs(buildOutputs());
  }

  public List<BboxReservationResource> children() {
    return children;
  }

  /** Aggregate count for a given action across all rows. */
  public int countOf(BboxAction action) {
    return actionCounts.getOrDefault(action, 0);
  }

  public boolean dryRun() {
    return dryRun;
  }

  private Map<String, Output<?>> buildOutputs() {
    final LinkedHashMap<String, Output<?>> out = new LinkedHashMap<>();
    out.put("dryRun", Output.of(dryRun));
    out.put("desiredCount", Output.of(children.size()));
    out.put("createdCount", Output.of(countOf(BboxAction.CREATED)));
    out.put("updatedCount", Output.of(countOf(BboxAction.UPDATED)));
    out.put("matchingCount", Output.of(countOf(BboxAction.MATCHING)));
    out.put("wouldCreateCount", Output.of(countOf(BboxAction.WOULD_CREATE)));
    out.put("wouldUpdateCount", Output.of(countOf(BboxAction.WOULD_UPDATE)));
    out.put("failedCount", Output.of(countOf(BboxAction.FAILED)));
    return out;
  }
}
