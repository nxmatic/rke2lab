package io.nxmatic.rke2lab.seed.bdd;

/**
 * The entry gate a run must pass before it has the right to sow — enforced by the exec, abstracted
 * here. seed-bdd is foundation: it names the GATE, not what it checks (a clean git worktree, a
 * coherent flake lock, a manifests-update gate — those live host-side in the exec's policy). The
 * exec hands in an implementation (e.g. delegating to its {@code EntryGatePolicyEnforcer}); the
 * preflight stage only invokes it.
 *
 * <p>{@link #enforce} throws when a gate fails, so the failing step is marked FAILED in the runbook
 * and the run stops before touching anything — "we do not sow on unclean ground".
 */
@FunctionalInterface
public interface PreflightGate {

  /** Enforce every entry gate; throw when one fails (the message names which). */
  void enforce();
}
