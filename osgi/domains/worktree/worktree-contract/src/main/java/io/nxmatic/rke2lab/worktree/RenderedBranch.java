package io.nxmatic.rke2lab.worktree;

import java.nio.file.Path;

/**
 * Fabricates a LINKED git worktree of a branch — the delivery channel for a rendered tree. Where
 * {@link Worktree} is "the worktree the seed IS" (it self-locates and reports its own facts),
 * {@code RenderedBranch} is "a worktree the seed MAKES": a transient checkout of a target branch at
 * a caller-chosen path, into which a producer materialises a rendered tree, then commits and
 * force-pushes it. A rendered branch is ephemeral desired-state, not history — a force-push is
 * expected and correct (see docs/architecture/cluster-api/manifests-rendered-branches.adoc).
 *
 * <p>Domain-neutral, exactly like {@link Worktree} and {@link GatePolicy}: it names no {@code
 * manifests/<host>-<role>} convention and no {@code .local.d} path. The caller supplies the branch
 * ref and the worktree path; the worktree domain owns only the git MECHANISM (add / commit / push /
 * remove), so no consumer vocabulary leaks in. jgit and {@code git worktree} stay sealed behind the
 * implementation — only JDK types cross this interface.
 */
public interface RenderedBranch {

  /**
   * Prepare a linked worktree checked out at {@code worktreePath} on {@code branch}, (re)created at
   * {@code base}. Idempotent across re-runs: an existing worktree at {@code worktreePath} is
   * removed first, then {@code branch} is reset to {@code base} and checked out fresh — a rendered
   * branch is regenerated, never accreted. {@code base} is any git ref the repository can resolve
   * (a sha, or a remote-tracking ref such as {@code origin/main}). Returns a handle the caller
   * materialises into, commits, force-pushes, and {@link LinkedWorktree#close() removes}. {@code
   * git worktree} is shelled (jgit exposes no worktree porcelain), sealed behind the
   * implementation.
   */
  LinkedWorktree prepare(Path worktreePath, String branch, String base);
}
