package io.nxmatic.rke2lab.worktree;

import java.nio.file.Path;

/**
 * The worktree the seed cultivates, as ONE OSGi service — it KNOWS its own git facts. The seed
 * process runs inside its worktree, so the component locates its root once (walking up to the
 * {@code .git}) and answers the facts that root carries: its {@link #provenance()} (HEAD sha +
 * dirty) and its {@link #workingState()} (clean, and which paths are uncommitted). The one place
 * the "is the worktree clean?" and "what did we provision from?" knowledge lives.
 *
 * <p>Governance: the interface is jgit-FREE — only JDK types and the worktree records cross it.
 * jgit lives behind the implementation ({@code worktree-core}'s {@code JgitWorktree}) and is never
 * exposed, so a consumer couples to the worktree's facts, not to a git library. OSGi consumers
 * {@code @Reference} it directly (the incus scion); the flat host reads the same facts through the
 * cellar — the worktree soil harvests a {@link WorktreeFacts} at the {@link WorktreeCoordinate},
 * which the host fetches back.
 */
public interface Worktree {

  /** The worktree root — absolute, normalised. The anchor every provisioning path derives from. */
  Path root();

  /** The HEAD provenance of the worktree: the commit sha it sits on, and whether it is dirty. */
  Provenance provenance();

  /** The working state: whether the tree is clean, and the uncommitted paths when it is not. */
  WorkingState workingState();

  /**
   * Whether the latest commit's flake locks are coherent: {@code false} when a {@code flake.nix}
   * {@code inputs} block changed in {@code HEAD} without a matching {@code flake.lock} change — the
   * incoherence a clean worktree does NOT catch. jgit stays sealed behind the implementation.
   */
  boolean flakeLockCoherent();
}
