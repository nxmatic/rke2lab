package io.seedmatic.rke2lab.worktree;

import io.seedmatic.rke2lab.seed.broker.port.Amendment;
import io.seedmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.Optional;

/**
 * The wire contract for the worktree {@code runbook} trigger — the activation payload the host
 * supplies to play the worktree soil. The INPUT twin of the harvested {@code WorktreeFacts}, it
 * carries one {@link Amendment}: {@link #gate}, the {@link Amendment#FACET} the host contributes
 * AMBIENT (a {@link io.seedmatic.rke2lab.seed.broker.port.AmendmentContributor} the assembler
 * merges at the {@code worktree} amend door) — the entry-gate {@link GatePolicy} (clean-worktree
 * requirement + tolerated paths). A FACET, not a per-consult ROW: the policy never changes across a
 * run. {@link Optional}: an EMPTY amendment is the honest "unamended" (an offline scenario or a
 * bare {@code shape} probe) — the soil harvests but enforces no gate.
 *
 * <p>The host names NO worktree type: it contributes the gate as opaque JSON on the {@code FACET}
 * role, decoded here OSGi-side. So the worktree domain owns the gate MECHANISM ({@code
 * GatePolicy.enforce}), the host owns the POLICY DATA — no manifests/host vocabulary crosses.
 */
@SeedContract("runbook")
public record WorktreeRunbookInput(@Amendment(Amendment.FACET) Optional<GatePolicy> gate) {

  /** The default trigger — UNAMENDED (harvest only, no gate enforced). */
  public static WorktreeRunbookInput defaults() {
    return new WorktreeRunbookInput(Optional.empty());
  }
}
