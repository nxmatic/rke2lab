package io.nxmatic.rke2lab.worktree.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.nxmatic.rke2lab.worktree.GitIdentity;
import io.nxmatic.rke2lab.worktree.LinkedWorktree;
import io.nxmatic.rke2lab.worktree.Provenance;
import io.nxmatic.rke2lab.worktree.RenderedBranch;
import io.nxmatic.rke2lab.worktree.WorkingState;
import io.nxmatic.rke2lab.worktree.Worktree;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.Daemon;
import org.eclipse.jgit.transport.DaemonClient;
import org.eclipse.jgit.transport.DaemonService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The RenderedBranch socle, proven against real git. Each test reads as a step of the one gesture —
 * {@code prepare} a linked worktree on a branch seeded with a null-commit base, materialise a tree,
 * seal it with an SSH-SIGNED commit, fast-forward push, RE-render (accretion), and {@code close} —
 * because the plumbing (a stand-in GitHub, a work repo, a signing key, every {@code git} shell-out)
 * lives in the {@link GitGround} fixture, not in the tests.
 *
 * <p>The stand-in origin is served over a loopback {@code git://} daemon, NOT a {@code file://}
 * path: jgit's local push deadlocks its in-JVM {@code InternalPushConnection} (two piped streams in
 * one JVM), so the push must ride a real socket — which is also what production (HTTPS) does.
 *
 * <p>Needs {@code git} and {@code ssh-keygen} on PATH (the flox runtime provides both); absence
 * aborts (skips) rather than fails.
 */
class JgitRenderedBranchTest {

  private static final String CLUSTER = "nikopol-mgmt";
  private static final String BRANCH = "manifests/" + CLUSTER;
  private static final String TOKEN = "x-access-token-unused-over-the-git-daemon";
  private static final GitIdentity BOT =
      new GitIdentity("rke2lab:manifests-bumper", "rke2lab+manifests-bumper@example.invalid");

  @TempDir Path tmp;
  private GitGround ground;

  @BeforeEach
  void setUp() throws Exception {
    assumeTrue(toolPresent("git", "--version"), "git is required");
    ground = new GitGround(tmp);
  }

  @AfterEach
  void tearDown() {
    if (ground != null) {
      ground.close();
    }
  }

  @Test
  void prepare_seeds_a_null_commit_base() throws Exception {
    final Path worktreePath = ground.renderPath(CLUSTER);

    final LinkedWorktree linked = ground.renderedBranch().prepare(worktreePath, BRANCH);

    assertEquals(worktreePath.toRealPath(), linked.path(), "checked out at the asked path");
    assertEquals(BRANCH, linked.branch());
    assertTrue(Files.isDirectory(linked.path()), "the linked worktree is on disk");
    assertTrue(ground.registersWorktree(worktreePath), "git knows the linked worktree");
    assertEquals(1, ground.commitCount(linked.path()), "the branch starts at its null-commit base");
  }

  @Test
  void a_render_accretes_is_ssh_signed_and_pushes() throws Exception {
    final Path worktreePath = ground.renderPath(CLUSTER);
    final LinkedWorktree linked = ground.renderedBranch().prepare(worktreePath, BRANCH);

    Files.writeString(linked.path().resolve("cluster.yaml"), "kind: Cluster\n");
    linked.stageAll();
    final String sha = linked.commit("render " + CLUSTER, BOT, Optional.of(ground.signingKey()));

    assertFalse(sha.isBlank(), "the commit reports its sha");
    assertTrue(ground.isSshSigned(sha), "the rendered commit is SSH-signed");
    assertEquals(2, ground.commitCount(linked.path()), "the render accretes on the null base");

    linked.push(TOKEN);
    assertEquals(sha, ground.originTip(BRANCH), "origin advanced to the pushed render");
  }

  @Test
  void re_preparing_reuses_the_branch_so_renders_accrete_as_fast_forwards() throws Exception {
    final Path worktreePath = ground.renderPath(CLUSTER);
    final RenderedBranch branch = ground.renderedBranch();

    // render 1 — accretes on the null base, pushed.
    final LinkedWorktree first = branch.prepare(worktreePath, BRANCH);
    Files.writeString(first.path().resolve("cluster.yaml"), "kind: Cluster\n");
    first.stageAll();
    final String firstSha =
        first.commit("render " + CLUSTER, BOT, Optional.of(ground.signingKey()));
    first.push(TOKEN);

    // render 2 — re-prepare REUSES the branch (its history survives), a second commit whose parent
    // is render 1 (a fast-forward, not a fresh orphan).
    final LinkedWorktree again = branch.prepare(worktreePath, BRANCH);
    assertEquals(2, ground.commitCount(again.path()), "re-prepare keeps the branch history");
    Files.writeString(again.path().resolve("cluster.yaml"), "kind: Cluster\nversion: 2\n");
    again.stageAll();
    final String secondSha =
        again.commit("re-render " + CLUSTER, BOT, Optional.of(ground.signingKey()));

    assertEquals(3, ground.commitCount(again.path()), "null base + two renders");
    assertEquals(
        firstSha, ground.parentSha(again.path(), secondSha), "render 2's parent is render 1");
    again.push(TOKEN);
    assertEquals(secondSha, ground.originTip(BRANCH), "origin fast-forwarded to render 2");
  }

