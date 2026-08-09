package io.nxmatic.rke2lab.worktree;

/**
 * The author/committer a {@link Worktree#commit(String, GitIdentity)} stamps onto an automated
 * commit — a bot identity, so a machine-made commit is attributable to the tool, not to the ambient
 * {@code user.name}/{@code user.email} of whoever happened to run it. Domain-neutral: the worktree
 * carries no specific identity, the caller supplies one (e.g. the version bumper's rke2lab bot).
 *
 * @param name the git author/committer name (e.g. {@code rke2lab})
 * @param email the git author/committer email (e.g. {@code rke2lab-bot@…})
 */
public record GitIdentity(String name, String email) {

  public GitIdentity {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("git identity name is required");
    }
    if (email == null || email.isBlank()) {
      throw new IllegalArgumentException("git identity email is required");
    }
  }
}
