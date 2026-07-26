package io.nxmatic.rke2lab.worktree;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Optional;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * The worktree the seed cultivates — its root, and the door onto the git repository rooted there.
 * ONE component both realms rest on: the host reads it before Felix boots (the {@code Main} derives
 * {@code worktree.dir}, the entry gate checks the tree is clean), and the incus scion reads it
 * in-container (the reconstructed worktree's HEAD provenance). It owns the single piece all three
 * genuinely shared: {@code findGitDir} + the empty-gitdir handling. The specialised reads
 * (provenance sha/dirty, entry-gate status/diff) stay with their owners and rest on {@link
 * #openRepository()}.
 *
 * <p>jgit lives underneath but never crosses the broker seam: {@code Worktree} and the {@link
 * Repository} it hands back are consumed locally in each realm, never placed in a {@code
 * SeedEnvelope} (the seam is String-only) — so no realm leak is possible (§ REALM_BOUNDARY).
 */
public final class Worktree {

  private final Path root;

  private Worktree(Path root) {
    this.root = root;
  }

  /**
   * Locate the worktree that ENCLOSES {@code startDir} — jgit walks up to the first {@code .git}
   * (dir or linked-worktree file) and reports its working tree. This is how the host derives its
   * own {@code worktree.dir} at runtime: it starts from the process directory and finds the real
   * root, so a launch from any subdirectory still resolves it. Throws if no worktree encloses
   * {@code startDir} (a bare repo, or no repository at all).
   */
  public static Worktree locatedFrom(Path startDir) {
    final FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(startDir.toFile());
    if (builder.getGitDir() == null) {
      throw new IllegalStateException("no git worktree encloses " + startDir);
    }
    try (Repository repository = builder.build()) {
      final File workTree = repository.getWorkTree();
      if (workTree == null) {
        throw new IllegalStateException("no worktree for the git dir found from " + startDir);
      }
      return new Worktree(workTree.toPath().toAbsolutePath().normalize());
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot locate the worktree enclosing " + startDir, ex);
    }
  }

  /**
   * Adopt a KNOWN worktree root — the reconstructed worktree the incus scion hands in, or the path
   * the entry gate is asked to check. No git access happens here; the repository is opened lazily
   * by {@link #openRepository()}.
   */
  public static Worktree at(Path root) {
    return new Worktree(root.toAbsolutePath().normalize());
  }

  /** The worktree root, absolute and normalised. */
  public Path root() {
    return root;
  }

  /**
   * Open the git repository rooted at this worktree — the single {@code findGitDir} door. Empty
   * when the root is not a git worktree (no {@code .git}): a legitimate state each caller reads its
   * own way (the provenance reader yields an empty sha, the entry gate throws). The caller owns the
   * returned {@link Repository} and must close it.
   */
  public Optional<Repository> openRepository() {
    final FileRepositoryBuilder builder = new FileRepositoryBuilder();
    builder.setWorkTree(root.toFile());
    builder.findGitDir(root.toFile());
    if (builder.getGitDir() == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(builder.build());
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot open the git repository at " + root, ex);
    }
  }
}
