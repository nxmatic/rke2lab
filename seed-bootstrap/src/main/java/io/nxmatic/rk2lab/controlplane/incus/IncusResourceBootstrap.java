package io.nxmatic.rk2lab.controlplane.incus;

import com.pulumi.core.Output;
import com.pulumi.deployment.Deployment;
import com.pulumi.incus.IncusFunctions;
import com.pulumi.incus.Instance;
import com.pulumi.incus.InstanceArgs;
import com.pulumi.incus.Network;
import com.pulumi.incus.NetworkArgs;
import com.pulumi.incus.Profile;
import com.pulumi.incus.ProfileArgs;
import com.pulumi.incus.Project;
import com.pulumi.incus.ProjectArgs;
import com.pulumi.incus.inputs.GetInstancePlainArgs;
import com.pulumi.incus.inputs.GetNetworkPlainArgs;
import com.pulumi.incus.inputs.GetProfilePlainArgs;
import com.pulumi.incus.inputs.GetProjectPlainArgs;
import com.pulumi.incus.inputs.InstanceDeviceArgs;
import com.pulumi.incus.inputs.ProfileDeviceArgs;
import com.pulumi.resources.CustomResourceOptions;
import com.pulumi.resources.Resource;
import io.nxmatic.rk2lab.controlplane.SeedLog;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig.WorktreeHost;
import io.nxmatic.rk2lab.controlplane.incus.image.PulumiIncusImageProvider;
import io.nxmatic.rk2lab.controlplane.pipeline.OnFailure;
import io.nxmatic.rk2lab.controlplane.pipeline.TopicRunner;
import io.nxmatic.rk2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rk2lab.manifests.api.ManifestExplodeRequest;
import io.nxmatic.rk2lab.manifests.api.ManifestExplodeResult;
import io.nxmatic.rk2lab.manifests.api.ManifestExplodeService;
import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisRequest;
import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisService;
import io.nxmatic.rk2lab.manifests.api.ManifestYaml;
import io.nxmatic.rk2lab.manifests.layers.common.profiles.ComponentVersions;
import io.nxmatic.rk2lab.manifests.layers.common.profiles.FloxDebugPolicy;
import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContext;
import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContributor;
import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContributorRegistry;
import io.nxmatic.rk2lab.netplan.ClusterNetworkBlueprint;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Provider-native Stage A bootstrap resources for the Incus management seed node. */
public final class IncusResourceBootstrap {

  private static final List<String> CLUSTER_NODE_NAMES =
      List.of("master", "peer1", "peer2", "peer3", "worker1", "worker2");

  private static final class DaemonsetLogPolicy {

    private static final String HOST_SOURCE_DIRECTORY_NAME = "k8s-daemonset.d";

    private static final String GUEST_ROOT_PATH = "/srv/host/k8s-daemonset.d";

    private DaemonsetLogPolicy() {}
  }

  private static final String HOST_ROOT_PATH = "/srv/host";

  private static final String HOST_WORKTREE_PATH = "/srv/host/rke2lab-worktree.d";

  private static final String HOST_ENV_DIR_PATH = "/srv/host/rke2lab-environment.d";

  private final ManifestFileOperations manifestFileOps = ManifestFileOperations.INSTANCE;

  private static final String HOST_SCRIPTS_DIR_PATH = "/srv/host/systemd-scripts.d";

  private static final String HOST_GIT_WORKTREE_DIR_PATH = "/srv/host/git-worktree.d";

  private static final String HOST_SYSTEMD_LIBEXEC_DIR_PATH = "/srv/host/systemd-libexec.d";

  private static final String HOST_SYSTEMD_DIR_PATH = "/srv/host/systemd-units.d";

  private static final String HOST_MANIFESTS_DIR_PATH = "/srv/host/rke2-manifests.d";

  private static final String HOST_RKE2_CONFIG_DIR_PATH = "/srv/host/rke2-config.d";

  private static final String HOST_CLOUDCONFIG_NO_CLOUD_DIR_PATH =
      "/srv/host/cloudconfig-nocloud.d";

  private static final String HOST_SHARE_DIR_PATH = "/srv/host/rke2lab-share.d";

  private static final String HOST_KUBECONFIG_DIR_PATH = "/srv/host/rke2lab-kube.d";

  private final BootstrapConfig config;

  private final ControlplanePolicy policy;

  private final PulumiIncusImageProvider imageProvider;

  private final HostMountSourceVerifier hostMountSourceVerifier;

  private final NodeConfigRegenerator nodeConfigRegenerator;

  private final RuntimeEnvControlplaneOverlayWriter runtimeEnvControlplaneOverlayWriter;

  private final ClasspathAssetMaterializer classpathAssetMaterializer;

  private final IncusImportLookup incusImportLookup;

  private final LaunchSecretsUpdater launchSecretsUpdater;

  public IncusResourceBootstrap(BootstrapConfig config, ControlplanePolicy policy) {
    this.config = config;
    this.policy = policy;
    this.imageProvider = new PulumiIncusImageProvider(config);
    this.hostMountSourceVerifier = HostMountSourceVerifier.INSTANCE;
    this.nodeConfigRegenerator = new NodeConfigRegenerator(CloudConfigSecretRenderer.INSTANCE);
    this.runtimeEnvControlplaneOverlayWriter = RuntimeEnvControlplaneOverlayWriter.INSTANCE;
    this.classpathAssetMaterializer = ClasspathAssetMaterializer.INSTANCE;
    this.incusImportLookup = IncusImportLookup.INSTANCE;
    this.launchSecretsUpdater = LaunchSecretsUpdater.INSTANCE;
  }

  /**
   * Materialize seed resources directly via the Incus provider. Reads as: resolve paths, then
   * prepare host state, then prepare provider resources, then create the instance.
   */
  public BootstrapResult apply() {
    final ApplyState state = new ApplyState();
    return new ApplyStart(state)
        .onFailure((topic, cause) -> SeedLog.error("incus", topic + ": " + cause.getMessage()))
        .during("path resolution", paths -> paths.resolve())
        .then()
        .during("host state", host -> host.materializeAssets().ensureSecrets().logSummary())
        .then()
        .during(
            "provider resources",
            provider -> provider.ensureProject().ensureNetworks().ensureProfile().ensureImage())
        .then()
        .during("instance", instance -> instance.create())
        .toResult();
  }

  /** Shared mutable state across all pipeline stages. */
  private static final class ApplyState {
    OnFailure onFailure;
    BootstrapPaths localPaths;
    BootstrapPaths nixosPaths;
    IncusProviderContext providerContext;
    Project ensuredProject;
    Output<String> ensuredProjectName;
    Output<String> ensuredProfileName;
    Output<String> ensuredImageFingerprint;
    DeploymentMetadata deploymentMetadata;
    ProvisioningMetadata provisioningMetadata;
    BuildMetadata buildMetadata;
    RuntimeMetadata runtimeMetadata;
    Instance instance;
  }

  /** Topic stages — each holds a reference to the shared ApplyState. */
  private static final class PathStage {
    private final ApplyState state;
    private final BootstrapConfig config;

    PathStage(ApplyState state, BootstrapConfig config) {
      this.state = state;
      this.config = config;
    }

    PathStage resolve() {
      final Path localWorktreeRoot = config.worktreeDirOn(WorktreeHost.DARWIN);
      state.localPaths =
          BootstrapPaths.fromLocalWorktree(
              localWorktreeRoot, config.clusterName(), config.nodeName());
      state.nixosPaths = state.localPaths.asHostView(config, WorktreeHost.NIXOS);
      return this;
    }
  }

  private final class HostStage {
    private final ApplyState state;
    private final BootstrapConfig config;
    private final ControlplanePolicy policy;

    HostStage(ApplyState state, BootstrapConfig config, ControlplanePolicy policy) {
      this.state = state;
      this.config = config;
      this.policy = policy;
    }

    HostStage materializeAssets() {
      final boolean dryRun = Deployment.getInstance().isDryRun();
      logInfo("mode=" + (dryRun ? "preview" : "apply"));

      // Preview reuses a fixed `host.preview` slot so it never consumes a retention slot or syncs
      // to host/. Apply allocates a numbered slot in [0, retentionCount) and rsyncs it onto host/.
      final HostAssetRootLifecycle lifecycle =
          dryRun
              ? HostAssetRootLifecycle.previewLifecycle()
              : new HostAssetRootLifecycle(config.hostAssetRotationRetentionCount());

      final StagingContext staging = materializeToStaging(lifecycle);
      final SliceContext slices = registerProvisioningSlices(staging);
      // Capture metadata BEFORE syncing: the sync step may move the scratch dir into a numbered
      // slot, after which the slice registry's recorded paths would no longer resolve. Metadata
      // capture only reads from the registry and in-memory summaries, so it's safe to do first.
      captureDeploymentMetadata(staging, slices);
      if (!dryRun && syncStagingToFinal(lifecycle, staging.stagingRoot())) {
        // Host content actually changed -- the kubeconfig may now point at an instance that's
        // about to be replaced, so wipe it so readiness re-publishes a fresh one. Skip on no-op
        // deploys: nothing rotated, the existing kubeconfig still matches the running instance.
        clearStaleBootstrapKubeconfig();
      }

      return this;
    }

    HostStage ensureSecrets() {
      ensureLaunchSecretsToken(state.localPaths.secretsFile());
      return this;
    }

    HostStage logSummary() {
      logInfo("deployment=" + state.deploymentMetadata);
      logInfo("provisioning.slices=" + state.provisioningMetadata.slices());
      return this;
    }

    private StagingContext materializeToStaging(HostAssetRootLifecycle lifecycle) {
      final Path stagingRoot = lifecycle.prepareStagingRoot(state.localPaths.assetsRoot());
      final Path stagingManifestsRoot =
          stagingRoot.resolve(
              state.localPaths.assetsRoot().relativize(state.localPaths.manifestsRoot()));

      classpathAssetMaterializer.materializeIncusAssets(stagingRoot);
      classpathAssetMaterializer.materializeManifests(stagingManifestsRoot);

      final LayerEnvContext layerContext = new DefaultBootstrapLayerEnvContext();
      final Map<String, Object> manifestSynthSummary =
          synthesizeAndExplodeManifests(stagingManifestsRoot, policy, layerContext);

      final BootstrapPaths stagingPaths = createStagingPaths(stagingRoot);

      return new StagingContext(
          stagingRoot, stagingPaths, stagingManifestsRoot, layerContext, manifestSynthSummary);
    }

    private SliceContext registerProvisioningSlices(StagingContext staging) {
      final ProvisioningSliceRegistry sliceRegistry = new ProvisioningSliceRegistry();

      registerCoreSlice(sliceRegistry, staging);
      registerNodeSlice(sliceRegistry, staging.stagingPaths());
      final Map<String, Object> runtimeSummaries =
          materializeAndRegisterRuntimeConfig(sliceRegistry, staging);

      return new SliceContext(sliceRegistry, runtimeSummaries);
    }

    private void registerCoreSlice(
        ProvisioningSliceRegistry sliceRegistry, StagingContext staging) {
      final BootstrapPaths stagingPaths = staging.stagingPaths();
      final CoreSlice coreSlice =
          new CoreSlice(
              nodeConfigRegenerator,
              stagingPaths.runtimeCloudConfigRoot(),
              stagingPaths.cloudSeedRoot(),
              staging.stagingManifestsRoot());
      try {
        coreSlice.materialize(stagingPaths);
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to materialize core slice", ex);
      }
      sliceRegistry.register(coreSlice);
    }

    private void registerNodeSlice(
        ProvisioningSliceRegistry sliceRegistry, BootstrapPaths stagingPaths) {
      final NodeSlice nodeSlice = new NodeSlice();
      try {
        nodeSlice.materialize(stagingPaths);
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to materialize node slice", ex);
      }
      sliceRegistry.register(nodeSlice);
    }

    private Map<String, Object> materializeAndRegisterRuntimeConfig(
        ProvisioningSliceRegistry sliceRegistry, StagingContext staging) {
      final BootstrapPaths stagingPaths = staging.stagingPaths();
      final LayerEnvContext layerContext = staging.layerContext();

      final List<String> hostMountNotes = hostMountSourceVerifier.ensureSources(stagingPaths);
      final Map<String, Object> systemdProvisioningSummary =
          SystemdProvisioningInventory.summarize(stagingPaths, hostMountNotes);

      final RuntimeConfigSlice runtimeConfigSlice =
          new RuntimeConfigSlice(
              runtimeEnvControlplaneOverlayWriter,
              layerContext,
              policy,
              stagingPaths.runtimeRke2ConfigRoot(),
              stagingPaths.runtimeEnvConfigRoot());
      try {
        runtimeConfigSlice.materialize(stagingPaths);
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to materialize runtimeConfig slice", ex);
      }
      sliceRegistry.register(runtimeConfigSlice);

      return Map.of(
          "systemd", systemdProvisioningSummary, "layerEnv", runtimeConfigSlice.layerEnvSummary());
    }

    private boolean syncStagingToFinal(HostAssetRootLifecycle lifecycle, Path stagingRoot) {
      return lifecycle.syncStagingToFinal(stagingRoot, state.localPaths.assetsRoot());
    }

