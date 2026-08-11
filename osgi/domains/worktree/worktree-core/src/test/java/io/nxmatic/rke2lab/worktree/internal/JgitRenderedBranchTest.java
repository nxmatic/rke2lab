package io.nxmatic.rke2lab.worktree.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.nxmatic.rke2lab.worktree.GitIdentity;
import io.nxmatic.rke2lab.worktree.LinkedWorktree;
import io.nxmatic.rke2lab.worktree.Provenance;
import io.nxmatic.rke2lab.worktree.WorkingState;
import io.nxmatic.rke2lab.worktree.Worktree;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The RenderedBranch socle proven end-to-end against real git: a {@code file://} bare {@code
 * origin} and a working repository stand in for GitHub and the seed's worktree. It proves the whole
 * rendered -branch gesture — {@code prepare} a linked worktree on a fresh branch, materialise a
 * tree into it, seal it with an SSH-SIGNED commit (a throwaway {@code ssh-keygen} keypair standing
 * in for the ndh {@code github-signing} key), force-push it to origin, re-render idempotently (a
 * second prepare + commit + force-push moves the branch), and {@code close} to leave no linked
 * worktree behind.
 *
 * <p>Needs {@code git} and {@code ssh-keygen} on PATH (the flox runtime provides both); each is
 * guarded so the proof is skipped, never falsely failed, where a tool is absent.
 */
class JgitRenderedBranchTest {

  @Test
  void it_prepares_commits_signed_force_pushes_and_removes(@TempDir Path tmp) throws Exception {
    assumeTrue(toolPresent("git", "--version"), "git is required");

    final Path origin = tmp.resolve("origin.git");
    final Path work = tmp.resolve("work");
    final Path renderRoot = tmp.resolve("render");
    final String signingKey = throwawaySshKey(tmp.resolve("sign.key"));

    // A bare origin and a working repo with one commit on main, pushed to origin — the ground the
    // rendered branch is cut from.
    git(tmp, "init", "--bare", "-b", "main", origin.toString());
    git(tmp, "init", "-b", "main", work.toString());
    git(work, "config", "user.name", "test");
    git(work, "config", "user.email", "test@example.invalid");
    git(work, "config", "commit.gpgsign", "false");
    Files.writeString(work.resolve("README"), "source\n");
    git(work, "add", "README");
    git(work, "commit", "-m", "init");
    git(work, "remote", "add", "origin", origin.toUri().toString());
    git(work, "push", "origin", "main");

    final RenderedBranchFixture rendered =
        new RenderedBranchFixture(new JgitRenderedBranch(rootedAt(work)));
    final GitIdentity bot =
        new GitIdentity("rke2lab:manifests-bumper", "rke2lab+manifests-bumper@example.invalid");
    final String branch = "manifests/nikopol-mgmt";
    final Path worktreePath = renderRoot.resolve("nikopol-mgmt");

    // 1) prepare — a linked worktree of a fresh branch cut from main.
    final LinkedWorktree linked = rendered.branch().prepare(worktreePath, branch, "main");
    assertEquals(worktreePath.toRealPath(), linked.path(), "checked out at the asked path");
    assertEquals(branch, linked.branch());
    assertTrue(Files.isDirectory(linked.path()), "the linked worktree is on disk");
    assertTrue(worktreeRegistered(work, worktreePath), "git knows the linked worktree");

    // 2) materialise + sign + push — the rendered tree sealed and delivered.
    Files.writeString(linked.path().resolve("cluster.yaml"), "kind: Cluster\n");
    linked.stage(List.of(linked.path().resolve("cluster.yaml")));
    final String firstSha = linked.commit("render nikopol-mgmt", bot, Optional.of(signingKey));
    assertFalse(firstSha.isBlank(), "the commit reports its sha");
    assertTrue(isSshSigned(work, firstSha), "the rendered commit is SSH-signed");
    linked.forcePush("x-access-token-value-unused-over-file");
    assertEquals(firstSha, originTip(origin, branch), "origin's branch is the pushed commit");

    // 3) re-render — a second prepare + commit + force-push regenerates the branch (idempotent
    // prepare, forced ref update). The tree is replaced, not accreted.
    final LinkedWorktree again = rendered.branch().prepare(worktreePath, branch, "main");
    Files.writeString(again.path().resolve("cluster.yaml"), "kind: Cluster\nversion: 2\n");
    again.stage(List.of(again.path().resolve("cluster.yaml")));
    final String secondSha = again.commit("re-render nikopol-mgmt", bot, Optional.of(signingKey));
    again.forcePush("x-access-token-value-unused-over-file");
    assertEquals(secondSha, originTip(origin, branch), "origin advanced to the re-render");

    // 4) close — the linked worktree is gone, the repo left clean of it.
    again.close();
    assertFalse(Files.exists(worktreePath), "the linked worktree directory is removed");
    assertFalse(worktreeRegistered(work, worktreePath), "git no longer lists the linked worktree");
  }

