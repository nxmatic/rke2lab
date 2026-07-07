package io.nxmatic.rke2lab.host.edge;

import io.nxmatic.rke2lab.host.port.GitFactsReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The live {@link GitFactsReader}: opens a jgit working tree and projects it to the flat {@link
 * GitFacts}. jgit resolves as a normal OSGi bundle (its jar carries the OSGi headers), so this edge
 * imports {@code org.eclipse.jgit.*} without embedding anything. An unreadable tree or a repo with
 * no HEAD yields empty rather than failing — reading git facts must never abort a seed.
 */
@Component(service = GitFactsReader.class)
public final class LiveGitFactsReader implements GitFactsReader {

  private static final Logger LOG = LoggerFactory.getLogger(LiveGitFactsReader.class);

  @Override
  public Optional<GitFacts> read(Path worktreeRoot, boolean dirtyCheckEnabled) {
    try (Repository repo = openRepository(worktreeRoot).orElse(null)) {
      if (repo == null) {
        return Optional.empty();
      }
      // A repository with no commits yet resolves HEAD to null — absorb it once.
      return Optional.ofNullable(repo.resolve(Constants.HEAD))
          .map(head -> factsOf(repo, head, dirtyCheckEnabled));
    } catch (IOException | UncheckedIOException ex) {
      LOG.warn("failed to read git facts at {}: {}", worktreeRoot, ex.getMessage());
      return Optional.empty();
    }
  }

  private static GitFacts factsOf(Repository repo, ObjectId head, boolean dirtyCheckEnabled) {
    try (RevWalk walk = new RevWalk(repo)) {
      final RevCommit commit = walk.parseCommit(head);
      final PersonIdent author = commit.getAuthorIdent();
      return new GitFacts(
          head.abbreviate(8).name(),
          head.name(),
          // "main", "feature/xyz", or the SHA if detached; jgit returns null only for a
          // never-checked-out repo, which cannot reach here (HEAD already resolved).
          Objects.requireNonNullElse(repo.getBranch(), head.name()),
          dirtyCheckEnabled && isDirty(repo),
          commit.getShortMessage(),
          author.getName(),
          Instant.ofEpochSecond(commit.getCommitTime()).toString());
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  private static Optional<Repository> openRepository(Path worktreeRoot) throws IOException {
    final FileRepositoryBuilder builder = new FileRepositoryBuilder();
    builder.setWorkTree(worktreeRoot.toFile());
    builder.findGitDir(worktreeRoot.toFile());
    if (builder.getGitDir() == null) {
      return Optional.empty();
    }
    return Optional.of(builder.build());
  }

  /** Whether the working tree has uncommitted changes (tracked, untracked, or staged). */
  private static boolean isDirty(Repository repo) {
    try (Git git = new Git(repo)) {
      return !git.status().call().isClean();
    } catch (GitAPIException ex) {
      // Treat an unreadable status as clean rather than failing the read.
      LOG.warn("failed to compute git dirty status: {}", ex.getMessage());
      return false;
    }
  }
}
