package io.nxmatic.rke2lab.controlplane.bbox;

import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.Resource;
import io.nxmatic.rke2lab.bbox.port.BboxAction;
import io.nxmatic.rke2lab.bbox.port.BboxRowOutcome;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pulumi component resource representing one DHCP reservation on the bbox for one RKE2 node.
 *
 * <p>One {@link BboxReservationResource} is registered per canonical RKE2 row by the parent {@link
 * BboxReservationsResource}, so {@code pulumi preview} / {@code pulumi up} display a row for every
 * cluster/node pair (e.g. {@code bioskop-master}, {@code nikopol-peer1}). Its inputs and outputs
 * come from the flat {@link BboxRowOutcome} the bbox-edge produced — the reconciliation ran before
 * this resource is built, so the resource only mirrors the outcome into Pulumi outputs, no bbox
 * contact.
 */
public final class BboxReservationResource extends ComponentResource {

  private static final String TYPE_TOKEN = "rke2lab:controlplane:BboxReservation";

  private final BboxRowOutcome outcome;

  public BboxReservationResource(BboxRowOutcome outcome, Resource parent) {
    super(
        TYPE_TOKEN,
        outcome.cluster() + "-" + outcome.node(),
        ComponentResourceOptions.builder().parent(parent).build());

    this.outcome = outcome;
    registerOutputs(asOutputs(outcome));
  }

  public BboxRowOutcome outcome() {
    return outcome;
  }

  public BboxAction action() {
    return outcome.action();
  }

  private static Map<String, Output<?>> asOutputs(BboxRowOutcome outcome) {
    final LinkedHashMap<String, Output<?>> out = new LinkedHashMap<>();
    out.put("cluster", Output.of(outcome.cluster()));
    out.put("node", Output.of(outcome.node()));
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
