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
   * #path()}. The rendered tree is staged wholesale before it is sealed.
   */
  void stage(List<Path> paths);

  /**
   * Commit the staged tree with {@code message}, authored AND committed as {@code identity} (a bot
   * identity — a rendered branch is machine-made, attributable to the tool, never to the ambient
   * {@code user.name}). {@code sshSigningKey} is the caller's OpenSSH PRIVATE key the commit is
   * SSH-signed with (git SSHSIG, {@code git} namespace); {@link Optional#empty()} commits unsigned.
   * Returns the new commit sha. Local only — {@link #forcePush} is the separate, credentialed act.
   */
  String commit(String message, GitIdentity identity, Optional<String> sshSigningKey);

  /**
   * Force-push this worktree's {@link #branch()} to the repository's {@code origin} over HTTPS,
   * authenticating as {@code x-access-token} with {@code token} (a short-lived GitHub token the
   * caller revealed from the sealed cellar). A rendered branch is desired-state, so the push is a
   * forced ref update — the remote branch is made to match the freshly-committed HEAD exactly. The
   * credential is held in memory for the single push, never written to a config or a command line.
   */
  void forcePush(String token);

  /** Remove this linked worktree ({@code git worktree remove --force}). Never throws on absence. */
  @Override
  void close();
}