  @Test
  void close_removes_the_linked_worktree_but_keeps_the_branch() throws Exception {
    final Path worktreePath = ground.renderPath(CLUSTER);
    final LinkedWorktree linked = ground.renderedBranch().prepare(worktreePath, BRANCH);

    linked.close();

    assertFalse(Files.exists(worktreePath), "the linked worktree directory is removed");
    assertFalse(ground.registersWorktree(worktreePath), "git no longer lists the linked worktree");
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

  /**
   * The inline fixture "app": a bare origin served over a loopback {@code git://} daemon (a
   * stand-in GitHub on a real socket, so the push does not deadlock jgit's in-JVM {@code file://}
   * transport), a work repository with one commit on {@code main}, and a throwaway {@code
   * ssh-keygen} key (the ndh {@code github-signing} stand-in). It owns every {@code git} shell-out
   * and the daemon lifecycle, so a test reads as prepare / render / push / close, not as plumbing.
   * {@link AutoCloseable}: {@link #close()} stops the daemon.
   */
  static final class GitGround implements AutoCloseable {

    private final Path origin;
    private final Path work;
    private final Path renderRoot;
    private final String signingKey;
    private final Daemon daemon;

    GitGround(Path tmp) throws Exception {
      this.origin = tmp.resolve("origin.git");
      this.work = tmp.resolve("work");
      this.renderRoot = tmp.resolve("render");
      this.signingKey = throwawaySshKey(tmp.resolve("sign.key"));

      git(tmp, "init", "--bare", "-b", "main", origin.toString());
      this.daemon = serveOverGitDaemon(origin);
      final String originUrl = "git://127.0.0.1:" + daemon.getAddress().getPort() + "/origin.git";

      git(tmp, "init", "-b", "main", work.toString());
      git(work, "config", "user.name", "test");
      git(work, "config", "user.email", "test@example.invalid");
      git(work, "config", "commit.gpgsign", "false");
      Files.writeString(work.resolve("README"), "source\n");
      git(work, "add", "README");
      git(work, "commit", "-m", "init");
      git(work, "remote", "add", "origin", originUrl);
      git(work, "push", "origin", "main");
    }

    /** A rendered branch cut from this ground's work repository. */
    RenderedBranch renderedBranch() {
      return new JgitRenderedBranch(new SeedWorktree(work));
    }

    /** The render-worktree path for a cluster leaf under the (would-be gitignored) render root. */
    Path renderPath(String leaf) {
      return renderRoot.resolve(leaf);
    }

    /** The throwaway OpenSSH private key renders are signed with. */
    String signingKey() {
      return signingKey;
    }

    /** The sha origin's {@code branch} points at, read straight from the bare repo. */
    String originTip(String branch) throws Exception {
      return git(origin, "rev-parse", "refs/heads/" + branch).trim();
    }

    /** Commits reachable from the worktree's HEAD — the branch's history depth. */
    int commitCount(Path worktree) throws Exception {
      return Integer.parseInt(git(worktree, "rev-list", "--count", "HEAD").trim());
    }

    /** The parent sha of {@code sha} in the worktree's repo. */
    String parentSha(Path worktree, String sha) throws Exception {
      return git(worktree, "rev-parse", sha + "^").trim();
    }

    /** Whether {@code sha}'s commit object carries an SSH signature header. */
    boolean isSshSigned(String sha) throws Exception {
      return git(work, "cat-file", "-p", sha).contains("BEGIN SSH SIGNATURE");
    }

    /**
     * Whether git's worktree registry lists {@code worktreePath}. Canonicalises via the PARENT
     * (which outlives the leaf — {@code git worktree remove} drops only the leaf), so it answers
     * both before and after {@code close()}, matching the real path git records.
     */
    boolean registersWorktree(Path worktreePath) throws Exception {
      final String canonical =
          worktreePath.getParent().toRealPath().resolve(worktreePath.getFileName()).toString();
      return git(work, "worktree", "list", "--porcelain").contains("worktree " + canonical);
    }

    @Override
    public void close() {
      daemon.stop();
    }

    /**
     * Serve {@code bareRepo} over a loopback {@code git://} daemon (ephemeral port), receive-pack
     * force-enabled, a single-repo resolver handing back {@code bareRepo} for any requested name.
     */
    private Daemon serveOverGitDaemon(Path bareRepo) throws Exception {
      final Daemon server = new Daemon(new InetSocketAddress("127.0.0.1", 0));
      final DaemonService receivePack = server.getService("receive-pack");
      receivePack.setEnabled(true);
      receivePack.setOverridable(false);
      server.setRepositoryResolver(
          (DaemonClient req, String name) -> {
            try {
              return new FileRepositoryBuilder().setGitDir(bareRepo.toFile()).build();
            } catch (IOException ex) {
              throw new RepositoryNotFoundException(name, ex);
            }
          });
      server.start();
      return server;
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
  }

  /**
   * A {@link Worktree} that knows only its root — all {@link RenderedBranch#prepare} asks of it.
   */
  private record SeedWorktree(Path root) implements Worktree {

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
  }
}
