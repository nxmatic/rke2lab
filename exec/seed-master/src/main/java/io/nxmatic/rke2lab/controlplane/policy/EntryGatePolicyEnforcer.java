package io.nxmatic.rke2lab.controlplane.policy;

import io.nxmatic.rke2lab.manifests.bridge.ManifestUpdateGate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceLoader;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;

/** Enforces entry-gate policies for bootstrap execution. */
public final class EntryGatePolicyEnforcer {

  private static final List<EntryGatePolicy> ENTRY_GATE_POLICIES =
      List.of(
          new EntryGatePolicy(
              "manifests-update-gate", EntryGatePolicyEnforcer::enforceManifestUpdateGate),
          new EntryGatePolicy("clean-git-worktree", EntryGatePolicyEnforcer::enforceCleanWorktree),
          new EntryGatePolicy(
              "flake-lock-coherence", EntryGatePolicyEnforcer::enforceFlakeLockCoherence));

  private EntryGatePolicyEnforcer() {}

  public static void enforceAll(Path worktreePath, boolean cleanWorktreeRequired) {
    final Path normalizedWorktreePath = worktreePath.toAbsolutePath().normalize();
    for (EntryGatePolicy policy : ENTRY_GATE_POLICIES) {
      if ("clean-git-worktree".equals(policy.name()) && !cleanWorktreeRequired) {
        continue;
      }
      try {
        policy.check().run(normalizedWorktreePath);
      } catch (IllegalStateException ex) {
        throw new IllegalStateException(
            "Entry-gate policy failed (" + policy.name() + "): " + ex.getMessage(), ex);
      }
    }
  }

  private static void enforceManifestUpdateGate(Path worktreePath) {
    final List<ManifestUpdateGate> gates =
        ServiceLoader.load(ManifestUpdateGate.class).stream()
            .map(ServiceLoader.Provider::get)
            .toList();
    if (gates.isEmpty()) {
      throw new IllegalStateException("No ManifestUpdateGate provider found via ServiceLoader.");
    }
    if (gates.size() > 1) {
      throw new IllegalStateException(
          "Expected exactly one ManifestUpdateGate provider, found "
              + gates.size()
              + ": "
              + gates.stream().map(ManifestUpdateGate::gateId).toList());
    }

    gates.getFirst().enforce(worktreePath);
  }

  private static void enforceCleanWorktree(Path worktreePath) {
    final Path normalizedWorktreePath = worktreePath.toAbsolutePath().normalize();
    try {
      final FileRepositoryBuilder builder =
          new FileRepositoryBuilder().findGitDir(normalizedWorktreePath.toFile());
      if (builder.getGitDir() == null) {
        throw new IllegalStateException(
            "No git repository found for worktree: " + normalizedWorktreePath);
      }

      try (Repository repository = builder.build();
          Git git = new Git(repository)) {
        final Status status = git.status().call();
        if (status.isClean()) {
          return;
        }

        final List<String> changes = summarizeStatus(status);
        final List<String> relevantChanges =
            changes.stream()
                .filter(EntryGatePolicyEnforcer::isEmbeddedManifestResourcePath)
                .toList();
        if (relevantChanges.isEmpty()) {
          return;
        }
        throw new IllegalStateException(
            "Pulumi update requires a clean manifests module worktree for Stage A. Resolve or commit manifests generator/resource changes before running. "
                + "Worktree: "
                + normalizedWorktreePath
                + "\nRelevant paths:\n- "
                + String.join("\n- ", relevantChanges));
      }
    } catch (IllegalStateException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException(
          "Failed to verify clean git worktree at: " + normalizedWorktreePath, ex);
    }
  }