    private void captureDeploymentMetadata(StagingContext staging, SliceContext slices) {
      final ProvisioningMetadata.Slices provisioningSlices =
          ProvisioningResourceInventory.sliceChecksums(state.localPaths, slices.sliceRegistry());

      final String hostSourceDirRelative =
          state.localPaths.relativizeAgainst(state.localPaths.worktreeRoot());

      @SuppressWarnings("unchecked")
      final Map<String, Object> layerEnvSummary =
          (Map<String, Object>) slices.runtimeSummaries().get("layerEnv");
      @SuppressWarnings("unchecked")
      final Map<String, Object> systemdSummary =
          (Map<String, Object>) slices.runtimeSummaries().get("systemd");

      state.deploymentMetadata = DeploymentMetadata.capture();
      state.provisioningMetadata =
          new ProvisioningMetadata(
              provisioningSlices, new ProvisioningMetadata.Paths(hostSourceDirRelative));
      state.buildMetadata =
          new BuildMetadata(null, BuildMetadata.Manifests.of(staging.manifestSynthSummary()));
      state.runtimeMetadata =
          new RuntimeMetadata(
              RuntimeMetadata.Environment.of(layerEnvSummary),
              RuntimeMetadata.Systemd.of(systemdSummary));
    }

    private BootstrapPaths createStagingPaths(Path stagingRoot) {
      return state.localPaths.asStagingView(stagingRoot);
    }

    private record StagingContext(
        Path stagingRoot,
        BootstrapPaths stagingPaths,
        Path stagingManifestsRoot,
        LayerEnvContext layerContext,
        Map<String, Object> manifestSynthSummary) {}

    private record SliceContext(
        ProvisioningSliceRegistry sliceRegistry, Map<String, Object> runtimeSummaries) {}
  }

