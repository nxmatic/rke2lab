package io.nxmatic.rke2lab.host.port;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The host-access seam for git working-tree facts: reads a live jgit repository at a worktree root
 * and returns FLAT facts — never a jgit type crosses the seam. The {@code host-edge} provides it by
 * opening the repository (jgit {@code FileRepositoryBuilder}); the caller reads the flat {@link
 * GitFacts} and reasons over it (build-id derivation, clean-worktree gate).
 *
 * <p>The grain is stateless and single-shot: one call reads the tree as it is NOW. The reader owns
 * no policy — whether the dirty flag matters, how a build id is formatted, what a dirty tree blocks
 * are the caller's concerns.
 */
public interface GitFactsReader {

  /**
   * The flat facts about a working tree at a point in time. {@code commit} is the abbreviated HEAD
   * (8 chars); {@code fullCommit} the full SHA; {@code branch} the current branch (or the SHA if
   * detached); {@code dirty} whether the tree has uncommitted changes; {@code shortMessage}, {@code
   * author}, {@code committedAt} describe the HEAD commit. All fields are plain values.
   */
  record GitFacts(
      String commit,
      String fullCommit,
      String branch,
      boolean dirty,
      String shortMessage,
      String author,
      String committedAt) {}

  /**
   * The facts for the repository whose working tree is {@code worktreeRoot}, or empty when the path
   * is not a git repository or HEAD is unresolved (a fresh repo with no commits). {@code
   * dirtyCheckEnabled} lets a caller skip the (costly) working-tree status scan and report {@code
   * dirty=false} — the heavy-refactor escape the host code already carries.
   */
  Optional<GitFacts> read(Path worktreeRoot, boolean dirtyCheckEnabled);
}