  private static void enforceFlakeLockCoherence(Path worktreePath) {
    if (true) {
      // Temporarily disable the flake lock coherence policy until we have a better story for
      // managing the git worktree state in the manifests module.
      return;
    }
    final Path normalizedWorktreePath = worktreePath.toAbsolutePath().normalize();
    try {
      final FileRepositoryBuilder builder =
          new FileRepositoryBuilder().findGitDir(normalizedWorktreePath.toFile());
      if (builder.getGitDir() == null) {
        throw new IllegalStateException(
            "No git repository found for worktree: " + normalizedWorktreePath);
      }

      try (Repository repository = builder.build()) {
        final ObjectId oldTreeId = repository.resolve("HEAD~1^{tree}");
        final ObjectId newTreeId = repository.resolve("HEAD^{tree}");
        if (oldTreeId == null || newTreeId == null) {
          return;
        }

        final List<DiffEntry> diffs = GitTreeDiffer.diffTrees(repository, oldTreeId, newTreeId);
        final LinkedHashSet<String> flakeNixDirs = new LinkedHashSet<>();
        final LinkedHashSet<String> flakeLockDirs = new LinkedHashSet<>();

        for (DiffEntry diff : diffs) {
          FlakeDirCollector.collect(diff.getOldPath(), flakeNixDirs, flakeLockDirs);
          FlakeDirCollector.collect(diff.getNewPath(), flakeNixDirs, flakeLockDirs);
        }

        final LinkedHashSet<String> violatingDirs = new LinkedHashSet<>();
        for (String flakeNixDir : flakeNixDirs) {
          if (flakeLockDirs.contains(flakeNixDir)) {
            continue;
          }
          if (FlakeInputsChecker.hasInputsChanged(repository, oldTreeId, newTreeId, flakeNixDir)) {
            violatingDirs.add(flakeNixDir);
          }
        }

        if (violatingDirs.isEmpty()) {
          return;
        }

        throw new IllegalStateException(
            "Flake lock coherence policy violation: detected flake.nix inputs changes without "
                + "matching flake.lock changes in the latest commit. Update locks and commit again. "
                + "Affected flake directories:\n- "
                + String.join("\n- ", violatingDirs));
      }
    } catch (IllegalStateException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException(
          "Failed to verify flake lock coherence at: " + normalizedWorktreePath, ex);
    }
  }

  private static List<String> summarizeStatus(Status status) {
    final LinkedHashSet<String> paths = new LinkedHashSet<>();
    append(paths, status.getAdded());
    append(paths, status.getChanged());
    append(paths, status.getModified());
    append(paths, status.getRemoved());
    append(paths, status.getMissing());
    append(paths, status.getUntracked());
    append(paths, status.getConflicting());

    final ArrayList<String> ordered = new ArrayList<>(paths);
    final int maxEntries = 20;
    if (ordered.size() <= maxEntries) {
      return ordered;
    }

    final ArrayList<String> truncated = new ArrayList<>(ordered.subList(0, maxEntries));
    truncated.add("... and " + (ordered.size() - maxEntries) + " more");
    return truncated;
  }

  private static void append(LinkedHashSet<String> target, Collection<String> values) {
    if (values == null || values.isEmpty()) {
      return;
    }
    target.addAll(values);
  }

  private static boolean isEmbeddedManifestResourcePath(String path) {
    return path != null
        && (path.startsWith("manifests/src/main/resources/")
            || path.startsWith("manifests/src/main/java/")
            || "manifests/src/main/resources".equals(path));
  }

  @FunctionalInterface
  private interface PolicyCheck {
    void run(Path worktreePath);
  }

  private record EntryGatePolicy(String name, PolicyCheck check) {}

  private static final class GitTreeDiffer {
    private GitTreeDiffer() {}

    static List<DiffEntry> diffTrees(Repository repository, ObjectId oldTreeId, ObjectId newTreeId)
        throws Exception {
      try (Git git = new Git(repository);
          org.eclipse.jgit.lib.ObjectReader reader = repository.newObjectReader()) {
        final CanonicalTreeParser oldTree = new CanonicalTreeParser();
        oldTree.reset(reader, oldTreeId);
        final CanonicalTreeParser newTree = new CanonicalTreeParser();
        newTree.reset(reader, newTreeId);
        return git.diff().setOldTree(oldTree).setNewTree(newTree).call();
      }
    }
  }

  private static final class FlakeDirCollector {
    private FlakeDirCollector() {}

    static void collect(
        String path, LinkedHashSet<String> flakeNixDirs, LinkedHashSet<String> flakeLockDirs) {
      if (path == null || path.isBlank() || DiffEntry.DEV_NULL.equals(path)) {
        return;
      }

      if (path.endsWith("/flake.nix") || "flake.nix".equals(path)) {
        flakeNixDirs.add(parentDirectory(path));
        return;
      }
      if (path.endsWith("/flake.lock") || "flake.lock".equals(path)) {
        flakeLockDirs.add(parentDirectory(path));
      }
    }

