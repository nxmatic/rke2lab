package io.nxmatic.rke2lab.incus.core;

import io.nxmatic.rke2lab.incus.contract.HostStagingEntry.Provenance;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * Reads a worktree's provenance — the HEAD {@code sha} + whether the tree is {@code dirty} — into a
 * {@link Provenance} the staging entry carries (§ host-cellar-realisation, the reconcile cycle).
 * The incus {@code prepare} scion runs it at production, on the worktree it reconstructed the
 * topology from; the sha is the KEY (the rest is recoverable via {@code git show}), dirty the one
 * bit the sha cannot carry. The reduced twin of {@code main}'s {@code GitMetadataExtractor} (which
 * also captured message/author/date — dropped here as derivable duplication).
 */
public final class GitProvenanceReader {

  /**
   * Read {@code worktreeRoot}'s HEAD sha + dirty. A worktree with no {@code .git} or no commit yet
   * yields an empty-sha provenance (a legitimate first run, not a failure); an unreadable status is
   * treated as clean rather than failing the prep.
   */
  public Provenance read(Path worktreeRoot) {
    final FileRepositoryBuilder builder = new FileRepositoryBuilder();
    builder.setWorkTree(worktreeRoot.toFile());
    builder.findGitDir(worktreeRoot.toFile());
    if (builder.getGitDir() == null) {
      return new Provenance("", false);
    }
    try (Repository repo = builder.build()) {
      final ObjectId head = repo.resolve(Constants.HEAD);
      if (head == null) {
        return new Provenance("", false);
      }
      return new Provenance(head.name(), isDirty(repo));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read git provenance of " + worktreeRoot, e);
    }
  }

  private static boolean isDirty(Repository repo) {
    try (Git git = new Git(repo)) {
      return !git.status().call().isClean();
    } catch (GitAPIException e) {
      return false;
    }
  }
}
