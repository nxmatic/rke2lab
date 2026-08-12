package io.nxmatic.rke2lab.worktree.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The {@code git worktree} porcelain for ONE repository — an instance bound to that repo's
 * directory, shelled because jgit exposes no worktree porcelain (verified against 7.7.x: it can
 * OPEN a linked worktree but not CREATE the {@code .git/worktrees/<name>} administrative files).
 * The single spot that runs {@code git} as a subprocess in this domain, the way {@link
 * SshCommitSigner} is the single spot that shells {@code ssh-keygen}. Constructed by {@link
 * JgitRenderedBranch} from the seed's root and threaded into the {@link JgitLinkedWorktree} it
 * makes, so add (at prepare) and remove (at close) run against the same repo.
 */
final class GitCli {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final Path repoDir;

  GitCli(Path repoDir) {
    this.repoDir = repoDir;
  }

  /**
   * Add a linked worktree at {@code worktreePath} on {@code branch}, on a STABLE null-commit base.
   * The first time a {@code branch} is seen it is seeded with an empty root commit — a shared base
   * Flux can point at and every later render commits ON TOP of (accretion, not orphan-per-render).
   * On a re-run the existing branch is reused: its tip is checked out (the accretion parent) and
   * the working tree emptied, so the fresh render starts clean and a manifest removed between
   * renders is staged as a deletion. Idempotent: any prior worktree at the path is removed and
   * pruned first.
   */
  void worktreeAdd(Path worktreePath, String branch) {
    final String path = worktreePath.toString();
    run(false, "worktree", "remove", "--force", path);
    run(false, "worktree", "prune");
    if (branchExists(branch)) {
      run(true, "worktree", "add", path, branch);
      // Empty the working tree (keep HEAD at the tip) so the render starts from a clean slate.
      run(false, "-C", path, "rm", "-rf", "--quiet", "--ignore-unmatch", ".");
    } else {
      run(true, "worktree", "add", "--orphan", "-b", branch, path);
      // The null commit — a stable, shared base. Its metadata is immaterial (the render commits on
      // top carry the real bot identity + SSH signature); a neutral identity keeps it
      // deterministic.
      run(
          true,
          "-C",
          path,
          "-c",
          "user.name=rke2lab",
          "-c",
          "user.email=rke2lab@localhost",
          "commit",
          "--allow-empty",
          "--no-gpg-sign",
          "-m",
          "init " + branch);
    }
  }

  /**
   * Stage EVERYTHING in the worktree — additions, modifications, AND deletions ({@code add -A}).
   */
  void addAll(Path worktreePath) {
    run(true, "-C", worktreePath.toString(), "add", "-A");
  }

  /** Remove the linked worktree at {@code worktreePath} — tolerant of a path already gone. */
  void worktreeRemove(Path worktreePath) {
    run(false, "worktree", "remove", "--force", worktreePath.toString());
  }

  private boolean branchExists(String branch) {
    return run(false, "show-ref", "--quiet", "--verify", "refs/heads/" + branch) == 0;
  }

  private int run(boolean check, String... args) {
    final List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(args));
    try {
      final Process process =
          new ProcessBuilder(command).directory(repoDir.toFile()).redirectErrorStream(true).start();
      final byte[] output = process.getInputStream().readAllBytes();
      if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException(String.join(" ", command) + " timed out after " + TIMEOUT);
      }
      final int exit = process.exitValue();
      if (check && exit != 0) {
        throw new IllegalStateException(
            String.join(" ", command)
                + " failed ("
                + exit
                + "): "
                + new String(output, StandardCharsets.UTF_8).trim());
      }
      return exit;
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot run " + String.join(" ", command), ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while running " + String.join(" ", command), ex);
    }
  }
}
