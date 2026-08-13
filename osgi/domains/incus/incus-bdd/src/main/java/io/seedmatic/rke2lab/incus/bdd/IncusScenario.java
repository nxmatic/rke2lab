package io.seedmatic.rke2lab.incus.bdd;

import io.seedmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.RunbookCoordinate;

/**
 * The incus domain's SCENARIOS, each with its own sow SOIL — the single source of truth for the
 * routing keys the two incus scenarios answer to. incus hosts N scenarios (like the doctor), so
 * "incus" alone would be ambiguous (it would read as the whole domain); each names its soil
 * explicitly — {@code incus-provision}, {@code incus-reconcile} — and the handler, the amend
 * reflector, and the host's sow crossing all reference THIS enum rather than repeating the literal
 * (the single-source-of-truth discipline: a soil mismatch is a silent mis-route, so it lives in one
 * place).
 *
 * <p>A scenario's soil is BOTH its {@link RunbookCoordinate} (play the scenario) and its {@link
 * AmendCoordinate} (reconcile roles onto its input at the door) — {@code Gardening.sow} derives
 * both from the one soil string, so the enum yields both coordinates from the one {@link #soil}.
 */
public enum IncusScenario {
  PROVISION("incus-provision"),
  RECONCILE("incus-reconcile");

  private final String soil;

  IncusScenario(String soil) {
    this.soil = soil;
  }

  /** The soil name the host sows toward — the routing key both coordinates carry. */
  public String soil() {
    return soil;
  }

  /** The coordinate that PLAYS this scenario in-container. */
  public RunbookCoordinate runbook() {
    return new RunbookCoordinate(soil);
  }

  /** The coordinate that reconciles amendment roles onto this scenario's input at the door. */
  public AmendCoordinate amend() {
    return new AmendCoordinate(soil);
  }
}
