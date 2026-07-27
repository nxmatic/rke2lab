package io.nxmatic.rke2lab.worktree;

import java.util.List;

/**
 * The clean-git-worktree entry gate's POLICY — the caller's judgement, injected into the worktree
 * crossing as the {@code FACET} amendment. The worktree owns the FACT (which paths are uncommitted,
 * carried by {@link WorkingState}); this policy owns the JUDGEMENT (whether a clean worktree is
 * required, and which paths are tolerated when it is). The worktree domain enforces its OWN ground
 * against a caller-supplied policy — it hardcodes no path, so no manifests/host vocabulary leaks
 * in.
 *
 * <p>Two gates, each with its own toggle. The CLEAN-WORKTREE gate refuses to sow when the worktree
 * carries uncommitted changes OUTSIDE {@link #toleratedPaths} (Stage A embeds the worktree tree
 * into the seed image; an uncommitted change would provision material with no committed provenance)
 * — a path is tolerated when it equals a tolerated entry or is nested under it. The FLAKE-LOCK gate
 * (armed by {@link #flakeLockRequired}, default OFF) refuses when the latest commit changed a
 * {@code flake.nix} inputs block without its {@code flake.lock} — the incoherence a clean worktree
 * does not catch, harvested as {@link WorktreeFacts#flakeLockCoherent()}.
 */
public record GatePolicy(
    boolean cleanWorktreeRequired, List<String> toleratedPaths, boolean flakeLockRequired) {

  /** The default: nothing required, nothing tolerated (an unamended crossing). */
  public static GatePolicy defaults() {
    return new GatePolicy(false, List.of(), false);
  }

  /**
   * Enforce this policy against the harvested {@link WorktreeFacts}. Each sub-gate is a no-op
   * unless its toggle is armed and its fact is violated; a violation throws (the failing step is
   * marked FAILED in the runbook and the run stops before any effectful sow — we do not sow on
   * unclean ground).
   */
  public void enforce(WorktreeFacts facts) {
    enforceFlakeLockCoherence(facts.flakeLockCoherent());
    enforceCleanWorktree(facts.workingState());
  }

  private void enforceCleanWorktree(WorkingState workingState) {
    if (!cleanWorktreeRequired || workingState.clean()) {
      return;
    }
    final List<String> offending =
        workingState.uncommittedPaths().stream().filter(path -> !isTolerated(path)).toList();
    if (offending.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        "Entry-gate policy failed (clean-git-worktree): provisioning requires a clean worktree (it"
            + " embeds the worktree tree into the seed image). Commit or revert the changes below,"
            + " or add their paths to the run's tolerated set (entryGate.cleanWorktree.tolerated)."
            + "\nUncommitted paths:\n- "
            + String.join("\n- ", offending));
  }

  private void enforceFlakeLockCoherence(boolean flakeLockCoherent) {
    if (!flakeLockRequired || flakeLockCoherent) {
      return;
    }
    throw new IllegalStateException(
        "Entry-gate policy failed (flake-lock-coherence): the latest commit changed a flake.nix"
            + " inputs block without updating its flake.lock. Update the locks and commit again, or"
            + " disable the gate (entryGate.flakeLock.required).");
  }

  private boolean isTolerated(String path) {
    if (path == null) {
      return false;
    }
    return toleratedPaths.stream()
        .anyMatch(tolerated -> path.equals(tolerated) || path.startsWith(prefixOf(tolerated)));
  }

  private String prefixOf(String tolerated) {
    return tolerated.endsWith("/") ? tolerated : tolerated + "/";
  }
}
