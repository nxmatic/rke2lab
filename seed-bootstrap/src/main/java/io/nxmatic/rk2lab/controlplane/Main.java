package io.nxmatic.rk2lab.controlplane;

import com.pulumi.Config;
import com.pulumi.Pulumi;
import com.pulumi.core.Output;
import com.pulumi.deployment.Deployment;
import io.nxmatic.bbox.api.BboxApiClient;
import io.nxmatic.bbox.reconcile.Action;
import io.nxmatic.bbox.reconcile.ReservationReconciler;
import io.nxmatic.bbox.reconcile.ReservationReconciler.Mode;
import io.nxmatic.rk2lab.controlplane.bbox.BboxReservationsResource;
import io.nxmatic.rk2lab.controlplane.bbox.BboxSecretsReader;
import io.nxmatic.rk2lab.controlplane.bbox.BlueprintRowEnumerator;
import io.nxmatic.rk2lab.controlplane.bbox.DesiredRow;
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
          SeedLog.installPulumiLogSink(
              (event, message) -> {
                switch (event) {
                  case ERROR -> context.log().error(message);
                  case WARN -> context.log().warn(message);
                  case INFO -> context.log().info(message);
                  case DEBUG, TRACE -> context.log().debug(message);
                }
              });
          try {
            final Config config = context.config("rke2lab");
            final BootstrapConfig bootstrapConfig =
                new BootstrapConfig.Builder().applyConfig(config).build();
            final ControlplanePolicy controlplanePolicy = ControlplanePolicy.from(config);
            final boolean readinessEnabled = resolveReadinessEnabled(config);
            final boolean cleanWorktreeRequired = resolveCleanWorktreeRequired(config);
            final boolean bboxFailOnError = resolveBboxFailOnError(config);
            final Consumer<String> readinessLogger = message -> SeedLog.info("readiness", message);
            final BootstrapOutputs outputs =
                bootstrapAndCollectOutputs(
                    bootstrapConfig,
                    controlplanePolicy,
                    readinessEnabled,
                    cleanWorktreeRequired,
                    bboxFailOnError,
                    readinessLogger);
            outputs.values().forEach(context::export);
          } finally {
            SeedLog.clearPulumiLogSink();
          }
        });
  }

  private static BootstrapOutputs bootstrapAndCollectOutputs(
      BootstrapConfig config,
      ControlplanePolicy policy,
      boolean readinessEnabled,
      boolean cleanWorktreeRequired,
      boolean bboxFailOnError,
      Consumer<String> readinessLogger) {
    enforceEntryGatePolicies(config.localWorktreePath(), cleanWorktreeRequired);
    RuntimeCommandPreflight.enforceRequiredCommands(List.of("ssh", "kubectl"), readinessLogger);
    RuntimeCommandPreflight.enforceRemoteCommandAvailable(
        config.imageBuilderHost(), "incus", readinessLogger);

    // DHCP reservations on the bbox must be in place before any incus instance leases — run
    // the reconciler now, before IncusResourceBootstrap. The Pulumi engine path constructs a
    // BboxReservationsResource (parent) which opens the bbox session, fetches the table once,
    // and registers one BboxReservationResource child per canonical RKE2 row — so `pulumi
    // preview` / `pulumi up` show a per-row diff. During preview (dryRun), no writes happen.
    final boolean bboxDryRun = isPulumiEngineAvailable() && Deployment.getInstance().isDryRun();
    final Object bboxResourceUrn;
    final Map<String, Object> bboxSummaryMap;
    if (isPulumiEngineAvailable()) {
      final BboxReservationsResource bboxResource =
          buildBboxReservationsResource(config.localWorktreePath(), bboxDryRun, bboxFailOnError);
      if (bboxResource != null) {
        bboxResourceUrn = bboxResource.urn();
        bboxSummaryMap = toBboxSummaryMap(bboxResource);
      } else {
        bboxResourceUrn = "";
        bboxSummaryMap = Map.of("status", "skipped");
      }
    } else {
      bboxResourceUrn = "";
      bboxSummaryMap = runBboxReconcileStandalone(config.localWorktreePath(), bboxFailOnError);
    }

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
    final Object systemdAdapterResourceUrn;
    final Object registryResourceUrn;
    final Object imageBuildResourceUrn;
    final Object manifestSynthResourceUrn;
    final Map<String, Object> registrySummary;
    final Map<String, Object> imageBuildSummary;
    final Map<String, Object> manifestSynthSummary;
    final Object systemdRuntimeStatusSummary;
    if (isPulumiEngineAvailable()) {
      final SystemdAdapterResource systemdAdapterResource =
          new SystemdAdapterResource(
              "seed-systemd-adapter",
              systemdAdapterLaunchSummary,
              bootstrapResult.readinessDependency());
      systemdAdapterResourceUrn = systemdAdapterResource.urn();

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

      final SeedManifestSynthResource manifestSynthResource =
          new SeedManifestSynthResource(
              "seed-manifest-synth",
              bootstrapResult.manifestSynthSummary(),
              bootstrapResult.readinessDependency());
      manifestSynthResourceUrn = manifestSynthResource.urn();
      manifestSynthSummary = manifestSynthResource.summary();

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
      systemdAdapterResourceUrn = "";
      registryResourceUrn = "";
      imageBuildResourceUrn = "";
      manifestSynthResourceUrn = "";
      manifestSynthSummary =
          bootstrapResult.manifestSynthSummary() == null
              ? Map.of()
              : Map.copyOf(bootstrapResult.manifestSynthSummary());
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
    outputs.put("systemdAdapterResourceUrn", systemdAdapterResourceUrn);
    outputs.put("registryResourceUrn", registryResourceUrn);
    outputs.put("seedImageBuildResourceUrn", imageBuildResourceUrn);
    outputs.put("seedManifestSynthResourceUrn", manifestSynthResourceUrn);
    outputs.put("bboxReservationsResourceUrn", bboxResourceUrn);
    outputs.put("bboxReservationsSummary", bboxSummaryMap);
    outputs.put("registrySummary", registrySummary);
    outputs.put("systemdProvisioningSummary", bootstrapResult.systemdProvisioningSummary());
    outputs.put("systemdAdapterLaunchSummary", systemdAdapterLaunchSummary);
    outputs.put("systemdRuntimeStatusSummary", systemdRuntimeStatusSummary);
    outputs.put("seedImageBuildSummary", imageBuildSummary);
    outputs.put("seedManifestSynthSummary", manifestSynthSummary);
    return new BootstrapOutputs(outputs);
  }

  private static void runStandalone() {
    final BootstrapConfig bootstrapConfig = new BootstrapConfig.Builder().build();
    final ControlplanePolicy controlplanePolicy = ControlplanePolicy.defaults();
    final Consumer<String> readinessLogger = message -> SeedLog.info("readiness", message);
    final BootstrapOutputs outputs =
        bootstrapAndCollectOutputs(
            bootstrapConfig, controlplanePolicy, true, true, true, readinessLogger);
    SeedLog.info(
        "standalone",
        "Pulumi engine not detected (missing PULUMI_MONITOR). Running in standalone mode.");
    SeedLog.info("standalone", "Bootstrap outputs:");
    outputs.values().forEach((key, value) -> SeedLog.info("standalone", key + "=" + value));
  }

  private static boolean resolveBboxFailOnError(Config config) {
    final String raw = config.get("bbox.reconcile.failOnError").orElse("").trim();
    if (raw.isBlank()) {
      return true;
    }
    return switch (raw.toLowerCase()) {
      case "1", "true", "yes", "on" -> true;
      case "0", "false", "no", "off" -> false;
      default ->
          throw new IllegalArgumentException(
              "Invalid boolean for rke2lab:bbox.reconcile.failOnError: " + raw);
    };
  }

  /**
   * Build the {@link BboxReservationsResource} parent. Returns {@code null} when {@code
   * failOnError=false} and either the secrets read or the bbox session fails — the caller then
   * records a "skipped" summary instead of bringing down the whole {@code pulumi up}.
   *
   * <p>{@code failOnError=true} (the default) propagates any failure: missing reservations before
   * the incus instance leases means the bootstrap can't succeed regardless.
   */
  private static BboxReservationsResource buildBboxReservationsResource(
      Path worktreePath, boolean dryRun, boolean failOnError) {
    final BboxSecretsReader.BboxCoordinates coordinates;
    try {
      coordinates = BboxSecretsReader.readBboxCoordinates(worktreePath);
    } catch (RuntimeException ex) {
      if (failOnError) {
        throw ex;
      }
      SeedLog.warn(
          "bbox-reconcile",
          "skipping reconciliation: cannot read bbox coordinates (" + ex.getMessage() + ")");
      return null;
    }

    try {
      final BboxReservationsResource resource =
          new BboxReservationsResource(
              "bbox-reservations", coordinates.uri(), coordinates.password(), dryRun, null);
      logBboxSummary(resource);
      if (failOnError && resource.countOf(Action.FAILED) > 0) {
        throw new IllegalStateException(
            "bbox reconciliation completed with "
                + resource.countOf(Action.FAILED)
                + " failed operation(s); set rke2lab:bbox.reconcile.failOnError=false to ignore.");
      }
      return resource;
    } catch (RuntimeException ex) {
      if (failOnError) {
        throw ex;
      }
      SeedLog.warn(
          "bbox-reconcile", "skipping reconciliation: bbox call failed (" + ex.getMessage() + ")");
      return null;
    }
  }

  /**
   * Standalone (no Pulumi engine) reconciliation. Walks the canonical rows directly through a
   * stateful reconciler and returns an aggregate summary map for the standalone outputs dump.
   * Always live (no dryRun) — standalone is "do the work without Pulumi state tracking."
   */
  private static Map<String, Object> runBboxReconcileStandalone(
      Path worktreePath, boolean failOnError) {
    final BboxSecretsReader.BboxCoordinates coordinates;
    try {
      coordinates = BboxSecretsReader.readBboxCoordinates(worktreePath);
    } catch (RuntimeException ex) {
      if (failOnError) {
        throw ex;
      }
      SeedLog.warn(
          "bbox-reconcile",
          "standalone: skipping reconciliation: cannot read bbox coordinates ("
              + ex.getMessage()
              + ")");
      return Map.of("status", "skipped", "reason", ex.getMessage());
    }

    final List<DesiredRow> rows = new BlueprintRowEnumerator().rows();
    final java.util.EnumMap<Action, Integer> counts = new java.util.EnumMap<>(Action.class);
    for (Action action : Action.values()) {
      counts.put(action, 0);
    }
    try (BboxApiClient client = BboxApiClient.open(coordinates.uri(), coordinates.password())) {
      final ReservationReconciler reconciler = new ReservationReconciler(client);
      for (DesiredRow row : rows) {
        final Action action = reconciler.apply(row.reservation(), Mode.APPLY).action();
        counts.merge(action, 1, (a, b) -> a + b);
      }
    } catch (Exception ex) {
      if (failOnError) {
        if (ex instanceof RuntimeException re) {
          throw re;
        }
        throw new IllegalStateException(
            "bbox standalone reconciliation failed: " + ex.getMessage(), ex);
      }
      SeedLog.warn(
          "bbox-reconcile",
          "standalone: skipping reconciliation: bbox call failed (" + ex.getMessage() + ")");
      return Map.of("status", "skipped", "reason", ex.getMessage());
    }

    if (failOnError && counts.get(Action.FAILED) > 0) {
      throw new IllegalStateException(
          "bbox reconciliation completed with "
              + counts.get(Action.FAILED)
              + " failed operation(s); set rke2lab:bbox.reconcile.failOnError=false to ignore.");
    }

    final LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("dryRun", false);
    out.put("desiredCount", rows.size());
    out.put("createdCount", counts.get(Action.CREATED));
    out.put("updatedCount", counts.get(Action.UPDATED));
    out.put("matchingCount", counts.get(Action.MATCHING));
    out.put("failedCount", counts.get(Action.FAILED));
    return out;
  }

  private static Map<String, Object> toBboxSummaryMap(BboxReservationsResource resource) {
    final LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("dryRun", resource.dryRun());
    out.put("desiredCount", resource.children().size());
    out.put("createdCount", resource.countOf(Action.CREATED));
    out.put("updatedCount", resource.countOf(Action.UPDATED));
    out.put("matchingCount", resource.countOf(Action.MATCHING));
    out.put("wouldCreateCount", resource.countOf(Action.WOULD_CREATE));
    out.put("wouldUpdateCount", resource.countOf(Action.WOULD_UPDATE));
    out.put("failedCount", resource.countOf(Action.FAILED));
    return out;
  }

  private static void logBboxSummary(BboxReservationsResource resource) {
    SeedLog.info(
        "bbox-reconcile",
        "summary: desired="
            + resource.children().size()
            + " created="
            + resource.countOf(Action.CREATED)
            + " updated="
            + resource.countOf(Action.UPDATED)
            + " matching="
            + resource.countOf(Action.MATCHING)
            + " wouldCreate="
            + resource.countOf(Action.WOULD_CREATE)
            + " wouldUpdate="
            + resource.countOf(Action.WOULD_UPDATE)
            + " failed="
            + resource.countOf(Action.FAILED));
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
    if (true) {
      // Temporarily disable the flake lock coherence policy until we have a better story for
      // managing the git worktree state in the manifests module. The current policy is too
      // fragile and causes more pain than it solves, especially for new users who are not yet
      // familiar with the git intricacies of the manifests module.
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
