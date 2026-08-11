package io.nxmatic.rke2lab.worktree.internal;

import io.nxmatic.rke2lab.worktree.GitIdentity;
import io.nxmatic.rke2lab.worktree.Provenance;
import io.nxmatic.rke2lab.worktree.WorkingState;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * A jgit view of ONE git worktree at a path — the SOLE holder of jgit in this domain, and the
 * instance both worktrees compose rather than a static utility. {@link JgitWorktree} (the seed the
 * process runs inside) holds a checkout of its self-located root; {@link JgitLinkedWorktree} (a
 * rendered branch) holds a checkout of the transient linked path {@code git worktree add} created.
 * Each opens its repository on demand — provenance and working state change as the tree is edited,
 * so they are never cached. jgit stays sealed here; nothing jgit crosses back to a caller.
 *
 * <p>Works equally on a main worktree and a LINKED one: a linked worktree's {@code .git} file
 * points at the shared common dir, which {@link FileRepositoryBuilder#findGitDir} resolves, so
 * provenance/stage/commit act on the linked HEAD while {@link #forcePush} pushes through the shared
 * object database to the origin the repository already knows.
 */
final class JgitCheckout {

  private final Path worktree;

  JgitCheckout(Path worktree) {
    this.worktree = worktree;
  }

  Path root() {
    return worktree;
  }

  Provenance provenance() {
    try (Repository repository = open()) {
      final ObjectId head = repository.resolve(Constants.HEAD);
      if (head == null) {
        return new Provenance("", false);
      }
      return new Provenance(head.name(), !status(repository).isClean());
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read the provenance of " + worktree, ex);
    }
  }

  WorkingState workingState() {
    try (Repository repository = open()) {
      final Status status = status(repository);
      if (status.isClean()) {
        return new WorkingState(true, List.of());
      }
      return new WorkingState(false, uncommittedPaths(status));
    }
  }

  boolean flakeLockCoherent() {
    try (Repository repository = open()) {
      final ObjectId oldTree = repository.resolve("HEAD~1^{tree}");
      final ObjectId newTree = repository.resolve("HEAD^{tree}");
      if (oldTree == null || newTree == null) {
        return true; // no prior commit to diff against — nothing to judge, so coherent.
      }
      return violatingFlakeDirs(repository, oldTree, newTree).isEmpty();
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read the flake-lock coherence of " + worktree, ex);
    }
  }

  void stage(List<Path> paths) {
    try (Repository repository = open();
        Git git = new Git(repository)) {
      for (Path path : paths) {
        final Path absolute = (path.isAbsolute() ? path : worktree.resolve(path)).normalize();
        final String pattern =
            worktree.relativize(absolute).toString().replace(File.separatorChar, '/');
        if (Files.exists(absolute)) {
          git.add().addFilepattern(pattern).call();
        } else {
          git.rm().setCached(true).addFilepattern(pattern).call();
        }
      }
    } catch (GitAPIException ex) {
      throw new IllegalStateException("cannot stage paths in " + worktree, ex);
    }
  }

  String commit(String message, GitIdentity identity, Optional<String> sshSigningKey) {
    try (Repository repository = open();
        Git git = new Git(repository)) {
      final CommitCommand commit =
          git.commit()
              .setMessage(message)
              .setAuthor(identity.name(), identity.email())
              .setCommitter(identity.name(), identity.email());
      // Sign with the caller's OWN key when supplied (git SSHSIG via ssh-keygen — jgit ships no ssh
      // signer, so we inject one), else force signing OFF so a repo `commit.gpgsign=true` /
      // `gpg.format=ssh` does not fail an unsigned bot commit.
      sshSigningKey
          .filter(key -> !key.isBlank())
          .ifPresentOrElse(
              key -> commit.setSign(true).setSigner(new SshCommitSigner(key)),
              () -> commit.setSign(false));
      return commit.call().getName();
    } catch (GitAPIException ex) {
      throw new IllegalStateException("cannot commit in " + worktree, ex);
    }
  }

  /**
   * Force-push {@code branch} to {@code origin} over HTTPS, authenticating as {@code
   * x-access-token} with {@code token}. A forced refspec ({@code +…}) makes the remote branch match
   * the local HEAD exactly — a rendered branch is desired-state, not history. The credential is
   * held only for this call (jgit's in-memory provider), never written to config or a command line.
   */
  void forcePush(String branch, String token) {
    try (Repository repository = open();
        Git git = new Git(repository)) {
      git.push()
          .setRemote(Constants.DEFAULT_REMOTE_NAME)
          .setRefSpecs(new RefSpec("+refs/heads/" + branch + ":refs/heads/" + branch))
          .setForce(true)
          .setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", token))
          .call();
    } catch (GitAPIException ex) {
      throw new IllegalStateException("cannot force-push " + branch + " from " + worktree, ex);
    }
  }

  /**
   * The flake directories whose {@code flake.nix} {@code inputs} block changed in the latest commit
   * WITHOUT a matching {@code flake.lock} change — the incoherence a clean worktree does NOT catch
   * (a clean tree can still commit an incoherent lock). Over the {@code HEAD~1..HEAD} tree diff.
   */
  private List<String> violatingFlakeDirs(
      Repository repository, ObjectId oldTree, ObjectId newTree) {
    final LinkedHashSet<String> flakeNixDirs = new LinkedHashSet<>();
    final LinkedHashSet<String> flakeLockDirs = new LinkedHashSet<>();
    for (DiffEntry diff : diffTrees(repository, oldTree, newTree)) {
      collectFlakeDir(diff.getOldPath(), flakeNixDirs, flakeLockDirs);
      collectFlakeDir(diff.getNewPath(), flakeNixDirs, flakeLockDirs);
    }
    final List<String> violating = new ArrayList<>();
    for (String dir : flakeNixDirs) {
      if (!flakeLockDirs.contains(dir) && inputsChanged(repository, oldTree, newTree, dir)) {
        violating.add(dir);
      }
    }
    return violating;
  }

  private List<DiffEntry> diffTrees(Repository repository, ObjectId oldTree, ObjectId newTree) {
    try (Git git = new Git(repository);
        ObjectReader reader = repository.newObjectReader()) {
      final CanonicalTreeParser oldParser = new CanonicalTreeParser();
      oldParser.reset(reader, oldTree);
      final CanonicalTreeParser newParser = new CanonicalTreeParser();
      newParser.reset(reader, newTree);
      return git.diff().setOldTree(oldParser).setNewTree(newParser).call();
    } catch (GitAPIException | IOException ex) {
      throw new IllegalStateException("cannot diff HEAD~1..HEAD of " + worktree, ex);
    }
  }

  private void collectFlakeDir(String path, Set<String> flakeNixDirs, Set<String> flakeLockDirs) {
    if (path == null || path.isBlank() || DiffEntry.DEV_NULL.equals(path)) {
      return;
    }
    if (path.equals("flake.nix") || path.endsWith("/flake.nix")) {
      flakeNixDirs.add(parentDir(path));
    } else if (path.equals("flake.lock") || path.endsWith("/flake.lock")) {
      flakeLockDirs.add(parentDir(path));
    }
  }

  private String parentDir(String path) {
    final int lastSlash = path.lastIndexOf('/');
    return lastSlash < 0 ? "." : path.substring(0, lastSlash);
  }

  private boolean inputsChanged(
      Repository repository, ObjectId oldTree, ObjectId newTree, String dir) {
    final String flakePath = ".".equals(dir) ? "flake.nix" : dir + "/flake.nix";
    return !inputsBlock(readTreeFile(repository, oldTree, flakePath))
        .equals(inputsBlock(readTreeFile(repository, newTree, flakePath)));
  }

  private String readTreeFile(Repository repository, ObjectId treeId, String path) {
    try {
      final TreeWalk walk = TreeWalk.forPath(repository, path, treeId);
      if (walk == null) {
        return "";
      }
      try (walk) {
        final ObjectLoader loader = repository.open(walk.getObjectId(0));
        return new String(loader.getBytes(), StandardCharsets.UTF_8);
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read " + path + " from a git tree of " + worktree, ex);
    }
  }

  /**
   * The normalised {@code inputs = { … }} block of a flake.nix, empty when absent — brace-matched
   * from the {@code inputs} binding so a change to {@code outputs}/description does NOT read as an
   * inputs change.
   */
  private String inputsBlock(String flakeNix) {
    if (flakeNix.isBlank()) {
      return "";
    }
    for (int at = flakeNix.indexOf("inputs"); at >= 0; at = flakeNix.indexOf("inputs", at + 1)) {
      if (!isWordBoundary(flakeNix, at - 1) || !isWordBoundary(flakeNix, at + "inputs".length())) {
        continue;
      }
      final int eq = skipWhitespaceTo(flakeNix, at + "inputs".length(), '=');
      final int open = eq < 0 ? -1 : skipWhitespaceTo(flakeNix, eq + 1, '{');
      final int close = open < 0 ? -1 : matchingBrace(flakeNix, open);
      if (close > open) {
        return flakeNix.substring(open, close + 1).replaceAll("\\s+", " ").trim();
      }
    }
    return "";
  }

  private int skipWhitespaceTo(String value, int start, char target) {
    int i = start;
    while (i < value.length() && Character.isWhitespace(value.charAt(i))) {
      i++;
    }
    return i < value.length() && value.charAt(i) == target ? i : -1;
  }

  private int matchingBrace(String value, int open) {
    int depth = 0;
    for (int i = open; i < value.length(); i++) {
      final char ch = value.charAt(i);
      if (ch == '{') {
        depth++;
      } else if (ch == '}' && --depth == 0) {
        return i;
      }
    }
    return -1;
  }

  private boolean isWordBoundary(String value, int index) {
    if (index < 0 || index >= value.length()) {
      return true;
    }
    final char ch = value.charAt(index);
    return !(Character.isLetterOrDigit(ch) || ch == '_' || ch == '-');
  }

  private Repository open() {
    final FileRepositoryBuilder builder = new FileRepositoryBuilder();
    builder.setWorkTree(worktree.toFile());
    builder.findGitDir(worktree.toFile());
    try {
      return builder.build();
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot open the git repository at " + worktree, ex);
    }
  }

  private static Status status(Repository repository) {
    try (Git git = new Git(repository)) {
      return git.status().call();
    } catch (GitAPIException ex) {
      throw new IllegalStateException("cannot read the git status", ex);
    }
  }

  private static List<String> uncommittedPaths(Status status) {
    final LinkedHashSet<String> paths = new LinkedHashSet<>();
    paths.addAll(status.getAdded());
    paths.addAll(status.getChanged());
    paths.addAll(status.getModified());
    paths.addAll(status.getRemoved());
    paths.addAll(status.getMissing());
    paths.addAll(status.getUntracked());
    paths.addAll(status.getConflicting());
    return new ArrayList<>(paths);
  }
}