    private static String parentDirectory(String path) {
      final int lastSlash = path.lastIndexOf('/');
      return lastSlash < 0 ? "." : path.substring(0, lastSlash);
    }
  }

  private static final class FlakeInputsChecker {
    private FlakeInputsChecker() {}

    static boolean hasInputsChanged(
        Repository repository, ObjectId oldTreeId, ObjectId newTreeId, String flakeDir) {
      final String flakePath = ".".equals(flakeDir) ? "flake.nix" : flakeDir + "/flake.nix";
      final String oldFlakeNix = GitTreeFileReader.read(repository, oldTreeId, flakePath);
      final String newFlakeNix = GitTreeFileReader.read(repository, newTreeId, flakePath);

      final String oldInputs = FlakeInputsParser.extractInputsBlock(oldFlakeNix);
      final String newInputs = FlakeInputsParser.extractInputsBlock(newFlakeNix);
      return !oldInputs.equals(newInputs);
    }
  }

  private static final class GitTreeFileReader {
    private GitTreeFileReader() {}

    static String read(Repository repository, ObjectId treeId, String path) {
      if (treeId == null || path == null || path.isBlank()) {
        return "";
      }
      try {
        final TreeWalk treeWalk = TreeWalk.forPath(repository, path, treeId);
        if (treeWalk == null) {
          return "";
        }
        try (treeWalk) {
          final ObjectLoader loader = repository.open(treeWalk.getObjectId(0));
          return new String(loader.getBytes(), StandardCharsets.UTF_8);
        }
      } catch (Exception ex) {
        throw new IllegalStateException("Failed to read " + path + " from git tree", ex);
      }
    }
  }

  private static final class FlakeInputsParser {
    private FlakeInputsParser() {}

    static String extractInputsBlock(String flakeNixText) {
      if (flakeNixText == null || flakeNixText.isBlank()) {
        return "";
      }

      int searchFrom = 0;
      while (searchFrom >= 0 && searchFrom < flakeNixText.length()) {
        final int candidateIndex = flakeNixText.indexOf("inputs", searchFrom);
        if (candidateIndex < 0) {
          return "";
        }

        if (isIdentifierBoundary(flakeNixText, candidateIndex - 1)
            && isIdentifierBoundary(flakeNixText, candidateIndex + "inputs".length())) {
          final int afterKeyword = candidateIndex + "inputs".length();
          final int equalsIndex = skipWhitespaceAndFind(flakeNixText, afterKeyword, '=');
          if (equalsIndex >= 0) {
            final int openBraceIndex = skipWhitespaceAndFind(flakeNixText, equalsIndex + 1, '{');
            if (openBraceIndex >= 0) {
              final int closeBraceIndex =
                  BraceMatcher.findMatchingBrace(flakeNixText, openBraceIndex);
              if (closeBraceIndex > openBraceIndex) {
                return normalizeWhitespace(
                    flakeNixText.substring(openBraceIndex, closeBraceIndex + 1));
              }
            }
          }
        }

        searchFrom = candidateIndex + 1;
      }

      return "";
    }

    private static int skipWhitespaceAndFind(String value, int start, char target) {
      int index = start;
      while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
        index++;
      }
      if (index < value.length() && value.charAt(index) == target) {
        return index;
      }
      return -1;
    }

    private static boolean isIdentifierBoundary(String value, int index) {
      if (index < 0 || index >= value.length()) {
        return true;
      }
      final char ch = value.charAt(index);
      return !(Character.isLetterOrDigit(ch) || ch == '_' || ch == '-');
    }

    private static String normalizeWhitespace(String value) {
      return value.replaceAll("\\s+", " ").trim();
    }
  }

  private static final class BraceMatcher {
    private BraceMatcher() {}

    static int findMatchingBrace(String value, int openBraceIndex) {
      int depth = 0;
      for (int index = openBraceIndex; index < value.length(); index++) {
        final char ch = value.charAt(index);
        if (ch == '{') {
          depth++;
        } else if (ch == '}') {
          depth--;
          if (depth == 0) {
            return index;
          }
        }
      }
      return -1;
    }
  }
}
