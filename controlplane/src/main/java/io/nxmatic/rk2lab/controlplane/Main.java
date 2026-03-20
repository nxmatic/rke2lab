package io.nxmatic.rk2lab.controlplane;

import com.pulumi.Config;
import com.pulumi.Pulumi;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rk2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rk2lab.manifests.api.ManifestUpdateGate;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

/** Entry point for the Pulumi management-cluster bootstrap program. */
public final class Main {

  private static final List<EntryGatePolicy> ENTRY_GATE_POLICIES =
      List.of(
          new EntryGatePolicy("manifests-update-gate", Main::enforceManifestUpdateGate),
          new EntryGatePolicy("clean-git-worktree", Main::enforceCleanWorktree),
          new EntryGatePolicy("flake-lock-coherence", Main::enforceFlakeLockCoherence));

  private Main() {
    // Utility class
  }

  public static void main(String[] args) {
    if (!isPulumiEngineAvailable()) {
      runStandalone();
      return;
    }

    Pulumi.run(
        context -> {
          final Config config = context.config("rke2lab");
          final BootstrapConfig bootstrapConfig =
              new BootstrapConfig.Builder().applyConfig(config).build();
          final ControlplanePolicy controlplanePolicy = ControlplanePolicy.from(config);
          final BootstrapOutputs outputs =
              bootstrapAndCollectOutputs(bootstrapConfig, controlplanePolicy);
          outputs.values().forEach(context::export);
        });
  }

  private static BootstrapOutputs bootstrapAndCollectOutputs(
      BootstrapConfig config, ControlplanePolicy policy) {
    enforceEntryGatePolicies(config.localWorktreePath());

    final String bootstrapPhase;
    final boolean handoffReady;
    final IncusResourceBootstrap.BootstrapResult bootstrapResult =
        new IncusResourceBootstrap(config, policy).apply();
    final String seedNodeId = bootstrapResult.seedNodeId();
    final Object imageFingerprint = bootstrapResult.imageFingerprint();
    final Object seedInstanceStatus = bootstrapResult.instanceStatus();
    final Object seedInstanceUrn = bootstrapResult.instanceUrn();
    final Object seedProviderUrn = bootstrapResult.providerUrn();
    final String provisioningChecksum = bootstrapResult.provisioningChecksum();
    final String imageBuildChecksum = bootstrapResult.imageBuildChecksum();
    final String hostSourceDirRelative = bootstrapResult.hostSourceDirRelative();
    bootstrapPhase = "Ready";
    handoffReady = true;

    final Map<String, Object> outputs = new LinkedHashMap<>();
    outputs.put("managementClusterName", config.clusterName());
    outputs.put("apiEndpoint", config.apiEndpoint().toString());
    outputs.put("kubeconfigRef", config.kubeconfigRef().toString());
    outputs.put("seedNodeId", seedNodeId);
    outputs.put("seedInstanceUrn", seedInstanceUrn);
    outputs.put("seedProviderUrn", seedProviderUrn);
    outputs.put("seedProvisioningChecksum", provisioningChecksum);
    outputs.put("seedImageBuildChecksum", imageBuildChecksum);
    outputs.put("seedImageFingerprint", imageFingerprint);
    outputs.put("seedInstanceStatus", seedInstanceStatus);
    outputs.put("hostSourceDirRelative", hostSourceDirRelative);
    outputs.put("incusProject", config.incusProject());
    outputs.put("imageAlias", config.imageAlias());
    outputs.put("seedLanBridgeParent", config.lanBridgeParent());
    outputs.putAll(policy.toOutputMap());
    outputs.put("handoffReady", handoffReady);
    outputs.put("bootstrapPhase", bootstrapPhase);
    outputs.put("nextStep", "bootstrap-management-cluster-then-apply-stageb-cluster-manifests");
    return new BootstrapOutputs(outputs);
  }

  private static void runStandalone() {
    final BootstrapConfig bootstrapConfig = new BootstrapConfig.Builder().build();
    final ControlplanePolicy controlplanePolicy = ControlplanePolicy.defaults();
    final BootstrapOutputs outputs =
        bootstrapAndCollectOutputs(bootstrapConfig, controlplanePolicy);
    System.out.println(
        "Pulumi engine not detected (missing PULUMI_MONITOR). Running in standalone mode.");
    System.out.println("Bootstrap outputs:");
    outputs.values().forEach((key, value) -> System.out.println(key + "=" + value));
  }

  private static boolean isPulumiEngineAvailable() {
    final String monitor = System.getenv("PULUMI_MONITOR");
    return monitor != null && !monitor.isBlank();
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
            changes.stream().filter(Main::isEmbeddedManifestResourcePath).toList();
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

  private static void enforceEntryGatePolicies(Path worktreePath) {
    final Path normalizedWorktreePath = worktreePath.toAbsolutePath().normalize();
    for (EntryGatePolicy policy : ENTRY_GATE_POLICIES) {
      try {
        policy.check().run(normalizedWorktreePath);
      } catch (IllegalStateException ex) {
        throw new IllegalStateException(
            "Entry-gate policy failed (" + policy.name() + "): " + ex.getMessage(), ex);
      }
    }
  }

  private static void enforceFlakeLockCoherence(Path worktreePath) {
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

        final List<DiffEntry> diffs = diffTrees(repository, oldTreeId, newTreeId);
        final LinkedHashSet<String> flakeNixDirs = new LinkedHashSet<>();
        final LinkedHashSet<String> flakeLockDirs = new LinkedHashSet<>();

        for (DiffEntry diff : diffs) {
          collectFlakeDirs(diff.getOldPath(), flakeNixDirs, flakeLockDirs);
          collectFlakeDirs(diff.getNewPath(), flakeNixDirs, flakeLockDirs);
        }

        flakeNixDirs.removeAll(flakeLockDirs);
        if (flakeNixDirs.isEmpty()) {
          return;
        }

        throw new IllegalStateException(
            "Flake lock coherence policy violation: detected changes to flake.nix without matching flake.lock "
                + "changes in the latest commit. Update locks and commit again. "
                + "Affected flake directories:\n- "
                + String.join("\n- ", flakeNixDirs));
      }
    } catch (IllegalStateException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException(
          "Failed to verify flake lock coherence at: " + normalizedWorktreePath, ex);
    }
  }

  private static List<DiffEntry> diffTrees(
      Repository repository, ObjectId oldTreeId, ObjectId newTreeId) throws Exception {
    try (Git git = new Git(repository);
        ObjectReader reader = repository.newObjectReader()) {
      final CanonicalTreeParser oldTree = new CanonicalTreeParser();
      oldTree.reset(reader, oldTreeId);
      final CanonicalTreeParser newTree = new CanonicalTreeParser();
      newTree.reset(reader, newTreeId);
      return git.diff().setOldTree(oldTree).setNewTree(newTree).call();
    }
  }

  private static void collectFlakeDirs(
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

  private record BootstrapOutputs(Map<String, Object> values) {}
}
