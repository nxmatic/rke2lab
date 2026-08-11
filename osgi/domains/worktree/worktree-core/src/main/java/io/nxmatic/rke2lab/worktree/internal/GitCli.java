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
   * Add a linked worktree at {@code worktreePath} on {@code branch}, (re)created at {@code base}.
   * Idempotent across re-runs: any prior worktree at the path is removed and the administrative
   * records pruned first, then {@code -B} resets {@code branch} to {@code base} and checks it out
   * fresh — a rendered branch is regenerated, never accreted. The add is checked (a failure
   * throws); the pre-clean tolerates absence.
   */
  void worktreeAdd(Path worktreePath, String branch, String base) {
    run(false, "worktree", "remove", "--force", worktreePath.toString());
    run(false, "worktree", "prune");
    run(true, "worktree", "add", "--force", "-B", branch, worktreePath.toString(), base);
  }

  /** Remove the linked worktree at {@code worktreePath} — tolerant of a path already gone. */
  void worktreeRemove(Path worktreePath) {
    run(false, "worktree", "remove", "--force", worktreePath.toString());
  }

  private void run(boolean check, String... args) {
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
      if (check && process.exitValue() != 0) {
        throw new IllegalStateException(
            String.join(" ", command)
                + " failed ("
                + process.exitValue()
                + "): "
                + new String(output, StandardCharsets.UTF_8).trim());
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot run " + String.join(" ", command), ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while running " + String.join(" ", command), ex);
    }
  }
}
