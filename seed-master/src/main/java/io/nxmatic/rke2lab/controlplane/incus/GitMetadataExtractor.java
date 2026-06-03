package io.nxmatic.rke2lab.controlplane.incus;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
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
   * @return git metadata, or {@code null} if not a git repository
   */
  public static HostSlotManifest.GitInfo extract(Path repoRoot, boolean dirtyCheckEnabled) {
    try (Repository repo = openRepository(repoRoot)) {
      if (repo == null) {
        return null;
      }

      final ObjectId head = repo.resolve(Constants.HEAD);
      if (head == null) {
        // Empty repository (no commits yet)
        return null;
      }

      try (RevWalk walk = new RevWalk(repo)) {
        final RevCommit commit = walk.parseCommit(head);
        final String commitShort = head.abbreviate(8).name();
        final String commitFull = head.name();
        final String branch = repo.getBranch(); // "main", "feature/xyz", or commit SHA if detached
        final boolean dirty = dirtyCheckEnabled && isDirty(repo);
        final String commitMessage = commit.getShortMessage();
        final PersonIdent author = commit.getAuthorIdent();
        final String authorName = author.getName();
        final Instant commitDate = Instant.ofEpochSecond(commit.getCommitTime());

        return new HostSlotManifest.GitInfo(
            commitShort,
            commitFull,
            branch,
            dirty,
            commitMessage,
            authorName,
            commitDate.toString());
      }
    } catch (IOException ex) {
      // Log but don't fail bootstrap if git metadata extraction fails
      System.err.println("Warning: Failed to extract git metadata: " + ex.getMessage());
      return null;
    }
  }

  private static Repository openRepository(Path repoRoot) throws IOException {
    final FileRepositoryBuilder builder = new FileRepositoryBuilder();
    builder.setWorkTree(repoRoot.toFile());
    builder.findGitDir(repoRoot.toFile());

    if (builder.getGitDir() == null) {
      // Not a git repository
      return null;
    }

    return builder.build();
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
  public static String generateBuildId(HostSlotManifest.GitInfo gitInfo) {
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

    final String commitSuffix = gitInfo != null ? gitInfo.commit() : "unknown";
    return timestamp + "-" + commitSuffix;
  }
}
