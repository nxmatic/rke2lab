package io.nxmatic.rk2lab.controlplane;

import com.pulumi.Config;
import com.pulumi.Pulumi;
import com.pulumi.core.Output;
import com.pulumi.deployment.Deployment;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rk2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rk2lab.manifests.api.ManifestUpdateGate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;

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
          final boolean readinessEnabled = resolveReadinessEnabled(config);
          final boolean cleanWorktreeRequired = resolveCleanWorktreeRequired(config);
          final Consumer<String> readinessLogger =
              message -> context.log().info("[readiness] " + message);
          final BootstrapOutputs outputs =
              bootstrapAndCollectOutputs(
                  bootstrapConfig,
                  controlplanePolicy,
                  readinessEnabled,
                  cleanWorktreeRequired,
                  readinessLogger);
          outputs.values().forEach(context::export);
        });
  }

  private static BootstrapOutputs bootstrapAndCollectOutputs(
      BootstrapConfig config,
      ControlplanePolicy policy,
      boolean readinessEnabled,
      boolean cleanWorktreeRequired,
      Consumer<String> readinessLogger) {
    enforceEntryGatePolicies(config.localWorktreePath(), cleanWorktreeRequired);
    RuntimeCommandPreflight.enforceRequiredCommands(List.of("ssh", "kubectl"), readinessLogger);
    RuntimeCommandPreflight.enforceRemoteCommandAvailable(
        config.imageBuilderHost(), "incus", readinessLogger);

    final IncusResourceBootstrap.BootstrapResult bootstrapResult =
        new IncusResourceBootstrap(config, policy).apply();
    final Map<String, Object> systemdAdapterLaunchSummary;
    if (isPulumiEngineAvailable() && Deployment.getInstance().isDryRun()) {
      systemdAdapterLaunchSummary = SeedSystemdAdapterEndpointGate.deferredPreview(config);
    } else {
      systemdAdapterLaunchSummary =
          SeedSystemdAdapterEndpointGate.ensureReachable(config, readinessLogger);
    }
    final Object readinessOutput;
    final Object clusterReadinessResourceUrn;
    final Object registryResourceUrn;
    final Object imageBuildResourceUrn;
    final Map<String, Object> registrySummary;
    final Map<String, Object> imageBuildSummary;
    final Object systemdRuntimeStatusSummary;
    if (isPulumiEngineAvailable()) {
      final ClusterReadinessResource readinessResource =
          new ClusterReadinessResource(
              "seed-cluster-readiness",
              config,
              policy,
              readinessEnabled,
              readinessLogger,
              Map.of(
                  "instanceStatus",
                  bootstrapResult.instanceStatus(),
                  "systemdAdapterLaunch",
                  systemdAdapterLaunchSummary),
              bootstrapResult.readinessDependency());
      readinessOutput = readinessResource.verificationResult();
      clusterReadinessResourceUrn = readinessResource.urn();

      final BootstrapRegistryResource registryResource =
          new BootstrapRegistryResource(
              "seed-bootstrap-registry",
              config,
              bootstrapResult.provisioningChecksum(),
              bootstrapResult.hostSourceDirRelative(),
              bootstrapResult.layerEnvRegistrySummary(),
              bootstrapResult.systemdProvisioningSummary(),
              bootstrapResult.readinessDependency());
      registryResourceUrn = registryResource.urn();
      registrySummary = registryResource.summary();

      final SeedImageBuildResource imageBuildResource =
          new SeedImageBuildResource(
              "seed-image-build",
              config,
              bootstrapResult.imageBuildChecksum(),
              bootstrapResult.imageFingerprint(),
              bootstrapResult.readinessDependency());
      imageBuildResourceUrn = imageBuildResource.urn();
      imageBuildSummary = imageBuildResource.summary();

      systemdRuntimeStatusSummary =
          Deployment.getInstance().isDryRun()
              ? SeedSystemdAdapterRuntimeStatusSnapshot.deferredPreview(config)
              : SeedSystemdAdapterRuntimeStatusSnapshot.snapshot(config, readinessLogger);
    } else {
      readinessOutput =
          readinessEnabled
              ? ClusterBootstrapReadinessVerifier.verify(config, policy, readinessLogger)
              : ClusterBootstrapReadinessVerifier.skipped(policy, readinessLogger);
      clusterReadinessResourceUrn = "";
      registryResourceUrn = "";
      imageBuildResourceUrn = "";
      registrySummary =
          Map.of(
              "checksum",
              bootstrapResult.provisioningChecksum(),
              "hostSourceDirRelative",
              bootstrapResult.hostSourceDirRelative(),
              "localWorktreePath",
              config.localWorktreePath().toString(),
              "layerEnvRegistry",
              bootstrapResult.layerEnvRegistrySummary(),
              "systemdProvisioning",
              bootstrapResult.systemdProvisioningSummary());
      imageBuildSummary =
          Map.of(
              "checksum", bootstrapResult.imageBuildChecksum(),
              "imageAlias", config.imageAlias(),
              "imageFingerprint", bootstrapResult.imageFingerprint(),
              "incusProject", config.incusProject());
      systemdRuntimeStatusSummary =
          SeedSystemdAdapterRuntimeStatusSnapshot.snapshotStandalone(config);
    }
    final String seedNodeId = bootstrapResult.seedNodeId();
    final Object imageFingerprint = bootstrapResult.imageFingerprint();
    final Object seedInstanceStatus = bootstrapResult.instanceStatus();
    final Object seedInstanceUrn = bootstrapResult.instanceUrn();
    final Object seedProviderUrn = bootstrapResult.providerUrn();
    final String provisioningChecksum = bootstrapResult.provisioningChecksum();
    final String imageBuildChecksum = bootstrapResult.imageBuildChecksum();
    final String hostSourceDirRelative = bootstrapResult.hostSourceDirRelative();

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
    if (readinessOutput instanceof Output<?> readinessAsOutput) {
      @SuppressWarnings("unchecked")
      final Output<ClusterBootstrapReadinessVerifier.VerificationResult> readinessResultOutput =
          (Output<ClusterBootstrapReadinessVerifier.VerificationResult>) readinessAsOutput;
      outputs.put(
          "clusterReadinessEnabled",
          readinessResultOutput.applyValue(
              ClusterBootstrapReadinessVerifier.VerificationResult::readinessEnabled));
      outputs.put(
          "clusterReadinessSkipped",
          readinessResultOutput.applyValue(value -> !value.readinessEnabled()));
      outputs.put(
          "clusterKubeconfigPublished",
          readinessResultOutput.applyValue(
              ClusterBootstrapReadinessVerifier.VerificationResult::kubeconfigPublished));
      outputs.put(
          "clusterApiReady",
          readinessResultOutput.applyValue(
              ClusterBootstrapReadinessVerifier.VerificationResult::apiReady));
      outputs.put(
          "clusterControllersEffective",
          readinessResultOutput.applyValue(
              ClusterBootstrapReadinessVerifier.VerificationResult::controllersEffective));
      outputs.put(
          "clusterRequiredControllers",
          readinessResultOutput.applyValue(
              ClusterBootstrapReadinessVerifier.VerificationResult::requiredControllerRefs));
      outputs.put(
          "clusterReadinessSummary",
          readinessResultOutput.applyValue(
              ClusterBootstrapReadinessVerifier.VerificationResult::summary));
      outputs.put(
          "handoffReady",
          readinessResultOutput.applyValue(
              ClusterBootstrapReadinessVerifier.VerificationResult::handoffReady));
      outputs.put(
          "bootstrapStatus",
          readinessResultOutput.applyValue(
              ClusterBootstrapReadinessVerifier.VerificationResult::bootstrapStatus));
      outputs.put(
          "nextStep",
          readinessResultOutput.applyValue(
              value ->
                  value.handoffReady()
                      ? "bootstrap-management-cluster-then-apply-stageb-cluster-manifests"
                      : "wait-for-cluster-readiness"));
    } else {
      final ClusterBootstrapReadinessVerifier.VerificationResult readiness =
          (ClusterBootstrapReadinessVerifier.VerificationResult) readinessOutput;
      outputs.putAll(readiness.asOutputs());
      outputs.put("handoffReady", readiness.handoffReady());
      outputs.put("bootstrapStatus", readiness.bootstrapStatus());
      outputs.put(
          "nextStep",
          readiness.handoffReady()
              ? "bootstrap-management-cluster-then-apply-stageb-cluster-manifests"
              : "wait-for-cluster-readiness");
    }
    outputs.put("clusterReadinessResourceUrn", clusterReadinessResourceUrn);
    outputs.put("registryResourceUrn", registryResourceUrn);
    outputs.put("seedImageBuildResourceUrn", imageBuildResourceUrn);
    outputs.put("registrySummary", registrySummary);
    outputs.put("systemdProvisioningSummary", bootstrapResult.systemdProvisioningSummary());
    outputs.put("systemdAdapterLaunchSummary", systemdAdapterLaunchSummary);
    outputs.put("systemdRuntimeStatusSummary", systemdRuntimeStatusSummary);
    outputs.put("seedImageBuildSummary", imageBuildSummary);
    return new BootstrapOutputs(outputs);
  }

  private static void runStandalone() {
    final BootstrapConfig bootstrapConfig = new BootstrapConfig.Builder().build();
    final ControlplanePolicy controlplanePolicy = ControlplanePolicy.defaults();
    final Consumer<String> readinessLogger =
        message -> System.out.println("[readiness] " + message);
    final BootstrapOutputs outputs =
        bootstrapAndCollectOutputs(
            bootstrapConfig, controlplanePolicy, true, true, readinessLogger);
    System.out.println(
        "Pulumi engine not detected (missing PULUMI_MONITOR). Running in standalone mode.");
    System.out.println("Bootstrap outputs:");
    outputs.values().forEach((key, value) -> System.out.println(key + "=" + value));
  }

  private static boolean resolveReadinessEnabled(Config config) {
    final String raw = config.get("readiness.enabled").orElse("").trim();
    if (raw.isBlank()) {
      return true;
    }

    return switch (raw.toLowerCase()) {
      case "1", "true", "yes", "on" -> true;
      case "0", "false", "no", "off" -> false;
      default ->
          throw new IllegalArgumentException("Invalid boolean for readiness.enabled: " + raw);
    };
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

  private static void enforceEntryGatePolicies(Path worktreePath, boolean cleanWorktreeRequired) {
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

  private static boolean resolveCleanWorktreeRequired(Config config) {
    if (config == null) {
      return true;
    }

    final String raw = config.get("entryGate.cleanWorktree.required").orElse("").trim();
    if (raw.isBlank()) {
      return true;
    }

    return switch (raw.toLowerCase()) {
      case "1", "true", "yes", "on" -> true;
      case "0", "false", "no", "off" -> false;
      default ->
          throw new IllegalArgumentException(
              "Invalid boolean for entryGate.cleanWorktree.required: " + raw);
    };
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

        final LinkedHashSet<String> violatingDirs = new LinkedHashSet<>();
        for (String flakeNixDir : flakeNixDirs) {
          if (flakeLockDirs.contains(flakeNixDir)) {
            continue;
          }
          if (hasFlakeInputsChanged(repository, oldTreeId, newTreeId, flakeNixDir)) {
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

  private static boolean hasFlakeInputsChanged(
      Repository repository, ObjectId oldTreeId, ObjectId newTreeId, String flakeDir) {
    final String flakePath = ".".equals(flakeDir) ? "flake.nix" : flakeDir + "/flake.nix";
    final String oldFlakeNix = readTreeFile(repository, oldTreeId, flakePath);
    final String newFlakeNix = readTreeFile(repository, newTreeId, flakePath);

    final String oldInputs = extractInputsBlock(oldFlakeNix);
    final String newInputs = extractInputsBlock(newFlakeNix);
    return !oldInputs.equals(newInputs);
  }

  private static String readTreeFile(Repository repository, ObjectId treeId, String path) {
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

  private static String extractInputsBlock(String flakeNixText) {
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
            final int closeBraceIndex = findMatchingBrace(flakeNixText, openBraceIndex);
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

  private static int findMatchingBrace(String value, int openBraceIndex) {
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