  /** A {@link Worktree} that only knows its root — all this socle asks of it. */
  private Worktree rootedAt(Path root) {
    return new Worktree() {
      @Override
      public Path root() {
        return root;
      }

      @Override
      public Provenance provenance() {
        throw new UnsupportedOperationException();
      }

      @Override
      public WorkingState workingState() {
        throw new UnsupportedOperationException();
      }

      @Override
      public boolean flakeLockCoherent() {
        throw new UnsupportedOperationException();
      }

      @Override
      public void stage(List<Path> paths) {
        throw new UnsupportedOperationException();
      }

      @Override
      public String commit(String message, GitIdentity identity, Optional<String> sshSigningKey) {
        throw new UnsupportedOperationException();
      }
    };
  }

  /** The sha origin's {@code branch} points at, read from the bare repo. */
  private String originTip(Path origin, String branch) throws Exception {
    return git(origin, "rev-parse", "refs/heads/" + branch).trim();
  }

  /** Whether {@code sha}'s commit object carries an SSH signature header. */
  private boolean isSshSigned(Path repo, String sha) throws Exception {
    return git(repo, "cat-file", "-p", sha).contains("BEGIN SSH SIGNATURE");
  }

  /**
   * Whether git's worktree registry lists {@code worktreePath} for {@code repo}. Canonicalises via
   * the PARENT (which outlives the leaf — {@code git worktree remove} drops only the leaf), so the
   * probe works both before and after {@code close()}, matching the real path git records.
   */
  private boolean worktreeRegistered(Path repo, Path worktreePath) throws Exception {
    final String canonical =
        worktreePath.getParent().toRealPath().resolve(worktreePath.getFileName()).toString();
    return git(repo, "worktree", "list", "--porcelain").contains("worktree " + canonical);
  }

  private String throwawaySshKey(Path keyFile) throws Exception {
    final Process process =
        new ProcessBuilder(
                "ssh-keygen",
                "-t",
                "ed25519",
                "-N",
                "",
                "-C",
                "render-test",
                "-f",
                keyFile.toString())
            .redirectErrorStream(true)
            .start();
    if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
      abort("ssh-keygen could not mint a throwaway key");
    }
    return Files.readString(keyFile, StandardCharsets.UTF_8);
  }

  private boolean toolPresent(String... probe) {
    try {
      final Process process =
          new ProcessBuilder(probe).redirectErrorStream(true).redirectOutput(discard()).start();
      return process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (IOException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return false;
    }
  }

  private ProcessBuilder.Redirect discard() {
    return ProcessBuilder.Redirect.to(
        new java.io.File(
            System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null"));
  }

  /** Run {@code git <args>} in {@code dir}, returning stdout; throws on a non-zero exit. */
  private String git(Path dir, String... args) throws Exception {
    final java.util.List<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(List.of(args));
    final Process process =
        new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
    final byte[] output = process.getInputStream().readAllBytes();
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException(String.join(" ", command) + " timed out");
    }
    final String text = new String(output, StandardCharsets.UTF_8);
    if (process.exitValue() != 0) {
      throw new IllegalStateException(String.join(" ", command) + " failed: " + text.trim());
    }
    return text;
  }

  /** The socle under test, threaded as an instance rather than reached statically. */
  private record RenderedBranchFixture(JgitRenderedBranch branch) {}
}
