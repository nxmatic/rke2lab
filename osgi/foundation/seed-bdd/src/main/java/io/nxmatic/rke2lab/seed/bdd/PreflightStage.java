package io.nxmatic.rke2lab.seed.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ScenarioState;

/**
 * The runbook's entry gate, as a step: enforce the run's right to sow through an injected {@link
 * PreflightGate} (the exec supplies what it checks — clean worktree, flake lock, …). Pure host, no
 * crossing: it consults no domain, it only guards the ground before the first sowing.
 */
public class PreflightStage extends Stage<PreflightStage> {

  @ScenarioState private PreflightGate gate;

  /** Hand in the exec's entry gate. */
  @Hidden
  public PreflightStage gatedBy(PreflightGate gate) {
    this.gate = gate;
    return self();
  }

  public PreflightStage the_entry_gates_are_enforced() {
    gate.enforce();
    return self();
  }
}
