package io.nxmatic.rke2lab.worktree;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A prepared linked worktree — the handle {@link RenderedBranch#prepare} returns. It is the
 * transient checkout a producer materialises a rendered tree into, then seals with a SIGNED commit
 * and force-pushes to the origin the repository already knows. {@link AutoCloseable}: {@link
 * #close()} runs {@code git worktree remove --force}, so a try-with-resources leaves no linked
 * worktree behind whatever the render did.
 *
 * <p>Its {@link #stage}/{@link #commit} mirror {@link Worktree}'s verbs, but against THIS
 * worktree's path and branch, not the seed's own — the same signing mechanism (the caller's OpenSSH
 * key, git SSHSIG under the {@code git} namespace), the same bot {@link GitIdentity} discipline.
 * jgit and {@code git worktree} stay sealed behind the implementation; only JDK types cross.
 */
public interface LinkedWorktree extends AutoCloseable {

  /** The worktree root the render materialises into — absolute, normalised. */
  Path path();

  /**
   * The branch this worktree has checked out (e.g. the rendered {@code manifests/<host>-<role>}).
   */
  String branch();

  /**
   * Stage the given paths for the next commit — additions/modifications for paths that exist,
   * removals for paths that no longer do. Each path may be absolute or resolved against {@link
   * #path()}. For staging a whole rendered tree (including files a re-render dropped), prefer
   * {@link #stageAll()}.
   */
  void stage(List<Path> paths);

  /**
   * Stage the ENTIRE worktree — additions, modifications, AND deletions ({@code git add -A}). The
   * render verb: a re-render rewrites the tree in place, and a manifest removed since the previous
   * render must be staged as a deletion so the commit reflects the tree exactly.
   */
  void stageAll();

  /**
   * Commit the staged tree with {@code message}, authored AND committed as {@code identity} (a bot
   * identity — a rendered branch is machine-made, attributable to the tool, never to the ambient
   * {@code user.name}). {@code sshSigningKey} is the caller's OpenSSH PRIVATE key the commit is
   * SSH-signed with (git SSHSIG, {@code git} namespace); {@link Optional#empty()} commits unsigned.
   * The commit accretes on the branch's tip (a null-commit base + one commit per render). Returns
   * the new commit sha. Local only — {@link #push} is the separate, credentialed act.
   */
  String commit(String message, GitIdentity identity, Optional<String> sshSigningKey);

  /**
   * Push this worktree's {@link #branch()} to the repository's {@code origin} over HTTPS,
   * authenticating as {@code x-access-token} with {@code token} (a short-lived GitHub token the
   * caller revealed from the sealed cellar). A FAST-FORWARD push, not a force: renders accrete on
   * the branch's stable null-commit base, so the remote advances, never rewrites — a divergence
   * fails loudly rather than being clobbered. The credential is held in memory for the single push,
   * never written to a config or a command line.
   */
  void push(String token);

  /** Remove this linked worktree ({@code git worktree remove --force}). Never throws on absence. */
  @Override
  void close();
}
