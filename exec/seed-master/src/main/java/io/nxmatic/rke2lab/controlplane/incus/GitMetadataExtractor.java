package io.nxmatic.rke2lab.controlplane.incus;

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

/**
 * Extracts git metadata (commit, branch, author, etc.) from the current working directory.
 *
 * <p>Used to populate {@link HostSlotManifest.GitInfo} during bootstrap.
 */
public final class GitMetadataExtractor {

  private GitMetadataExtractor() {}

  /**
   * Extracts git metadata from the repository at the given directory.
   *
   * @param repoRoot the repository root (directory containing {@code .git/})
   * @param dirtyCheckEnabled whether to compute the working-tree dirty flag; disabled during heavy
   *     refactors via {@code policy.gitDirtyCheck.enabled=false} to avoid committing on every run
   * @return git metadata, or empty if not a git repository
   */
  public static Optional<HostSlotManifest.GitInfo> extract(
      Path repoRoot, boolean dirtyCheckEnabled) {
    try (Repository repo = openRepository(repoRoot).orElse(null)) {
      if (repo == null) {
        return Optional.empty(); // not a git repository
      }
      // A repository with no commits yet resolves HEAD to null (third-party jgit) — absorb it once.
      return Optional.ofNullable(repo.resolve(Constants.HEAD))
          .map(head -> gitInfoOf(repo, head, dirtyCheckEnabled));
    } catch (IOException | UncheckedIOException ex) {
      // Log but don't fail bootstrap if git metadata extraction fails
      System.err.println("Warning: Failed to extract git metadata: " + ex.getMessage());
      return Optional.empty();
    }
  }

  private static HostSlotManifest.GitInfo gitInfoOf(
      Repository repo, ObjectId head, boolean dirtyCheckEnabled) {
    try (RevWalk walk = new RevWalk(repo)) {
      final RevCommit commit = walk.parseCommit(head);
      final PersonIdent author = commit.getAuthorIdent();
      return new HostSlotManifest.GitInfo(
          head.abbreviate(8).name(),
          head.name(),
          // "main", "feature/xyz", or commit SHA if detached; jgit returns null only for a
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

  private static Optional<Repository> openRepository(Path repoRoot) throws IOException {
    final FileRepositoryBuilder builder = new FileRepositoryBuilder();
    builder.setWorkTree(repoRoot.toFile());
    builder.findGitDir(repoRoot.toFile());

    if (builder.getGitDir() == null) {
      // Not a git repository
      return Optional.empty();
    }

    return Optional.of(builder.build());
  }

  /** Checks if the working tree has uncommitted changes (tracked, untracked, or staged). */
  private static boolean isDirty(Repository repo) {
    try (Git git = new Git(repo)) {
      return !git.status().call().isClean();
    } catch (GitAPIException ex) {
      // Treat an unreadable status as clean rather than failing bootstrap.
      System.err.println("Warning: Failed to compute git dirty status: " + ex.getMessage());
      return false;
    }
  }

  /**
   * Generates a build ID from current timestamp and git commit.
   *
   * <p>Format: {@code YYYYMMDD-HHMMSS-<shortCommit>}
   *
   * <p>Example: {@code 20260529-080523-b71a8f8f}
   */
  public static String generateBuildId(Optional<HostSlotManifest.GitInfo> gitInfo) {
    final Instant now = Instant.now();
    final String timestamp =
        String.format(
            "%04d%02d%02d-%02d%02d%02d",
            now.atZone(java.time.ZoneOffset.UTC).getYear(),
            now.atZone(java.time.ZoneOffset.UTC).getMonthValue(),
            now.atZone(java.time.ZoneOffset.UTC).getDayOfMonth(),
            now.atZone(java.time.ZoneOffset.UTC).getHour(),
            now.atZone(java.time.ZoneOffset.UTC).getMinute(),
            now.atZone(java.time.ZoneOffset.UTC).getSecond());

    final String commitSuffix = gitInfo.map(HostSlotManifest.GitInfo::commit).orElse("unknown");
    return timestamp + "-" + commitSuffix;
  }
}