  private void clearStaleBootstrapKubeconfig() {
    if (Deployment.getInstance().isDryRun()) {
      return;
    }

    final Path kubeconfigPath = config.kubeconfigRef().toAbsolutePath().normalize();
    try {
      Files.deleteIfExists(kubeconfigPath);
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Failed to clear stale bootstrap kubeconfig before readiness gating: " + kubeconfigPath,
          ex);
    }
  }

  private Map<String, Object> synthesizeAndExplodeManifests(
      Path manifestsRoot, ControlplanePolicy policy, LayerEnvContext layerContext) {
    final long startedAt = System.nanoTime();
    Path synthScratch = null;
    try {
      synthScratch = Files.createTempDirectory("rke2lab-synth-");
      final Path consolidated = synthScratch.resolve("manifests.yaml");
      final FloxDebugPolicy floxDebugPolicy = resolveFloxDebugPolicy(policy);

      manifestFileOps.wipeExplodedLayers(manifestsRoot);

      synthesizeManifests(synthScratch, consolidated, layerContext, floxDebugPolicy);
      final ManifestExplodeResult explodeResult = explodeManifests(consolidated, manifestsRoot);
      final Map<String, Object> summary =
          buildManifestSynthSummary(manifestsRoot, explodeResult, floxDebugPolicy);

      logManifestSynthesisComplete(startedAt, floxDebugPolicy, summary);
      return summary;
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to synthesize/explode manifests", ex);
    } finally {
      if (synthScratch != null) {
        manifestFileOps.deleteSynthScratchSilently(synthScratch);
      }
    }
  }

  private FloxDebugPolicy resolveFloxDebugPolicy(ControlplanePolicy policy) {
    return policy.debug().floxNriPluginEnabled()
        ? FloxDebugPolicy.debug()
        : FloxDebugPolicy.disabled();
  }

  private void synthesizeManifests(
      Path synthScratch,
      Path consolidated,
      LayerEnvContext layerContext,
      FloxDebugPolicy floxDebugPolicy)
      throws IOException {
    final ComponentVersions componentVersions = ComponentVersions.defaults();

    final ManifestSynthesisRequest synthRequest =
        new ManifestSynthesisRequest(synthScratch, consolidated)
            .withFloxDebugPolicy(floxDebugPolicy)
            .withBootstrapIdentity(layerContext.bootstrapIdentity())
            .withNetworkTopology(layerContext.networkTopology())
            .withComponentVersions(componentVersions);

    final ManifestSynthesisService synthesizer = singleSpiProvider(ManifestSynthesisService.class);
    synthesizer.synthesize(synthRequest);
  }

  private ManifestExplodeResult explodeManifests(Path consolidated, Path manifestsRoot)
      throws IOException {
    final ManifestExplodeService exploder = singleSpiProvider(ManifestExplodeService.class);
    return exploder.explode(new ManifestExplodeRequest(consolidated, manifestsRoot));
  }

  private void logManifestSynthesisComplete(
      long startedAt, FloxDebugPolicy floxDebugPolicy, Map<String, Object> summary) {
    logInfo(
        "phase prepareHostState: manifests synthesized + exploded after "
            + elapsedSince(startedAt)
            + " (floxDebugPolicy.enabled="
            + floxDebugPolicy.enabled()
            + ", checksum="
            + summary.get("checksum")
            + ", fileCount="
            + summary.get("fileCount")
            + ")");
  }

  private Map<String, Object> buildManifestSynthSummary(
      Path manifestsRoot, ManifestExplodeResult explodeResult, FloxDebugPolicy floxDebugPolicy) {
    final List<Path> writtenFiles = explodeResult.writtenFiles();

    return Map.of(
        "checksum", computeManifestChecksum(manifestsRoot, writtenFiles),
        "fileCount", writtenFiles.size(),
        "layers", countLayers(manifestsRoot, writtenFiles),
        "byLayer", groupByLayer(manifestsRoot, writtenFiles),
        "floxDebugEnabled", floxDebugPolicy.enabled());
  }

  private String computeManifestChecksum(Path manifestsRoot, List<Path> writtenFiles) {
    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }

    for (Path file : writtenFiles) {
      try {
        digest.update(manifestsRoot.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(Files.readAllBytes(file));
        digest.update((byte) 0);
      } catch (IOException ex) {
        throw new IllegalStateException("Failed reading exploded manifest: " + file, ex);
      }
    }

    return HexFormat.of().formatHex(digest.digest());
  }

  private Map<String, Integer> groupByLayer(Path manifestsRoot, List<Path> writtenFiles) {
    final LinkedHashMap<String, Integer> byLayer = new LinkedHashMap<>();
    for (Path file : writtenFiles) {
      final Path relative = manifestsRoot.relativize(file);
      if (relative.getNameCount() == 0) {
        continue;
      }
      final String layer = relative.getName(0).toString();
      byLayer.merge(layer, 1, (existing, increment) -> existing + increment);
    }
    return Map.copyOf(byLayer);
  }

  private int countLayers(Path manifestsRoot, List<Path> writtenFiles) {
    return groupByLayer(manifestsRoot, writtenFiles).size();
  }

  private static final class ManifestFileOperations {
    private static final ManifestFileOperations INSTANCE = new ManifestFileOperations();

    private ManifestFileOperations() {}

    void wipeExplodedLayers(Path manifestsRoot) throws IOException {
      if (!Files.isDirectory(manifestsRoot)) {
        return;
      }
      try (Stream<Path> children = Files.list(manifestsRoot)) {
        for (Path child : children.toList()) {
          if ("host".equals(child.getFileName().toString())) {
            continue;
          }
          deleteSubtree(child);
        }
      }
    }

    void deleteSubtree(Path root) throws IOException {
      if (!Files.exists(root)) {
        return;
      }
      try (Stream<Path> stream = Files.walk(root)) {
        final List<Path> entries = stream.sorted(java.util.Comparator.reverseOrder()).toList();
        for (Path entry : entries) {
          Files.deleteIfExists(entry);
        }
      }
    }

    void deleteSynthScratchSilently(Path scratch) {
      try (Stream<Path> stream = Files.walk(scratch)) {
        stream
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(
                entry -> {
                  try {
                    Files.deleteIfExists(entry);
                  } catch (IOException ignored) {
                    // best-effort cleanup
                  }
                });
      } catch (IOException ignored) {
        // synthScratch already gone or unreadable
      }
    }
  }

  private <T> T singleSpiProvider(Class<T> serviceType) {
    final List<T> providers =
        ServiceLoader.load(serviceType).stream().map(ServiceLoader.Provider::get).toList();
    if (providers.isEmpty()) {
      throw new IllegalStateException(
          "No " + serviceType.getSimpleName() + " provider found via ServiceLoader.");
    }
    if (providers.size() > 1) {
      throw new IllegalStateException(
          "Expected exactly one "
              + serviceType.getSimpleName()
              + " provider, found "
              + providers.size());
    }
    return providers.getFirst();
  }

  private final class ProviderStage {
    private final ApplyState state;
    private final BootstrapConfig config;
    private final ControlplanePolicy policy;
    private final PulumiIncusImageProvider imageProvider;

    ProviderStage(
        ApplyState state,
        BootstrapConfig config,
        ControlplanePolicy policy,
        PulumiIncusImageProvider imageProvider) {
      this.state = state;
      this.config = config;
      this.policy = policy;
      this.imageProvider = imageProvider;
    }

    ProviderStage ensureProject() {
      state.providerContext = IncusProviderContext.forBootstrap("seed-incus-provider", config);
      state.ensuredProject = IncusResourceBootstrap.this.ensureProject(state.providerContext);
      state.ensuredProjectName = state.ensuredProject.name();
      return this;
    }

    ProviderStage ensureNetworks() {
      IncusResourceBootstrap.this.ensureNetwork(
          state.providerContext, config.lanBridgeParent(), state.ensuredProject);
      IncusResourceBootstrap.this.ensureNetwork(
          state.providerContext, config.vmnetNetworkName(), state.ensuredProject);
      return this;
    }

    ProviderStage ensureProfile() {
      state.ensuredProfileName =
          IncusResourceBootstrap.this.ensureProfile(state.providerContext, state.ensuredProject);
      return this;
    }

    ProviderStage ensureImage() {
      state.ensuredImageFingerprint =
          imageProvider.ensureSeedImageFingerprint(
              state.providerContext.invokeOptions(),
              state.providerContext.provider(),
              state.ensuredProject);
      state.buildMetadata =
          new BuildMetadata(
              new BuildMetadata.Image(imageProvider.buildChecksum()),
              state.buildMetadata.manifests());
      return this;
    }
  }

  private final class InstanceStage {
    private final ApplyState state;
    private final BootstrapConfig config;

    InstanceStage(ApplyState state, BootstrapConfig config) {
      this.state = state;
      this.config = config;
    }

    InstanceStage create() {
      final Map<String, String> instanceConfig = new LinkedHashMap<>();
      instanceConfig.put(
          "raw.lxc",
          String.join(
              "\n",
              "lxc.mount.auto = proc:rw sys:rw cgroup:rw",
              "lxc.apparmor.profile = unconfined",
              "lxc.cap.drop ="));
      instanceConfig.put("security.privileged", "true");
      instanceConfig.put("security.nesting", "true");
      instanceConfig.put("security.syscalls.intercept.bpf", "true");
      instanceConfig.put("security.syscalls.intercept.bpf.devices", "true");

      for (Map.Entry<String, String> entry :
          state.provisioningMetadata.slices().staticSlices().entrySet()) {
        instanceConfig.put("user.rke2lab.provisioning.slice." + entry.getKey(), entry.getValue());
      }

      instanceConfig.put("user.rke2lab.imageBuildChecksum", state.buildMetadata.image().checksum());

      state.instance =
          new Instance(
              "seed-instance",
              InstanceArgs.builder()
                  .name(config.nodeName())
                  .project(state.ensuredProjectName)
                  .image(state.ensuredImageFingerprint)
                  .profiles(state.ensuredProfileName.applyValue(List::of))
                  .config(instanceConfig)
                  .running(true)
                  .devices(seedInstanceDevices(state.nixosPaths))
                  .build(),
              instanceOptions());
      return this;
    }

    private CustomResourceOptions instanceOptions() {
      return CustomResourceOptions.builder()
          .provider(state.providerContext.provider())
          .deleteBeforeReplace(true)
          .replaceOnChanges(List.of("config", "config.*"))
          .ignoreChanges(List.of("image"))
          .build();
    }
  }

  /** State-machine entry / transitions for the apply pipeline. */
  private final class ApplyStart {
    private final ApplyState state;

    ApplyStart(ApplyState state) {
      this.state = state;
    }

    /** Optional: register a per-topic failure handler. Defaults to no-op when not called. */
    ApplyStart onFailure(OnFailure handler) {
      state.onFailure = handler;
      return this;
    }

    PathDone during(String topic, java.util.function.Function<PathStage, PathStage> body) {
      TopicRunner.runDuring("incus", topic, new PathStage(state, config), body, state.onFailure);
      return new PathDone(state);
    }
  }

  private final class PathDone {
    private final ApplyState state;

    PathDone(ApplyState state) {
      this.state = state;
    }

    AwaitingHost then() {
      return new AwaitingHost(state);
    }
  }

  private final class AwaitingHost {
    private final ApplyState state;

    AwaitingHost(ApplyState state) {
      this.state = state;
    }

    HostDone during(String topic, java.util.function.Function<HostStage, HostStage> body) {
      TopicRunner.runDuring(
          "incus", topic, new HostStage(state, config, policy), body, state.onFailure);
      return new HostDone(state);
    }
  }

  private final class HostDone {
    private final ApplyState state;

    HostDone(ApplyState state) {
      this.state = state;
    }

    AwaitingProvider then() {
      return new AwaitingProvider(state);
    }
  }

  private final class AwaitingProvider {
    private final ApplyState state;

    AwaitingProvider(ApplyState state) {
      this.state = state;
    }

    ProviderDone during(
        String topic, java.util.function.Function<ProviderStage, ProviderStage> body) {
      TopicRunner.runDuring(
          "incus",
          topic,
          new ProviderStage(state, config, policy, imageProvider),
          body,
          state.onFailure);
      return new ProviderDone(state);
    }
  }

  private final class ProviderDone {
    private final ApplyState state;

    ProviderDone(ApplyState state) {
      this.state = state;
    }

    AwaitingInstance then() {
      return new AwaitingInstance(state);
    }
  }

  private final class AwaitingInstance {
    private final ApplyState state;

    AwaitingInstance(ApplyState state) {
      this.state = state;
    }

    InstanceDone during(
        String topic, java.util.function.Function<InstanceStage, InstanceStage> body) {
      TopicRunner.runDuring(
          "incus", topic, new InstanceStage(state, config), body, state.onFailure);
      return new InstanceDone(state);
    }
  }

  private final class InstanceDone {
    private final ApplyState state;

    InstanceDone(ApplyState state) {
      this.state = state;
    }

    BootstrapResult toResult() {
      return new BootstrapResult(
          "incus://" + config.incusProject() + "/" + config.nodeName(),
          state.ensuredImageFingerprint,
          state.instance.status(),
          state.instance.urn(),
          state.providerContext.provider().urn(),
          state.deploymentMetadata,
          state.provisioningMetadata,
          state.buildMetadata,
          state.runtimeMetadata,
          state.instance);
    }
  }

  private static void logInfo(String message) {
    SeedLog.debug("bootstrap", message);
  }

  private static String elapsedSince(long startedAtNanos) {
    return Duration.ofNanos(System.nanoTime() - startedAtNanos).toString();
  }

  private Project ensureProject(IncusProviderContext context) {
    final String existingProjectId =
        incusImportLookup.normalizeImportId(
            incusImportLookup.existingProjectId(context, config.incusProject()));

    final CustomResourceOptions.Builder optionsBuilder =
        CustomResourceOptions.builder().provider(context.provider()).retainOnDelete(true);
    if (!existingProjectId.isBlank()) {
      optionsBuilder.importId(existingProjectId);
    }

    return new Project(
        "seed-project",
        ProjectArgs.builder()
            .name(config.incusProject())
            // Enable per-project network namespacing so instance NIC parent
            // references resolve under this project even though the actual
            // bridges (lan-br, vmnet-br) are created in the default project
            // (incus only allows OVN networks in non-default projects).
            .config(Map.of("features.networks", "true"))
            .build(),
        optionsBuilder.build());
  }

  private Output<String> ensureProfile(IncusProviderContext context, Resource projectDependency) {
    final CustomResourceOptions.Builder optionsBuilder =
        CustomResourceOptions.builder()
            .provider(context.provider())
            .retainOnDelete(true)
            .dependsOn(List.of(projectDependency))
            .ignoreChanges(List.of("name", "project", "devices", "config", "description"));

    final ProfileArgs.Builder profileArgsBuilder =
        ProfileArgs.builder().name(config.profileName()).project(config.incusProject());
    profileArgsBuilder.devices(
        ProfileDeviceArgs.builder()
            .name("root")
            .type("disk")
            .properties(Map.of("path", "/", "pool", "default"))
            .build());

    final Profile profile =
        new Profile("seed-profile", profileArgsBuilder.build(), optionsBuilder.build());

    return profile.name();
  }

  private void ensureLaunchSecretsToken(Path secretsFile) {
    if (Deployment.getInstance().isDryRun()) {
      return;
    }
    launchSecretsUpdater.ensureTokensPresent(secretsFile);
  }

  private ClusterNetworkBlueprint deriveBlueprint(String nodeName) {
    return ClusterNetworkBlueprint.builder()
        .cluster(config.clusterName())
        .node(nodeName)
        .deriveRecipeModel()
        .build();
  }

  private List<InstanceDeviceArgs> seedInstanceDevices(BootstrapPaths hostPaths) {
    final ClusterNetworkBlueprint managementNodeBlueprint = deriveBlueprint(config.nodeName());

    return DeviceMountPipeline.builder()
        .lanNic(config.lanBridgeParent(), managementNodeBlueprint.lan().hostMacaddr().value())
        .vmnetNic(config.vmnetNetworkName(), managementNodeBlueprint.wan().hostMacaddr().value())
        .kmsgDevice()
        .zfsDevice()
        .disk("worktree.dir", hostPaths.worktreeRoot(), HOST_WORKTREE_PATH)
        .disk("rke2lab.environment.dir", hostPaths.runtimeEnvConfigRoot(), HOST_ENV_DIR_PATH)
        .disk("rke2lab.scripts.dir", hostPaths.scriptsRoot(), HOST_SCRIPTS_DIR_PATH)
        .disk("git.dir", hostPaths.gitRoot(), HOST_GIT_WORKTREE_DIR_PATH)
        .disk(
            "rke2lab.systemd.libexec.dir",
            hostPaths.systemdLibexecRoot(),
            HOST_SYSTEMD_LIBEXEC_DIR_PATH)
        .disk("rke2lab.system.dir", hostPaths.systemdRoot(), HOST_SYSTEMD_DIR_PATH)
        .disk("manifests.dir", hostPaths.manifestsRoot(), HOST_MANIFESTS_DIR_PATH)
        .disk("rke2.config.dir", hostPaths.runtimeRke2ConfigRoot(), HOST_RKE2_CONFIG_DIR_PATH)
        .disk(
            "cloudconfig.nocloud.dir",
            hostPaths.runtimeCloudConfigRoot(),
            HOST_CLOUDCONFIG_NO_CLOUD_DIR_PATH)
        .disk("shared.dir", hostPaths.shareRoot(), HOST_SHARE_DIR_PATH)
        .disk("daemonset.dir", hostPaths.daemonsetRoot(), DaemonsetLogPolicy.GUEST_ROOT_PATH)
        .disk("kubeconfig.dir", hostPaths.kubeconfigRoot(), HOST_KUBECONFIG_DIR_PATH)
        .disk("nocloud.dir", hostPaths.cloudSeedRoot(), "/var/lib/cloud/seed/nocloud")
        .build();
  }

  private static final class RuntimeEnvControlplaneOverlayWriter {

    private static final RuntimeEnvControlplaneOverlayWriter INSTANCE =
        new RuntimeEnvControlplaneOverlayWriter();

    private RuntimeEnvControlplaneOverlayWriter() {}

    private Map<String, Object> write(
        Path runtimeEnvConfigRoot, LayerEnvContext layerContext, ControlplanePolicy policy) {
      try {
        Files.createDirectories(runtimeEnvConfigRoot);

        // Write layer contributions first
        LayerEnvContributorRegistry registry = new LayerEnvContributorRegistry(layerContext);
        final List<LayerEnvContributor> orderedContributors = registry.orderedContributors();
        registry.writeAllContributions(runtimeEnvConfigRoot);

        // Aggregate all layer contributions and create 99-configmap with merged vars
        Map<String, String> aggregatedVars = new LinkedHashMap<>();

        // Add bootstrap-only constants first; contributor-owned sections override as needed
        aggregatedVars.put("RKE2LAB_REPO_ROOT", HOST_WORKTREE_PATH);
        aggregatedVars.putAll(policy.toEnvMap());

        // Add layer contributions (later ones override earlier)
        final Map<String, String> layerContributionVars = registry.aggregateContributions();
        aggregatedVars.putAll(layerContributionVars);

        final Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("config.kubernetes.io/local-config", "true");
        annotations.put(
            "description.kpt.dev", "Controlplane runtime environment with layer contributions");
        annotations.put(
            "env.rk2lab.nxmatic.io/section", "section-controlplane-layer-contributions");
        annotations.put("rk2lab.nxmatic.io/managed-by", "controlplane");

        final Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("annotations", annotations);
        metadata.put("name", "env-section-controlplane-layer-contributions");

        final Map<String, Object> document = new LinkedHashMap<>();
        document.put("apiVersion", "v1");
        document.put("kind", "ConfigMap");
        document.put("metadata", metadata);
        document.put("data", aggregatedVars);

        final Path overlayPath =
            runtimeEnvConfigRoot.resolve(
                "99-configmap-env-section-controlplane-layer-contributions.yml");
        ManifestYaml.writeDocument(overlayPath, document);
        return buildRegistrySnapshot(orderedContributors, layerContributionVars);

      } catch (IOException ex) {
        throw new IllegalStateException(
            "Failed to write controlplane runtime env override ConfigMap: " + runtimeEnvConfigRoot,
            ex);
      }
    }

    private static Map<String, Object> buildRegistrySnapshot(
        List<LayerEnvContributor> orderedContributors, Map<String, String> layerContributionVars) {
      final List<Map<String, Object>> contributors = new ArrayList<>();
      final List<String> orderedLayers = new ArrayList<>();
      final List<String> contributedSections = new ArrayList<>();

      for (LayerEnvContributor contributor : orderedContributors) {
        final List<String> sections = List.copyOf(contributor.contributedSections());
        orderedLayers.add(contributor.layerId());
        contributedSections.addAll(sections);
        contributors.add(
            Map.of(
                "layerId", contributor.layerId(),
                "contributorClass", contributor.getClass().getName(),
                "sections", sections,
                "sectionCount", sections.size()));
      }

      return Map.of(
          "contributorCount", contributors.size(),
          "contributors", contributors,
          "orderedLayers", List.copyOf(orderedLayers),
          "contributedSections", List.copyOf(contributedSections),
          "contributedSectionCount", contributedSections.size(),
          "aggregatedVariableCount", layerContributionVars.size());
    }
  }

  private final class DefaultBootstrapLayerEnvContext implements LayerEnvContext {

    private final ClusterNetworkBlueprint managementNodeBlueprint =
        deriveBlueprint(config.nodeName());

    @Override
    public Path rootPath() {
      return Path.of(HOST_ROOT_PATH);
    }

    @Override
    public Path envDirPath() {
      return Path.of(HOST_ENV_DIR_PATH);
    }

    @Override
    public Path scriptsDirPath() {
      return Path.of(HOST_SCRIPTS_DIR_PATH);
    }

    @Override
    public Path systemdDirPath() {
      return Path.of(HOST_SYSTEMD_DIR_PATH);
    }

    @Override
    public Path configDirPath() {
      return Path.of(HOST_RKE2_CONFIG_DIR_PATH);
    }

    @Override
    public Path cloudconfigNocloudDirPath() {
      return Path.of(HOST_CLOUDCONFIG_NO_CLOUD_DIR_PATH);
    }

    @Override
    public Path manifestsDirPath() {
      return Path.of(HOST_MANIFESTS_DIR_PATH);
    }

    @Override
    public Path sharedDirPath() {
      return Path.of(HOST_SHARE_DIR_PATH);
    }

    @Override
    public Path kubeconfigDirPath() {
      return Path.of(HOST_KUBECONFIG_DIR_PATH);
    }

    @Override
    public int nodeId() {
      return managementNodeBlueprint.node().id();
    }

    @Override
    public String nodeName() {
      return config.nodeName();
    }

    @Override
    public String nodeKind() {
      return switch (managementNodeBlueprint.node().type()) {
        case SERVER -> "server";
        case AGENT -> "agent";
      };
    }

    @Override
    public int clusterId() {
      return managementNodeBlueprint.cluster().id();
    }

    @Override
    public String clusterName() {
      return config.clusterName();
    }

    @Override
    public String clusterToken() {
      return config.clusterName(); // Using cluster name as token (bioskop)
    }

    @Override
    public String clusterDomain() {
      return "cluster.local";
    }

    @Override
    public String clusterCidr() {
      return managementNodeBlueprint.host().clusterCidr().toString();
    }

    @Override
    public String clusterPodCidr() {
      return "10.42.0.0/16";
    }

    @Override
    public String clusterServiceCidr() {
      return "10.43.0.0/16";
    }

    @Override
    public String nodeHostInetAddr() {
      return managementNodeBlueprint.nodeNetwork().nodeHostInetaddr().getHostAddress();
    }

    @Override
    public String nodeNetworkCidr() {
      return managementNodeBlueprint.nodeNetwork().nodeCidr().toString();
    }

    @Override
    public String nodeNetworkGatewayAddr() {
      return managementNodeBlueprint.nodeNetwork().nodeGatewayInetaddr().getHostAddress();
    }

    @Override
    public String clusterLoadBalancerCidr() {
      return managementNodeBlueprint.loadBalancer().lbCidr().toString();
    }

    @Override
    public String clusterLoadBalancerGatewayAddr() {
      return managementNodeBlueprint.lan().headscaleInetaddr().getHostAddress();
    }

    @Override
    public String lanInterface() {
      return managementNodeBlueprint.interfaces().lanInterface();
    }

    @Override
    public String lanHostInetAddr() {
      return managementNodeBlueprint.lan().hostInetaddr().getHostAddress();
    }

    @Override
    public String lanLoadBalancerCidr() {
      return managementNodeBlueprint.lan().lbCidr().toString();
    }

    @Override
    public String wanInterface() {
      return managementNodeBlueprint.interfaces().wanInterface();
    }

    @Override
    public String vipInterface() {
      return managementNodeBlueprint.interfaces().vipInterface();
    }

    @Override
    public String vipCidr() {
      return managementNodeBlueprint.vip().vipCidr().toString();
    }

    @Override
    public String vipGatewayInetAddr() {
      return managementNodeBlueprint.vip().vipGatewayInetaddr().getHostAddress();
    }

    @Override
    public String vipHostInetAddr() {
      return managementNodeBlueprint.vip().vipHostInetaddr().getHostAddress();
    }
  }

  record BootstrapPaths(
      Path worktreeRoot,
      Path stateRoot,
      Path clusterNodeRoot,
      Path manifestsRoot,
      Path runtimeRke2ConfigRoot,
      Path runtimeCloudConfigRoot,
      Path runtimeEnvConfigRoot,
      Path secretsFile,
      Path assetsRoot,
      Path daemonsetRoot,
      Path scriptsRoot,
      Path systemdLibexecRoot,
      Path systemdRoot,
      Path gitRoot,
      Path shareRoot,
      Path kubeconfigRoot,
      Path cloudSeedRoot) {

    private static Builder builder() {
      return new Builder();
    }

    private static BootstrapPaths fromLocalWorktree(
        Path worktreeRoot, String clusterName, String nodeName) {
      final Path stateRoot = worktreeRoot.resolve(".local.d");
      final Path hostResourceRoot =
          stateRoot
              .resolve("var")
              .resolve("run")
              .resolve("incus")
              .resolve(clusterName)
              .resolve(nodeName)
              .resolve("host");
      final Path clusterNodeRoot =
          stateRoot
              .resolve("var")
              .resolve("lib")
              .resolve("rke2lab")
              .resolve(clusterName)
              .resolve(nodeName);
      final Path manifestsRoot = hostResourceRoot.resolve("manifests.d");
      final Path runtimeRoot = manifestsRoot.resolve("runtime");
      final Path hostRoot = manifestsRoot.resolve("host");
      final Path scriptsRoot = hostRoot.resolve("systemd-scripts");
      final Path systemdLibexecRoot = hostRoot.resolve("systemd-libexec");
      final Path systemdRoot = hostRoot.resolve("systemd-units");

      return BootstrapPaths.builder()
          .worktreeRoot(worktreeRoot)
          .stateRoot(stateRoot)
          .clusterNodeRoot(clusterNodeRoot)
          .manifestsRoot(manifestsRoot)
          .runtimeRke2ConfigRoot(runtimeRoot.resolve("rke2-config"))
          .runtimeCloudConfigRoot(runtimeRoot.resolve("cloud-config"))
          .runtimeEnvConfigRoot(runtimeRoot.resolve("env-config"))
          .secretsFile(worktreeRoot.resolve(".secrets"))
          .assetsRoot(hostResourceRoot)
          .daemonsetRoot(hostResourceRoot.resolve(DaemonsetLogPolicy.HOST_SOURCE_DIRECTORY_NAME))
          .scriptsRoot(scriptsRoot)
          .systemdLibexecRoot(systemdLibexecRoot)
          .systemdRoot(systemdRoot)
          .gitRoot(worktreeRoot.getParent().getParent())
          .shareRoot(stateRoot.resolve("share"))
          .kubeconfigRoot(stateRoot.resolve("var").resolve("kube"))
          .cloudSeedRoot(clusterNodeRoot.resolve("cloud.d"))
          .build();
    }

    private BootstrapPaths asHostView(BootstrapConfig config, WorktreeHost host) {
      return BootstrapPaths.builder()
          .worktreeRoot(config.pathOn(host, worktreeRoot))
          .stateRoot(config.pathOn(host, stateRoot))
          .clusterNodeRoot(config.pathOn(host, clusterNodeRoot))
          .manifestsRoot(config.pathOn(host, manifestsRoot))
          .runtimeRke2ConfigRoot(config.pathOn(host, runtimeRke2ConfigRoot))
          .runtimeCloudConfigRoot(config.pathOn(host, runtimeCloudConfigRoot))
          .runtimeEnvConfigRoot(config.pathOn(host, runtimeEnvConfigRoot))
          .secretsFile(config.pathOn(host, secretsFile))
          .assetsRoot(config.pathOn(host, assetsRoot))
          .daemonsetRoot(config.pathOn(host, daemonsetRoot))
          .scriptsRoot(config.pathOn(host, scriptsRoot))
          .systemdLibexecRoot(config.pathOn(host, systemdLibexecRoot))
          .systemdRoot(config.pathOn(host, systemdRoot))
          .gitRoot(config.pathOn(host, gitRoot))
          .shareRoot(config.pathOn(host, shareRoot))
          .kubeconfigRoot(config.pathOn(host, kubeconfigRoot))
          .cloudSeedRoot(config.pathOn(host, cloudSeedRoot))
          .build();
    }

    private BootstrapPaths asStagingView(Path stagingRoot) {
      final Path originalRoot = assetsRoot;
      return BootstrapPaths.builder()
          .worktreeRoot(worktreeRoot)
          .stateRoot(stateRoot)
          .clusterNodeRoot(clusterNodeRoot)
          .manifestsRoot(stagingRoot.resolve(originalRoot.relativize(manifestsRoot)))
          .runtimeRke2ConfigRoot(
              stagingRoot.resolve(originalRoot.relativize(runtimeRke2ConfigRoot)))
          .runtimeCloudConfigRoot(
              stagingRoot.resolve(originalRoot.relativize(runtimeCloudConfigRoot)))
          .runtimeEnvConfigRoot(stagingRoot.resolve(originalRoot.relativize(runtimeEnvConfigRoot)))
          .secretsFile(secretsFile)
          .assetsRoot(stagingRoot)
          .daemonsetRoot(stagingRoot.resolve(originalRoot.relativize(daemonsetRoot)))
          .scriptsRoot(stagingRoot.resolve(originalRoot.relativize(scriptsRoot)))
          .systemdLibexecRoot(stagingRoot.resolve(originalRoot.relativize(systemdLibexecRoot)))
          .systemdRoot(stagingRoot.resolve(originalRoot.relativize(systemdRoot)))
          .gitRoot(gitRoot)
          .shareRoot(shareRoot)
          .kubeconfigRoot(kubeconfigRoot)
          .cloudSeedRoot(stagingRoot.resolve(originalRoot.relativize(cloudSeedRoot)))
          .build();
    }

    private String relativizeAgainst(Path base) {
      final Path normalizedBase = base.toAbsolutePath().normalize();
      final Path normalizedAssets = assetsRoot.toAbsolutePath().normalize();
      try {
        return normalizedBase.relativize(normalizedAssets).toString();
      } catch (IllegalArgumentException ex) {
        return normalizedAssets.toString();
      }
    }

    private static final class Builder {
      private Path worktreeRoot;
      private Path stateRoot;
      private Path clusterNodeRoot;
      private Path manifestsRoot;
      private Path runtimeRke2ConfigRoot;
      private Path runtimeCloudConfigRoot;
      private Path runtimeEnvConfigRoot;
      private Path secretsFile;
      private Path assetsRoot;
      private Path daemonsetRoot;
      private Path scriptsRoot;
      private Path systemdLibexecRoot;
      private Path systemdRoot;
      private Path gitRoot;
      private Path shareRoot;
      private Path kubeconfigRoot;
      private Path cloudSeedRoot;

      private Builder worktreeRoot(Path value) {
        this.worktreeRoot = value;
        return this;
      }

      private Builder stateRoot(Path value) {
        this.stateRoot = value;
        return this;
      }

      private Builder clusterNodeRoot(Path value) {
        this.clusterNodeRoot = value;
        return this;
      }

      private Builder manifestsRoot(Path value) {
        this.manifestsRoot = value;
        return this;
      }

      private Builder runtimeRke2ConfigRoot(Path value) {
        this.runtimeRke2ConfigRoot = value;
        return this;
      }

      private Builder runtimeCloudConfigRoot(Path value) {
        this.runtimeCloudConfigRoot = value;
        return this;
      }

      private Builder runtimeEnvConfigRoot(Path value) {
        this.runtimeEnvConfigRoot = value;
        return this;
      }

      private Builder secretsFile(Path value) {
        this.secretsFile = value;
        return this;
      }

      private Builder assetsRoot(Path value) {
        this.assetsRoot = value;
        return this;
      }

      private Builder daemonsetRoot(Path value) {
        this.daemonsetRoot = value;
        return this;
      }

      private Builder scriptsRoot(Path value) {
        this.scriptsRoot = value;
        return this;
      }

      private Builder systemdLibexecRoot(Path value) {
        this.systemdLibexecRoot = value;
        return this;
      }

      private Builder systemdRoot(Path value) {
        this.systemdRoot = value;
        return this;
      }

      private Builder gitRoot(Path value) {
        this.gitRoot = value;
        return this;
      }

      private Builder shareRoot(Path value) {
        this.shareRoot = value;
        return this;
      }

      private Builder kubeconfigRoot(Path value) {
        this.kubeconfigRoot = value;
        return this;
      }

      private Builder cloudSeedRoot(Path value) {
        this.cloudSeedRoot = value;
        return this;
      }

      private BootstrapPaths build() {
        return new BootstrapPaths(
            worktreeRoot,
            stateRoot,
            clusterNodeRoot,
            manifestsRoot,
            runtimeRke2ConfigRoot,
            runtimeCloudConfigRoot,
            runtimeEnvConfigRoot,
            secretsFile,
            assetsRoot,
            daemonsetRoot,
            scriptsRoot,
            systemdLibexecRoot,
            systemdRoot,
            gitRoot,
            shareRoot,
            kubeconfigRoot,
            cloudSeedRoot);
      }
    }
  }

  private void ensureNetwork(
      IncusProviderContext context, String networkName, Resource projectDependency) {
    if (networkName.equals(config.lanBridgeParent())) {
      // lan-br is provisioned by the host (NixOS systemd-networkd in
      // nix-darwin-home/modules/nixos/incus.nix);
      // is incus-managed
      // by this bootstrap and falls through to the create path below.
      logInfo(
          "incus network ensure: skipping canonical host-provided bridge (name="
              + networkName
              + ")");
      return;
    }

    final String networkProject =
        networkName.equals(config.vmnetNetworkName()) ? "default" : config.incusProject();

    if (incusImportLookup.isUnmanagedNetwork(context, networkName, networkProject)) {
      // Additional safeguard for any non-canonical network that provider reports unmanaged.
      logInfo(
          "incus network ensure: skipping unmanaged bridge reported by provider (name="
              + networkName
              + ")");
      return;
    }

    final String existingNetworkId =
        incusImportLookup.normalizeImportId(
            incusImportLookup.existingNetworkId(context, networkName, networkProject));

    if (!existingNetworkId.isBlank() && networkName.equals(config.vmnetNetworkName())) {
      // Existing vmnet bridge is the canonical source of truth for Stage A.
      // Managing it via import causes persistent provider import-replacement churn.
      return;
    }

    final NetworkArgs.Builder builder = NetworkArgs.builder().name(networkName).type("bridge");

    if (networkName.equals(config.vmnetNetworkName())) {
      // Incus restricts non-default projects to OVN networks only; bridges must
      // live in the default project. Since the rke2lab project inherits
      // networks from default (features.networks=NO), instances in rke2lab can
      // still reference vmnet-br as a NIC parent.
      builder.project("default");
    } else if (existingNetworkId.isBlank()) {
      builder.project(config.incusProject());
    }

    if (networkName.equals(config.vmnetNetworkName())) {
      builder.config(vmnetBridgeConfig());
    }

    final List<String> networkIgnoreChanges = new ArrayList<>(List.of("project"));
    if (!existingNetworkId.isBlank()) {
      networkIgnoreChanges.add("config");
      networkIgnoreChanges.add("remote");
      networkIgnoreChanges.add("target");
    }

    final CustomResourceOptions.Builder optionsBuilder =
        CustomResourceOptions.builder()
            .provider(context.provider())
            .retainOnDelete(true)
            .dependsOn(List.of(projectDependency))
            .ignoreChanges(networkIgnoreChanges);
    if (!existingNetworkId.isBlank()) {
      optionsBuilder.importId(existingNetworkId);
    }

    new Network("seed-network-" + networkName, builder.build(), optionsBuilder.build());
  }

  private Map<String, String> vmnetBridgeConfig() {
    final ClusterNetworkBlueprint managementNodeBlueprint = deriveBlueprint(config.nodeName());

    final String clusterGatewayWithPrefix =
        managementNodeBlueprint.host().clusterGatewayInetaddr().getHostAddress()
            + "/"
            + managementNodeBlueprint.host().clusterCidr().prefixLength();

    final String dhcpRange = managementNodeBlueprint.wan().dhcpRange();

    final String rawDnsmasq =
        CLUSTER_NODE_NAMES.stream()
            .map(this::deriveBlueprint)
            .map(
                blueprint ->
                    "dhcp-host="
                        + blueprint.wan().hostMacaddr()
                        + ","
                        + blueprint.nodeNetwork().nodeHostInetaddr().getHostAddress()
                        + ","
                        + clusterNodeLeaseHostname(blueprint.node().name()))
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");

    return Map.of(
        "ipv4.address",
        clusterGatewayWithPrefix,
        "ipv4.nat",
        "false",
        "ipv4.dhcp",
        "true",
        "ipv4.dhcp.ranges",
        dhcpRange,
        "dns.mode",
        "none",
        "bridge.driver",
        "native",
        "raw.dnsmasq",
        rawDnsmasq);
  }

  private String clusterNodeLeaseHostname(String nodeName) {
    return config.clusterName() + "-" + nodeName;
  }

  private static final class DeviceMountPipeline {

    private final List<InstanceDeviceArgs> devices = new ArrayList<>();

    private DeviceMountPipeline() {}

    private static DeviceMountPipeline builder() {
      return new DeviceMountPipeline();
    }

    private DeviceMountPipeline lanNic(String parentBridge, String hwaddr) {
      return nic(
          "lan0",
          Map.of("hwaddr", hwaddr, "name", "lan0", "nictype", "bridged", "parent", parentBridge));
    }

    private DeviceMountPipeline vmnetNic(String networkName, String hwaddr) {
      // Reference vmnet-br as a host-interface bridge (parent + nictype) rather
      // than via "network" — vmnet-br lives in the default incus project (only
      // OVN networks are allowed in non-default projects), so a project-scoped
      // network reference would not resolve from this project.
      return nic(
          "vmnet0",
          Map.of("hwaddr", hwaddr, "name", "vmnet0", "nictype", "bridged", "parent", networkName));
    }

    private DeviceMountPipeline kmsgDevice() {
      return unixChar("kmsg.dev", "/dev/kmsg", "/dev/kmsg");
    }

    private DeviceMountPipeline zfsDevice() {
      return unixChar("zfs.dev", "/dev/zfs", "/dev/zfs");
    }

    private DeviceMountPipeline nic(String name, Map<String, String> properties) {
      devices.add(device(name, "nic", properties));
      return this;
    }

    private DeviceMountPipeline unixChar(String name, String source, String path) {
      devices.add(device(name, "unix-char", Map.of("source", source, "path", path)));
      return this;
    }

    private DeviceMountPipeline disk(String name, Path source, String path) {
      devices.add(device(name, "disk", Map.of("source", source.toString(), "path", path)));
      return this;
    }

    private List<InstanceDeviceArgs> build() {
      return List.copyOf(devices);
    }

    private static InstanceDeviceArgs device(
        String name, String type, Map<String, String> properties) {
      return InstanceDeviceArgs.builder().name(name).type(type).properties(properties).build();
    }
  }

  private static final class HostMountSourceVerifier {

    private static final HostMountSourceVerifier INSTANCE = new HostMountSourceVerifier();

    private HostMountSourceVerifier() {}

    private List<String> ensureSources(BootstrapPaths paths) {
      ensureDirectories(
          List.of(
              paths.clusterNodeRoot(),
              paths.cloudSeedRoot(),
              paths.shareRoot(),
              paths.kubeconfigRoot(),
              paths.daemonsetRoot(),
              paths.systemdLibexecRoot()));

      final List<String> missingPaths = new ArrayList<>();

      requirePathExists(paths.secretsFile(), "required secrets file", missingPaths);
      requirePathExists(paths.scriptsRoot(), "required scripts directory", missingPaths);
      requirePathExists(paths.systemdRoot(), "required systemd directory", missingPaths);
      requirePathExists(
          paths.manifestsRoot(), "required generated manifests directory", missingPaths);
      requirePathExists(
          paths.runtimeRke2ConfigRoot(), "required runtime rke2-config directory", missingPaths);
      requirePathExists(
          paths.runtimeCloudConfigRoot(), "required runtime cloud-config directory", missingPaths);
      requirePathExists(
          paths.runtimeEnvConfigRoot(), "required runtime env-config directory", missingPaths);

      if (!missingPaths.isEmpty()) {
        throw new IllegalStateException(
            "Missing required Stage A host source paths for Incus disk devices:\n- "
                + String.join("\n- ", missingPaths));
      }

      final List<String> hostMountNotes = new ArrayList<>();
      addEmptyContributionNote(
          paths.systemdLibexecRoot(),
          "systemd-libexec contribution directory is present but empty; continuing with canonical placeholder behavior",
          hostMountNotes);
      return List.copyOf(hostMountNotes);
    }

    private void ensureDirectories(List<Path> directories) {
      for (Path directory : directories) {
        try {
          Files.createDirectories(directory);
        } catch (IOException ex) {
          throw new IllegalStateException("Failed to prepare required directory: " + directory, ex);
        }
      }
    }

    private void requirePathExists(Path path, String purpose, List<String> missingPaths) {
      if (!Files.exists(path)) {
        missingPaths.add(path + " (" + purpose + ")");
      }
    }

    private void addEmptyContributionNote(Path directory, String note, List<String> notes) {
      if (directory == null || note == null || notes == null) {
        return;
      }

      try (Stream<Path> stream = Files.list(directory)) {
        final boolean hasRegularFiles = stream.anyMatch(Files::isRegularFile);
        if (!hasRegularFiles) {
          notes.add(note);
        }
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to inspect contribution root: " + directory, ex);
      }
    }
  }

  private static final class NodeConfigRegenerator {

    private final CloudConfigSecretRenderer cloudConfigSecretRenderer;

    private NodeConfigRegenerator(CloudConfigSecretRenderer cloudConfigSecretRenderer) {
      this.cloudConfigSecretRenderer = cloudConfigSecretRenderer;
    }

    private void regenerateCloudConfigDir(Path sourceRoot, Path targetDir) {
      ensureDirectories(List.of(targetDir));
      clearRegularFiles(
          targetDir, "Failed to clear node cloud-config directory before regeneration");

      final CloudConfigSecretRenderer.CloudConfigPayload payload =
          cloudConfigSecretRenderer.renderFromManifestSecrets(sourceRoot);
      writeCloudConfigFiles(targetDir, payload);
    }

    private void clearRegularFiles(Path directory, String failurePrefix) {
      try (Stream<Path> existing = Files.list(directory)) {
        existing
            .filter(Files::isRegularFile)
            .forEach(
                path -> {
                  try {
                    Files.delete(path);
                  } catch (IOException ex) {
                    throw new IllegalStateException(failurePrefix + ": " + directory, ex);
                  }
                });
      } catch (IOException ex) {
        throw new IllegalStateException(failurePrefix + ": " + directory, ex);
      }
    }

    private void writeCloudConfigFiles(
        Path targetDir, CloudConfigSecretRenderer.CloudConfigPayload payload) {
      try {
        Files.writeString(
            targetDir.resolve("user-data"), payload.userData(), StandardCharsets.UTF_8);
        Files.writeString(
            targetDir.resolve("meta-data"), payload.metaData(), StandardCharsets.UTF_8);
        Files.writeString(
            targetDir.resolve("network-config"), payload.networkData(), StandardCharsets.UTF_8);
      } catch (IOException ex) {
        throw new IllegalStateException(
            "Failed to write rendered cloud-init files in: " + targetDir, ex);
      }
    }

    private void ensureDirectories(List<Path> directories) {
      for (Path directory : directories) {
        try {
          Files.createDirectories(directory);
        } catch (IOException ex) {
          throw new IllegalStateException("Failed to prepare required directory: " + directory, ex);
        }
      }
    }
  }

  private static final class ClasspathAssetMaterializer {

    private static final ClasspathAssetMaterializer INSTANCE = new ClasspathAssetMaterializer();

    private static final String CLASSPATH_ROOT = "META-INF/io.nxmatic/rk2lab/controlplane";

    private static final String CLASSPATH_HOST_SYSTEMD_SCRIPTS_ROOT =
        CLASSPATH_ROOT + "/incus/manifests/manifests.d/host/systemd-scripts";

    private static final String CLASSPATH_HOST_SYSTEMD_UNITS_ROOT =
        CLASSPATH_ROOT + "/incus/manifests/manifests.d/host/systemd-units";

    private static final String CLASSPATH_MANIFESTS_ROOT =
        CLASSPATH_ROOT + "/incus/manifests/manifests.d";

    private ClasspathAssetMaterializer() {}

    private void materializeIncusAssets(Path assetsTargetRoot) {
      // Keep materialization hook for non-systemd host assets.
    }

    private void materializeHostSystemdAssets(Path hostRoot) {
      materializeResourceTree(
          CLASSPATH_HOST_SYSTEMD_SCRIPTS_ROOT, hostRoot.resolve("systemd-scripts"), true);
      materializeResourceTree(
          CLASSPATH_HOST_SYSTEMD_UNITS_ROOT, hostRoot.resolve("systemd-units"), false);
    }

    private void materializeManifests(Path manifestsTargetRoot) {
      materializeResourceTree(CLASSPATH_MANIFESTS_ROOT, manifestsTargetRoot, false);
    }

    private void materializeResourceTree(
        String classpathRoot, Path targetRoot, boolean scriptsExecutable) {
      try {
        final URL rootUrl = getClass().getClassLoader().getResource(classpathRoot);
        if (rootUrl == null) {
          throw new IllegalStateException("Classpath resource root not found: " + classpathRoot);
        }

        ensureDirectories(List.of(targetRoot));
        clearTargetRoot(targetRoot);

        final String protocol = rootUrl.getProtocol();
        if ("jar".equals(protocol)) {
          copyFromJar(rootUrl, classpathRoot, targetRoot, scriptsExecutable);
          return;
        }

        copyFromDirectory(Path.of(rootUrl.toURI()), targetRoot, scriptsExecutable);
      } catch (Exception ex) {
        throw new IllegalStateException(
            "Failed to materialize classpath resources from " + classpathRoot, ex);
      }
    }

    private void clearTargetRoot(Path targetRoot) {
      if (!Files.exists(targetRoot)) {
        return;
      }
      try (Stream<Path> walk = Files.walk(targetRoot)) {
        walk.sorted((left, right) -> Integer.compare(right.getNameCount(), left.getNameCount()))
            .filter(path -> !path.equals(targetRoot))
            .forEach(
                path -> {
                  try {
                    Files.delete(path);
                  } catch (IOException ex) {
                    throw new IllegalStateException(
                        "Failed to clear target root before materialization: " + targetRoot, ex);
                  }
                });
      } catch (IOException ex) {
        throw new IllegalStateException(
            "Failed to walk target root before materialization: " + targetRoot, ex);
      }
    }

    private void copyFromDirectory(Path classpathRoot, Path targetRoot, boolean scriptsExecutable)
        throws IOException {
      try (Stream<Path> walk = Files.walk(classpathRoot)) {
        walk.filter(Files::isRegularFile)
            .forEach(
                sourcePath -> {
                  final Path relative = classpathRoot.relativize(sourcePath);
                  final Path targetPath = targetRoot.resolve(relative);
                  copyOneFile(sourcePath, targetPath, relative, scriptsExecutable);
                });
      }
    }

    private void copyFromJar(
        URL rootUrl, String classpathRoot, Path targetRoot, boolean scriptsExecutable)
        throws IOException {
      final JarURLConnection connection = (JarURLConnection) rootUrl.openConnection();
      final String root = classpathRoot + "/";
      try (JarFile jarFile = connection.getJarFile()) {
        final Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
          final JarEntry entry = entries.nextElement();
          final String name = entry.getName();
          if (!name.startsWith(root) || entry.isDirectory()) {
            continue;
          }

          final Path relative = Path.of(name.substring(root.length()));
          final Path targetPath = targetRoot.resolve(relative);
          ensureDirectories(List.of(targetPath.getParent()));
          try (var in = jarFile.getInputStream(entry)) {
            Files.copy(in, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
          }
          maybeSetExecutable(targetPath, relative, scriptsExecutable);
        }
      }
    }

    private void copyOneFile(
        Path sourcePath, Path targetPath, Path relative, boolean scriptsExecutable) {
      try {
        ensureDirectories(List.of(targetPath.getParent()));
        Files.copy(sourcePath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        maybeSetExecutable(targetPath, relative, scriptsExecutable);
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to copy classpath asset to " + targetPath, ex);
      }
    }

    private void maybeSetExecutable(Path targetPath, Path relative, boolean scriptsExecutable) {
      if (scriptsExecutable) {
        targetPath.toFile().setExecutable(true, false);
      }
    }

    private void ensureDirectories(List<Path> directories) {
      for (Path directory : directories) {
        if (directory == null) {
          continue;
        }
        try {
          Files.createDirectories(directory);
        } catch (IOException ex) {
          throw new IllegalStateException("Failed to prepare required directory: " + directory, ex);
        }
      }
    }
  }

  private static final class HostAssetRootLifecycle {

    private static final String PREVIEW_SUFFIX = ".preview";
    private static final String SCRATCH_SUFFIX = ".scratch";

    private final int retentionCount;
    private final boolean preview;

    private HostAssetRootLifecycle(int retentionCount) {
      this(retentionCount, false);
    }

    private HostAssetRootLifecycle(int retentionCount, boolean preview) {
      this.retentionCount = retentionCount;
      this.preview = preview;
    }

    /** Lifecycle for {@code pulumi preview} runs: reuses a fixed slot, never rotates or syncs. */
    private static HostAssetRootLifecycle previewLifecycle() {
      return new HostAssetRootLifecycle(0, true);
    }

    private Path prepareStagingRoot(Path hostAssetRoot) {
      try {
        final Path parent = hostAssetRoot.getParent();
        if (parent == null) {
          throw new IllegalStateException(
              "Host asset root has no parent directory: " + hostAssetRoot);
        }

        Files.createDirectories(parent);

        // Apply mode: materialize into a scratch dir first. We only allocate a numbered slot in
        // syncStagingToFinal, after comparing against host/ so a no-op deploy doesn't burn one.
        final String suffix = preview ? PREVIEW_SUFFIX : SCRATCH_SUFFIX;
        final Path stagingRoot =
            hostAssetRoot.resolveSibling(hostAssetRoot.getFileName().toString() + suffix);
        deleteRecursively(stagingRoot);
        Files.createDirectories(stagingRoot);
        return stagingRoot;
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to prepare staging root for: " + hostAssetRoot, ex);
      }
    }

    /** Returns {@code true} if the host asset root actually changed (slot was rotated). */
    private boolean syncStagingToFinal(Path stagingRoot, Path hostAssetRoot) {
      try {
        if (Files.exists(hostAssetRoot) && directoriesAreIdentical(stagingRoot, hostAssetRoot)) {
          // No-op deploy: scratch matches host/ byte-for-byte. Discard scratch, don't rotate.
          deleteRecursively(stagingRoot);
          return false;
        }

        // Promote scratch to a numbered slot before any backup/sync so the slot is the canonical
        // record of this run. Slot eviction (oldest mtime) happens here when retention is full.
        final int slotSeq = allocateSlot(hostAssetRoot);
        final Path slotPath = stagingPathFor(hostAssetRoot, slotSeq);
        deleteRecursively(slotPath);
        deleteRecursively(backupPathFor(hostAssetRoot, slotSeq));
        Files.move(stagingRoot, slotPath);

        if (Files.exists(hostAssetRoot)) {
          // Backup existing state before sync (paired with the staging slot for this run).
          backup(hostAssetRoot, backupPathFor(hostAssetRoot, slotSeq));

          // Sync staging content to final location (preserves mount point inode)
          syncDirectories(slotPath, hostAssetRoot);
        } else {
          // First deployment: copy staging to final location (keep staging for comparison)
          backup(slotPath, hostAssetRoot);
        }
        return true;
      } catch (IOException ex) {
        throw new IllegalStateException(
            "Failed to sync staging to final host asset root: " + hostAssetRoot, ex);
      }
    }

    private boolean directoriesAreIdentical(Path left, Path right) throws IOException {
      final java.util.Set<Path> leftRelativePaths = collectRelativePaths(left);
      final java.util.Set<Path> rightRelativePaths = collectRelativePaths(right);
      if (!leftRelativePaths.equals(rightRelativePaths)) {
        return false;
      }
      for (Path relative : leftRelativePaths) {
        final Path leftPath = left.resolve(relative);
        final Path rightPath = right.resolve(relative);
        if (Files.isRegularFile(leftPath) != Files.isRegularFile(rightPath)) {
          return false;
        }
        if (!Files.isRegularFile(leftPath)) {
          continue;
        }
        if (Files.size(leftPath) != Files.size(rightPath)) {
          return false;
        }
        if (Files.mismatch(leftPath, rightPath) != -1L) {
          return false;
        }
      }
      return true;
    }

    private java.util.Set<Path> collectRelativePaths(Path root) throws IOException {
      final java.util.Set<Path> entries = new java.util.HashSet<>();
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              final Path relative = root.relativize(dir);
              if (!relative.toString().isEmpty()) {
                entries.add(relative);
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              entries.add(root.relativize(file));
              return FileVisitResult.CONTINUE;
            }
          });
      return entries;
    }

    /**
     * Pick a slot in {@code [0, retentionCount)}. Returns the lowest unused slot when one exists,
     * otherwise the slot whose staging dir has the oldest mtime (the caller will overwrite it).
     */
    private int allocateSlot(Path hostAssetRoot) throws IOException {
      if (retentionCount <= 0) {
        return 0;
      }

      int oldestSlot = 0;
      java.nio.file.attribute.FileTime oldestMtime = null;
      for (int seq = 0; seq < retentionCount; seq++) {
        final Path stagingPath = stagingPathFor(hostAssetRoot, seq);
        if (!Files.exists(stagingPath)) {
          return seq;
        }
        final java.nio.file.attribute.FileTime mtime = Files.getLastModifiedTime(stagingPath);
        if (oldestMtime == null || mtime.compareTo(oldestMtime) < 0) {
          oldestMtime = mtime;
          oldestSlot = seq;
        }
      }
      return oldestSlot;
    }

    private Path stagingPathFor(Path hostAssetRoot, int seq) {
      return hostAssetRoot.resolveSibling(
          hostAssetRoot.getFileName().toString() + ".staging." + seq);
    }

    private Path backupPathFor(Path hostAssetRoot, int seq) {
      return hostAssetRoot.resolveSibling(
          hostAssetRoot.getFileName().toString() + ".backup." + seq);
    }

    private void backup(Path source, Path target) throws IOException {
      Files.createDirectories(target);
      Files.walkFileTree(
          source,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException {
              Path targetDir = target.resolve(source.relativize(dir));
              Files.createDirectories(targetDir);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.copy(file, target.resolve(source.relativize(file)));
              return FileVisitResult.CONTINUE;
            }
          });
    }

    private void syncDirectories(Path source, Path target) throws IOException {
      // Collect all paths in source (for copying/updating)
      final java.util.Set<Path> sourcePaths = new java.util.HashSet<>();
      Files.walkFileTree(
          source,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException {
              Path relativePath = source.relativize(dir);
              sourcePaths.add(relativePath);
              Path targetDir = target.resolve(relativePath);
              Files.createDirectories(targetDir);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Path relativePath = source.relativize(file);
              sourcePaths.add(relativePath);
              Path targetFile = target.resolve(relativePath);
              Files.copy(
                  file,
                  targetFile,
                  java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                  java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
              return FileVisitResult.CONTINUE;
            }
          });

      // Collect all paths in target (for deletion of stale entries)
      final java.util.Set<Path> targetPaths = new java.util.HashSet<>();
      if (Files.exists(target)) {
        Files.walkFileTree(
            target,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path relativePath = target.relativize(dir);
                if (!relativePath.toString().isEmpty()) {
                  targetPaths.add(relativePath);
                }
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path relativePath = target.relativize(file);
                targetPaths.add(relativePath);
                return FileVisitResult.CONTINUE;
              }
            });
      }

      // Delete paths in target that don't exist in source (rsync --delete behavior)
      final java.util.List<Path> toDelete =
          targetPaths.stream()
              .filter(p -> !sourcePaths.contains(p))
              .map(target::resolve)
              .sorted(java.util.Comparator.reverseOrder()) // Delete files before dirs
              .toList();

      for (Path path : toDelete) {
        Files.deleteIfExists(path);
      }
    }

    private void deleteRecursively(Path root) throws IOException {
      if (!Files.exists(root)) {
        return;
      }
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.deleteIfExists(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException {
              if (exc != null) {
                throw exc;
              }
              Files.deleteIfExists(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    }
  }

  private static final class CoreSlice implements ProvisioningSlice {
    private final NodeConfigRegenerator nodeConfigRegenerator;
    private final Path runtimeCloudConfigRoot;
    private final Path cloudSeedRoot;
    private final Path manifestsRoot;

    private CoreSlice(
        NodeConfigRegenerator nodeConfigRegenerator,
        Path runtimeCloudConfigRoot,
        Path cloudSeedRoot,
        Path manifestsRoot) {
      this.nodeConfigRegenerator = nodeConfigRegenerator;
      this.runtimeCloudConfigRoot = runtimeCloudConfigRoot;
      this.cloudSeedRoot = cloudSeedRoot;
      this.manifestsRoot = manifestsRoot;
    }

    @Override
    public String name() {
      return "core";
    }

    @Override
    public SliceStoragePolicy storagePolicy() {
      return SliceStoragePolicy.STATIC;
    }

    @Override
    public void materialize(BootstrapPaths paths) throws IOException {
      // Manifests root is filled by classpath materialization + cdk8s synth+explode upstream of
      // slice registration. Cloud-seed (user-data/meta-data/network-config) is the slice's own
      // output, derived deterministically from the runtime/cloud-config ConfigMap.
      nodeConfigRegenerator.regenerateCloudConfigDir(runtimeCloudConfigRoot, cloudSeedRoot);
    }

    @Override
    public List<Path> getMaterializedPaths() {
      return List.of(cloudSeedRoot, manifestsRoot);
    }
  }

  private static final class RuntimeConfigSlice implements ProvisioningSlice {
    private final RuntimeEnvControlplaneOverlayWriter overlayWriter;
    private final LayerEnvContext layerContext;
    private final ControlplanePolicy policy;
    private final Path rke2ConfigRoot;
    private final Path envConfigRoot;
    private Map<String, Object> layerEnvSummary = Map.of();

    private RuntimeConfigSlice(
        RuntimeEnvControlplaneOverlayWriter overlayWriter,
        LayerEnvContext layerContext,
        ControlplanePolicy policy,
        Path rke2ConfigRoot,
        Path envConfigRoot) {
      this.overlayWriter = overlayWriter;
      this.layerContext = layerContext;
      this.policy = policy;
      this.rke2ConfigRoot = rke2ConfigRoot;
      this.envConfigRoot = envConfigRoot;
    }

    @Override
    public String name() {
      return "runtimeConfig";
    }

    @Override
    public SliceStoragePolicy storagePolicy() {
      return SliceStoragePolicy.HOT_RELOAD;
    }

    @Override
    public void materialize(BootstrapPaths paths) throws IOException {
      // rke2-config root is filled by cdk8s synth+explode upstream of slice registration. The
      // env-config root is the slice's own output: per-layer ConfigMap files plus the aggregated
      // 99-configmap overlay.
      layerEnvSummary = overlayWriter.write(envConfigRoot, layerContext, policy);
    }

    @Override
    public List<Path> getMaterializedPaths() {
      return List.of(rke2ConfigRoot, envConfigRoot);
    }

    /** Layer-contributor registry summary captured during {@link #materialize}. */
    Map<String, Object> layerEnvSummary() {
      return layerEnvSummary;
    }
  }

  private static final class IncusImportLookup {

    private static final IncusImportLookup INSTANCE = new IncusImportLookup();

    private static final long PREVIEW_INVOKE_TIMEOUT_SECONDS = 10;

    private static final long APPLY_INVOKE_TIMEOUT_SECONDS = 20;

    private enum LookupState {
      FOUND,
      NOT_FOUND,
      FAILED
    }

    private record LookupResult(String importId, LookupState state, Boolean managed) {

      private static LookupResult found(String importId, Boolean managed) {
        return new LookupResult(importId, LookupState.FOUND, managed);
      }

      private static LookupResult notFound() {
        return new LookupResult("", LookupState.NOT_FOUND, null);
      }

      private static LookupResult failed() {
        return new LookupResult("", LookupState.FAILED, null);
      }
    }

    private IncusImportLookup() {}

    private long invokeTimeoutSeconds() {
      try {
        return Deployment.getInstance().isDryRun()
            ? PREVIEW_INVOKE_TIMEOUT_SECONDS
            : APPLY_INVOKE_TIMEOUT_SECONDS;
      } catch (Exception ignored) {
        return APPLY_INVOKE_TIMEOUT_SECONDS;
      }
    }

    private String existingProjectId(IncusProviderContext context, String projectName) {
      final long startedAt = System.nanoTime();
      logInfo("incus lookup getProject: start name=" + projectName);
      try {
        final var project =
            IncusFunctions.getProjectPlain(
                    GetProjectPlainArgs.builder().name(projectName).build(),
                    context.invokeOptions())
                .orTimeout(invokeTimeoutSeconds(), TimeUnit.SECONDS)
                .join();
        if (project == null) {
          logInfo(
              "incus lookup getProject: complete after "
                  + elapsedSince(startedAt)
                  + " (result=not-found)");
          return "";
        }

        final String providerId = normalizeImportId(project.id());
        if (!providerId.isBlank()) {
          logInfo(
              "incus lookup getProject: complete after "
                  + elapsedSince(startedAt)
                  + " (result=id)");
          return providerId;
        }

        logInfo(
            "incus lookup getProject: complete after "
                + elapsedSince(startedAt)
                + " (result=name)");
        return normalizeImportId(project.name());
      } catch (Exception ex) {
        logInfo(
            "incus lookup getProject: failed after "
                + elapsedSince(startedAt)
                + " ("
                + summarizeLookupFailure(ex)
                + ")");
        return "";
      }
    }

    private String existingNetworkId(
        IncusProviderContext context, String networkName, String incusProject) {
      final LookupResult projectScoped =
          resolveNetworkImportId(
              context,
              GetNetworkPlainArgs.builder().name(networkName).project(incusProject).build());
      if (projectScoped.state() == LookupState.FOUND) {
        return projectScoped.importId();
      }
      if (projectScoped.state() == LookupState.FAILED) {
        final String fallbackImportId = normalizeImportId(networkName);
        logInfo(
            "incus lookup getNetwork: using deterministic fallback import ID after scoped lookup"
                + " failure"
                + " (name="
                + networkName
                + ", fallbackImportId="
                + fallbackImportId
                + ")");
        return fallbackImportId;
      }

      return resolveNetworkImportId(
              context, GetNetworkPlainArgs.builder().name(networkName).build())
          .importId();
    }

    private String existingInstanceId(
        IncusProviderContext context, String instanceName, String incusProject) {
      final long startedAt = System.nanoTime();
      logInfo("incus lookup getInstance: start name=" + instanceName);
      try {
        final var instance =
            IncusFunctions.getInstancePlain(
                    GetInstancePlainArgs.builder().name(instanceName).project(incusProject).build(),
                    context.invokeOptions())
                .orTimeout(invokeTimeoutSeconds(), TimeUnit.SECONDS)
                .join();
        if (instance == null) {
          logInfo(
              "incus lookup getInstance: complete after "
                  + elapsedSince(startedAt)
                  + " (result=not-found)");
          return "";
        }

        final String providerId = normalizeImportId(instance.id());
        if (!providerId.isBlank()) {
          logInfo(
              "incus lookup getInstance: complete after "
                  + elapsedSince(startedAt)
                  + " (result=id)");
          return providerId;
        }

        logInfo(
            "incus lookup getInstance: complete after "
                + elapsedSince(startedAt)
                + " (result=name)");
        return normalizeImportId(instance.name());
      } catch (Exception ex) {
        logInfo(
            "incus lookup getInstance: failed after "
                + elapsedSince(startedAt)
                + " ("
                + summarizeLookupFailure(ex)
                + ")");
        return "";
      }
    }

    private boolean isUnmanagedNetwork(
        IncusProviderContext context, String networkName, String incusProject) {
      final LookupResult projectScoped =
          resolveNetworkImportId(
              context,
              GetNetworkPlainArgs.builder().name(networkName).project(incusProject).build());
      if (projectScoped.state() == LookupState.FOUND) {
        return Boolean.FALSE.equals(projectScoped.managed());
      }
      if (projectScoped.state() == LookupState.FAILED) {
        logInfo(
            "incus lookup getNetwork(managed): unable to determine managed state after scoped"
                + " lookup failure"
                + " (name="
                + networkName
                + ")");
        return false;
      }

      final LookupResult unscoped =
          resolveNetworkImportId(context, GetNetworkPlainArgs.builder().name(networkName).build());
      if (unscoped.state() == LookupState.FOUND) {
        return Boolean.FALSE.equals(unscoped.managed());
      }
      if (unscoped.state() == LookupState.FAILED) {
        logInfo(
            "incus lookup getNetwork(managed): unable to determine managed state after unscoped"
                + " lookup failure"
                + " (name="
                + networkName
                + ")");
      }
      return false;
    }

    private String existingProfileId(
        IncusProviderContext context, String profileName, String incusProject) {
      return resolveProfileImportId(
          context, GetProfilePlainArgs.builder().name(profileName).project(incusProject).build());
    }

    private LookupResult resolveNetworkImportId(
        IncusProviderContext context, GetNetworkPlainArgs args) {
      final long startedAt = System.nanoTime();
      logInfo("incus lookup getNetwork: start name=" + args.name());
      try {
        final var network =
            IncusFunctions.getNetworkPlain(args, context.invokeOptions())
                .orTimeout(invokeTimeoutSeconds(), TimeUnit.SECONDS)
                .join();
        if (network == null) {
          logInfo(
              "incus lookup getNetwork: complete after "
                  + elapsedSince(startedAt)
                  + " (result=not-found)");
          return LookupResult.notFound();
        }

        final String providerId = normalizeImportId(network.id());
        final Boolean managed = network.managed();
        if (!providerId.isBlank()) {
          logInfo(
              "incus lookup getNetwork: complete after "
                  + elapsedSince(startedAt)
                  + " (result=id)");
          return LookupResult.found(providerId, managed);
        }

        logInfo(
            "incus lookup getNetwork: complete after "
                + elapsedSince(startedAt)
                + " (result=name)");
        return LookupResult.found(normalizeImportId(network.name()), managed);
      } catch (Exception ex) {
        final String summary = summarizeLookupFailure(ex);
        if (isNotFoundFailure(summary)) {
          logInfo(
              "incus lookup getNetwork: complete after "
                  + elapsedSince(startedAt)
                  + " (result=not-found, source=error: "
                  + summary
                  + ")");
          return LookupResult.notFound();
        }
        logInfo(
            "incus lookup getNetwork: failed after "
                + elapsedSince(startedAt)
                + " ("
                + summary
                + ")");
        return LookupResult.failed();
      }
    }

    private boolean isNotFoundFailure(String summary) {
      if (summary == null) {
        return false;
      }
      final String lower = summary.toLowerCase(java.util.Locale.ROOT);
      return lower.contains("not found");
    }

    private String resolveProfileImportId(IncusProviderContext context, GetProfilePlainArgs args) {
      final long startedAt = System.nanoTime();
      logInfo("incus lookup getProfile: start name=" + args.name());
      try {
        final var profile =
            IncusFunctions.getProfilePlain(args, context.invokeOptions())
                .orTimeout(invokeTimeoutSeconds(), TimeUnit.SECONDS)
                .join();
        if (profile == null) {
          logInfo(
              "incus lookup getProfile: complete after "
                  + elapsedSince(startedAt)
                  + " (result=not-found)");
          return "";
        }

        final String providerId = normalizeImportId(profile.id());
        if (!providerId.isBlank()) {
          logInfo(
              "incus lookup getProfile: complete after "
                  + elapsedSince(startedAt)
                  + " (result=id)");
          return providerId;
        }

        logInfo(
            "incus lookup getProfile: complete after "
                + elapsedSince(startedAt)
                + " (result=name)");
        return normalizeImportId(profile.name());
      } catch (Exception ex) {
        logInfo(
            "incus lookup getProfile: failed after "
                + elapsedSince(startedAt)
                + " ("
                + summarizeLookupFailure(ex)
                + ")");
        return "";
      }
    }

    private String summarizeLookupFailure(Exception ex) {
      if (ex == null) {
        return "unknown";
      }

      Throwable root = ex;
      while (root.getCause() != null
          && (root instanceof java.util.concurrent.CompletionException
              || root instanceof java.util.concurrent.ExecutionException)) {
        root = root.getCause();
      }

      final String type = root.getClass().getSimpleName();
      final String message = root.getMessage() == null ? "" : root.getMessage().trim();
      return message.isBlank() ? type : type + ": " + message;
    }

    private String normalizeImportId(String value) {
      if (value == null) {
        return "";
      }
      final String trimmed = value.trim();
      return trimmed.isBlank() ? "" : trimmed;
    }
  }

  private static final class CloudConfigSecretRenderer {

    private static final CloudConfigSecretRenderer INSTANCE = new CloudConfigSecretRenderer();

    private CloudConfigSecretRenderer() {}

    private CloudConfigPayload renderFromManifestSecrets(Path sourceRoot) {
      String userData = null;
      String metaData = null;
      String networkData = null;

      final List<Path> yamlSources = listYamlSources(sourceRoot);
      for (Path yamlSource : yamlSources) {
        final Map<String, Object> document = parseYamlDocument(yamlSource);
        final String kind = asString(document.get("kind"));
        final Map<String, String> payload = extractManifestPayload(kind, document);

        if (payload.containsKey("userData")) {
          userData = payload.get("userData");
        }
        if (payload.containsKey("metaData")) {
          metaData = payload.get("metaData");
        }
        if (payload.containsKey("networkData")) {
          networkData = payload.get("networkData");
        }
      }

      if (userData == null || metaData == null || networkData == null) {
        throw new IllegalStateException(
            "Runtime cloud-config source did not include all required payloads"
                + " (userData, metaData, networkData): "
                + sourceRoot);
      }

      return new CloudConfigPayload(userData, metaData, networkData);
    }

    private List<Path> listYamlSources(Path sourceRoot) {
      try (Stream<Path> sourceEntries = Files.list(sourceRoot)) {
        return sourceEntries
            .filter(Files::isRegularFile)
            .filter(
                path -> {
                  final String name = path.getFileName().toString();
                  return name.endsWith(".yml") || name.endsWith(".yaml");
                })
            .sorted()
            .toList();
      } catch (IOException ex) {
        throw new IllegalStateException(
            "Failed to regenerate node cloud-config directory from runtime cloud-config", ex);
      }
    }

    private Map<String, Object> parseYamlDocument(Path yamlSource) {
      try {
        @SuppressWarnings("unchecked")
        final Map<String, Object> parsed =
            ManifestYaml.mapper().readValue(yamlSource.toFile(), Map.class);
        return parsed;
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to parse YAML manifest: " + yamlSource, ex);
      }
    }

    private Map<String, String> extractManifestPayload(String kind, Map<String, Object> document) {
      final Map<String, String> payload = new LinkedHashMap<>();

      if ("ConfigMap".equals(kind)) {
        payload.putAll(extractStringMap(document.get("data")));
        return payload;
      }

      if (!"Secret".equals(kind)) {
        return payload;
      }

      payload.putAll(extractStringMap(document.get("stringData")));

      final Map<String, String> data = extractStringMap(document.get("data"));
      for (Map.Entry<String, String> entry : data.entrySet()) {
        if (payload.containsKey(entry.getKey())) {
          continue;
        }
        try {
          payload.put(
              entry.getKey(),
              new String(Base64.getDecoder().decode(entry.getValue()), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
          throw new IllegalStateException(
              "Failed to decode Secret data key '" + entry.getKey() + "'", ex);
        }
      }

      return payload;
    }

    private Map<String, String> extractStringMap(Object value) {
      if (!(value instanceof Map<?, ?> mapValue)) {
        return Map.of();
      }

      final LinkedHashMap<String, String> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
        final String key = entry.getKey() == null ? "" : entry.getKey().toString();
        final String mapValueString = entry.getValue() == null ? "" : entry.getValue().toString();
        if (!key.isBlank()) {
          result.put(key, mapValueString);
        }
      }
      return result;
    }

    private String asString(Object value) {
      return value == null ? "" : value.toString();
    }

    private record CloudConfigPayload(String userData, String metaData, String networkData) {}
  }

  private static final class LaunchSecretsUpdater {

    private static final LaunchSecretsUpdater INSTANCE = new LaunchSecretsUpdater();

    private LaunchSecretsUpdater() {}

    private void ensureTokensPresent(Path secretsFile) {
      ensureGithubTokenPresent(secretsFile);
      ensureFloxHubTokenPresent(secretsFile);
    }

    private void ensureGithubTokenPresent(Path secretsFile) {
      final String githubToken = resolveGithubToken();
      if (githubToken.isBlank()) {
        return;
      }

      try {
        final String original = Files.readString(secretsFile, StandardCharsets.UTF_8);
        final String updated = upsertGithubCredentialsPreservingComments(original, githubToken);
        if (!original.equals(updated)) {
          Files.writeString(secretsFile, updated, StandardCharsets.UTF_8);
        }
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to update launch secrets file with gh token", ex);
      }
    }

    private void ensureFloxHubTokenPresent(Path secretsFile) {
      final String floxToken = resolveFloxHubToken();
      if (floxToken.isBlank()) {
        return;
      }

      try {
        final String original = Files.readString(secretsFile, StandardCharsets.UTF_8);
        final String updated = upsertFloxCredentialsPreservingComments(original, floxToken);
        if (!original.equals(updated)) {
          Files.writeString(secretsFile, updated, StandardCharsets.UTF_8);
        }
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to update launch secrets file with flox token", ex);
      }
    }

    private String upsertGithubCredentialsPreservingComments(String content, String githubToken) {
      final String lineSeparator = content.contains("\r\n") ? "\r\n" : "\n";
      final List<String> lines = new ArrayList<>(List.of(content.split("\\r?\\n", -1)));

      final Pattern githubHeaderPattern = Pattern.compile("^([\\t ]*)github:\\s*(#.*)?$");
      final Pattern usernamePattern =
          Pattern.compile("^([\\t ]*username\\s*:\\s*)([^#]*)(\\s*(#.*)?)$");
      final Pattern tokenPattern = Pattern.compile("^([\\t ]*token\\s*:\\s*)([^#]*)(\\s*(#.*)?)$");

      int githubIndex = -1;
      String githubIndent = "";
      for (int i = 0; i < lines.size(); i++) {
        final Matcher matcher = githubHeaderPattern.matcher(lines.get(i));
        if (matcher.matches()) {
          githubIndex = i;
          githubIndent = matcher.group(1);
          break;
        }
      }

      final String usernameValue = yamlSingleQuoted("x-access-token");
      final String tokenValue = yamlSingleQuoted(githubToken);

      if (githubIndex < 0) {
        final String childIndent = "  ";
        if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) {
          lines.add("");
        }
        lines.add("github:");
        lines.add(childIndent + "username: " + usernameValue);
        lines.add(childIndent + "token: " + tokenValue);
        return String.join(lineSeparator, lines);
      }

      final int githubIndentWidth = indentationWidth(githubIndent);
      final String childIndent = githubIndent + "  ";

      int blockStart = githubIndex + 1;
      int blockEndExclusive = lines.size();
      for (int i = blockStart; i < lines.size(); i++) {
        final String line = lines.get(i);
        final String trimmed = line.trim();
        if (trimmed.isEmpty()) {
          continue;
        }
        if (trimmed.startsWith("#")) {
          continue;
        }
        if (indentationWidth(line) <= githubIndentWidth) {
          blockEndExclusive = i;
          break;
        }
      }

      int usernameIndex = -1;
      int tokenIndex = -1;
      for (int i = blockStart; i < blockEndExclusive; i++) {
        final String line = lines.get(i);
        final Matcher usernameMatcher = usernamePattern.matcher(line);
        if (usernameMatcher.matches()) {
          final String suffix = usernameMatcher.group(3) == null ? "" : usernameMatcher.group(3);
          lines.set(i, usernameMatcher.group(1) + usernameValue + suffix);
          usernameIndex = i;
          continue;
        }

        final Matcher tokenMatcher = tokenPattern.matcher(line);
        if (tokenMatcher.matches()) {
          final String suffix = tokenMatcher.group(3) == null ? "" : tokenMatcher.group(3);
          lines.set(i, tokenMatcher.group(1) + tokenValue + suffix);
          tokenIndex = i;
        }
      }

      int insertIndex = blockEndExclusive;
      if (usernameIndex < 0) {
        lines.add(insertIndex, childIndent + "username: " + usernameValue);
        usernameIndex = insertIndex;
        insertIndex++;
        if (tokenIndex >= insertIndex) {
          tokenIndex++;
        }
      }

      if (tokenIndex < 0) {
        lines.add(insertIndex, childIndent + "token: " + tokenValue);
      }

      return String.join(lineSeparator, lines);
    }

    private String upsertFloxCredentialsPreservingComments(String content, String floxToken) {
      final String lineSeparator = content.contains("\r\n") ? "\r\n" : "\n";
      final List<String> lines = new ArrayList<>(List.of(content.split("\\r?\\n", -1)));

      final Pattern floxHeaderPattern = Pattern.compile("^([\\t ]*)flox:\\s*(#.*)?$");
      final Pattern tokenPattern = Pattern.compile("^([\\t ]*token\\s*:\\s*)([^#]*)(\\s*(#.*)?)$");

      int floxIndex = -1;
      String floxIndent = "";
      for (int i = 0; i < lines.size(); i++) {
        final Matcher matcher = floxHeaderPattern.matcher(lines.get(i));
        if (matcher.matches()) {
          floxIndex = i;
          floxIndent = matcher.group(1);
          break;
        }
      }

      final String tokenValue = yamlSingleQuoted(floxToken);

      if (floxIndex < 0) {
        final String childIndent = "  ";
        if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) {
          lines.add("");
        }
        lines.add("flox:");
        lines.add(childIndent + "token: " + tokenValue);
        return String.join(lineSeparator, lines);
      }

      final int floxIndentWidth = indentationWidth(floxIndent);
      final String childIndent = floxIndent + "  ";

      int blockStart = floxIndex + 1;
      int blockEndExclusive = lines.size();
      for (int i = blockStart; i < lines.size(); i++) {
        final String line = lines.get(i);
        final String trimmed = line.trim();
        if (trimmed.isEmpty()) {
          continue;
        }
        if (trimmed.startsWith("#")) {
          continue;
        }
        if (indentationWidth(line) <= floxIndentWidth) {
          blockEndExclusive = i;
          break;
        }
      }

      int tokenIndex = -1;
      for (int i = blockStart; i < blockEndExclusive; i++) {
        final String line = lines.get(i);
        final Matcher tokenMatcher = tokenPattern.matcher(line);
        if (tokenMatcher.matches()) {
          final String suffix = tokenMatcher.group(3) == null ? "" : tokenMatcher.group(3);
          lines.set(i, tokenMatcher.group(1) + tokenValue + suffix);
          tokenIndex = i;
          break;
        }
      }

      if (tokenIndex < 0) {
        lines.add(blockEndExclusive, childIndent + "token: " + tokenValue);
      }

      return String.join(lineSeparator, lines);
    }

    private int indentationWidth(String line) {
      int width = 0;
      while (width < line.length()) {
        final char c = line.charAt(width);
        if (c != ' ' && c != '\t') {
          break;
        }
        width++;
      }
      return width;
    }

    private String yamlSingleQuoted(String value) {
      return "'" + value.replace("'", "''") + "'";
    }

    private String resolveGithubToken() {
      final String envToken =
          firstNonBlank(System.getenv("GITHUB_TOKEN"), System.getenv("GH_TOKEN"));
      if (!envToken.isBlank()) {
        return envToken;
      }
      return captureCommandOutput("gh", "auth", "token");
    }

    private String resolveFloxHubToken() {
      final String envToken =
          firstNonBlank(
              System.getenv("FLOXHUB_TOKEN"),
              System.getenv("FLOX_TOKEN"),
              System.getenv("FLOX_AUTH_TOKEN"));
      if (!envToken.isBlank()) {
        return envToken;
      }
      return captureCommandOutput("flox", "auth", "token");
    }

    private String firstNonBlank(String... candidates) {
      for (String value : candidates) {
        if (value != null && !value.isBlank()) {
          return value.trim();
        }
      }
      return "";
    }

    private String captureCommandOutput(String... command) {
      final ProcessBuilder pb = new ProcessBuilder(command);
      pb.redirectError(ProcessBuilder.Redirect.DISCARD);
      try {
        final Process process = pb.start();
        final String output = new String(process.getInputStream().readAllBytes()).trim();
        final int exit = process.waitFor();
        return exit == 0 ? output : "";
      } catch (IOException | InterruptedException ex) {
        if (ex instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        return "";
      }
    }
  }

  private static final class ProvisioningResourceInventory {

    private ProvisioningResourceInventory() {}

    /**
     * Compute per-slice checksums for independently reconcilable provisioning slices using fluent
     * pipeline grammar.
     *
     * <p>The core slice covers base provisioning (systemd, manifests, rke2 config); component
     * slices are discovered from the registry populated during materialization. Static slices
     * trigger instance renewal; hot-reload slices trigger reconciliation without renewal.
     *
     * @param paths bootstrap filesystem paths
     * @param registry component-populated slice registry with storage policies
     * @return partitioned slice checksums (static vs hot-reload)
     */
    private static ProvisioningMetadata.Slices sliceChecksums(
        BootstrapPaths paths, ProvisioningSliceRegistry registry) {
      final Map<String, String> allChecksums =
          SliceChecksumPipeline.begin(paths, registry)
              .during("core", core -> core.fromCoreRoots())
              .then()
              .during("registered components", components -> components.fromRegistry())
              .collectChecksums();

      // Partition by storage policy
      final Map<String, String> staticSlices = new java.util.LinkedHashMap<>();
      final Map<String, String> hotReloadSlices = new java.util.LinkedHashMap<>();

      for (Map.Entry<String, String> entry : allChecksums.entrySet()) {
        final String sliceName = entry.getKey();
        final String checksum = entry.getValue();
        final SliceStoragePolicy policy = registry.getStoragePolicy(sliceName);

        if (policy == SliceStoragePolicy.STATIC) {
          staticSlices.put(sliceName, checksum);
        } else if (policy == SliceStoragePolicy.HOT_RELOAD) {
          hotReloadSlices.put(sliceName, checksum);
        }
      }

      return ProvisioningMetadata.Slices.of(staticSlices, hotReloadSlices);
    }

    /**
     * Legacy single-checksum method for backward compatibility during migration. Computes aggregate
     * checksum over all slices.
     */
    @Deprecated
    private static String checksum(BootstrapPaths paths) {
      final List<Path> roots =
          List.of(
              paths.scriptsRoot(),
              paths.systemdRoot(),
              paths.manifestsRoot(),
              paths.runtimeRke2ConfigRoot(),
              paths.runtimeCloudConfigRoot(),
              paths.runtimeEnvConfigRoot(),
              paths.cloudSeedRoot(),
              paths.daemonsetRoot());

      try {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Path root : roots) {
          updateDigestForPath(digest, root);
        }
        return HexFormat.of().formatHex(digest.digest());
      } catch (NoSuchAlgorithmException ex) {
        throw new IllegalStateException("SHA-256 is not available", ex);
      }
    }

    private static String coreSliceChecksum(BootstrapPaths paths) {
      final List<Path> coreRoots =
          List.of(
              paths.scriptsRoot(),
              paths.systemdRoot(),
              paths.manifestsRoot(),
              paths.runtimeRke2ConfigRoot(),
              paths.runtimeCloudConfigRoot(),
              paths.runtimeEnvConfigRoot(),
              paths.cloudSeedRoot());

      return computeChecksum(coreRoots);
    }

    private static String computeChecksum(List<Path> roots) {
      try {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Path root : roots) {
          updateDigestForPath(digest, root);
        }
        return HexFormat.of().formatHex(digest.digest());
      } catch (NoSuchAlgorithmException ex) {
        throw new IllegalStateException("SHA-256 is not available", ex);
      }
    }

    private static void updateDigestForPath(MessageDigest digest, Path root) {
      digest.update((byte) '\n');
      digest.update(root.toString().getBytes(StandardCharsets.UTF_8));

      if (!Files.exists(root)) {
        digest.update("<missing>".getBytes(StandardCharsets.UTF_8));
        return;
      }

      if (Files.isRegularFile(root)) {
        digestFile(digest, root, root.getFileName());
        return;
      }

      try (Stream<Path> walk = Files.walk(root)) {
        walk.filter(Files::isRegularFile)
            .sorted()
            .forEach(file -> digestFile(digest, file, root.relativize(file)));
      } catch (IOException ex) {
        throw new IllegalStateException(
            "Failed to fingerprint provisioning resources at: " + root, ex);
      }
    }

    private static void digestFile(MessageDigest digest, Path file, Path relativePath) {
      try {
        digest.update((byte) '\n');
        digest.update(relativePath.toString().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(Files.readAllBytes(file));
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to read provisioning resource: " + file, ex);
      }
    }
  }

  private static final class SystemdProvisioningInventory {

    private SystemdProvisioningInventory() {}

    private static Map<String, Object> summarize(
        BootstrapPaths paths, List<String> hostMountNotes) {
      final List<String> scripts = listRegularFileNames(paths.scriptsRoot());
      final List<String> units = listRegularFileNames(paths.systemdRoot());
      final List<String> systemdLibexecContributions =
          listRegularFileNames(paths.systemdLibexecRoot());
      final Map<String, List<String>> scriptsByPhase = classifyByPhase(scripts);
      final Map<String, List<String>> unitsByPhase = classifyByPhase(units);
      final List<String> normalizedHostMountNotes =
          hostMountNotes == null ? List.of() : List.copyOf(hostMountNotes);

      final LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
      summary.put("scriptsMountPath", HOST_SCRIPTS_DIR_PATH);
      summary.put("unitsMountPath", HOST_SYSTEMD_DIR_PATH);
      summary.put("systemdLibexecMountPath", HOST_SYSTEMD_LIBEXEC_DIR_PATH);
      summary.put("scriptCount", scripts.size());
      summary.put("unitCount", units.size());
      summary.put("systemdLibexecContributionCount", systemdLibexecContributions.size());
      summary.put("scripts", scripts);
      summary.put("units", units);
      summary.put("systemdLibexecContributions", systemdLibexecContributions);
      summary.put("systemdLibexecNotes", normalizedHostMountNotes);
      summary.put("hostMountNotes", normalizedHostMountNotes);
      summary.put("scriptsByPhase", scriptsByPhase);
      summary.put("unitsByPhase", unitsByPhase);
      return Map.copyOf(summary);
    }

    private static List<String> listRegularFileNames(Path directory) {
      if (directory == null || !Files.exists(directory) || !Files.isDirectory(directory)) {
        return List.of();
      }

      try (Stream<Path> stream = Files.list(directory)) {
        return stream
            .filter(Files::isRegularFile)
            .map(path -> path.getFileName().toString())
            .sorted()
            .toList();
      } catch (IOException ex) {
        throw new IllegalStateException(
            "Failed to inspect systemd provisioning inventory at: " + directory, ex);
      }
    }

    private static Map<String, List<String>> classifyByPhase(List<String> names) {
      final LinkedHashMap<String, List<String>> phased = new LinkedHashMap<>();
      phased.put("network", new ArrayList<>());
      phased.put("install", new ArrayList<>());
      phased.put("runtime", new ArrayList<>());
      phased.put("manifests", new ArrayList<>());
      phased.put("storage", new ArrayList<>());
      phased.put("cluster-api", new ArrayList<>());
      phased.put("gitops", new ArrayList<>());
      phased.put("mesh", new ArrayList<>());
      phased.put("replication", new ArrayList<>());
      phased.put("cicd", new ArrayList<>());
      phased.put("tools", new ArrayList<>());
      phased.put("other", new ArrayList<>());

      for (String name : names) {
        phased.get(phaseForName(name)).add(name);
      }

      final LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
      for (Map.Entry<String, List<String>> entry : phased.entrySet()) {
        if (entry.getValue().isEmpty()) {
          continue;
        }
        result.put(entry.getKey(), List.copyOf(entry.getValue()));
      }
      return Map.copyOf(result);
    }

    private static String phaseForName(String name) {
      final String normalized = name == null ? "" : name.toLowerCase();

      if (normalized.contains("network")
          || normalized.contains("route")
          || normalized.contains("vip")) {
        return "network";
      }
      if (normalized.contains("install")
          || normalized.contains("activate")
          || normalized.contains("nix")
          || normalized.contains("flox")) {
        return "install";
      }
      if (normalized.contains("runtime")
          || normalized.contains("containerd")
          || normalized.contains("cri")
          || normalized.contains("server-")) {
        return "runtime";
      }
      if (normalized.contains("manifest")) {
        return "manifests";
      }
      if (normalized.contains("storage")
          || normalized.contains("openebs")
          || normalized.contains("zfs")) {
        return "storage";
      }
      if (normalized.contains("cluster-api") || normalized.contains("capn")) {
        return "cluster-api";
      }
      if (normalized.contains("gitops")) {
        return "gitops";
      }
      if (normalized.contains("mesh")
          || normalized.contains("headscale")
          || normalized.contains("headplane")) {
        return "mesh";
      }
      if (normalized.contains("replication") || normalized.contains("replicator")) {
        return "replication";
      }
      if (normalized.contains("cicd") || normalized.contains("tekton")) {
        return "cicd";
      }
      if (normalized.contains("tool")
          || normalized.contains("kubectl")
          || normalized.contains("helm")) {
        return "tools";
      }
      return "other";
    }
  }

  public record BootstrapResult(
      String seedNodeId,
      Object imageFingerprint,
      Object instanceStatus,
      Object instanceUrn,
      Object providerUrn,
      DeploymentMetadata deployment,
      ProvisioningMetadata provisioning,
      BuildMetadata build,
      RuntimeMetadata runtime,
      Resource readinessDependency) {}
}
