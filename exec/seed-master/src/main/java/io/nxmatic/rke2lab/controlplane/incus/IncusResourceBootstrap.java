package io.nxmatic.rke2lab.controlplane.incus;

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
import com.pulumi.incus.inputs.GetNetworkPlainArgs;
import com.pulumi.incus.inputs.GetProjectPlainArgs;
import com.pulumi.incus.inputs.InstanceDeviceArgs;
import com.pulumi.incus.inputs.ProfileDeviceArgs;
import com.pulumi.resources.CustomResourceOptions;
import com.pulumi.resources.Resource;
import io.nxmatic.rke2lab.config.port.BootstrapConfig;
import io.nxmatic.rke2lab.config.port.BootstrapConfig.WorktreeHost;
import io.nxmatic.rke2lab.controlplane.SeedLog;
import io.nxmatic.rke2lab.controlplane.incus.image.PulumiIncusImageProvider;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.manifests.port.FloxRuntimeAssetService;
import io.nxmatic.rke2lab.manifests.port.ManifestDocumentService;
import io.nxmatic.rke2lab.manifests.port.ManifestExplodeRequest;
import io.nxmatic.rke2lab.manifests.port.ManifestExplodeResult;
import io.nxmatic.rke2lab.manifests.port.ManifestExplodeService;
import io.nxmatic.rke2lab.manifests.port.ManifestSynthesisRequest;
import io.nxmatic.rke2lab.manifests.port.ManifestSynthesisResult;
import io.nxmatic.rke2lab.manifests.port.ManifestSynthesisService;
import io.nxmatic.rke2lab.manifests.port.node.NodeEnvContext;
import io.nxmatic.rke2lab.manifests.port.node.NodeEnvOverlayService;
import io.nxmatic.rke2lab.manifests.port.profiles.ComponentVersions;
import io.nxmatic.rke2lab.manifests.port.profiles.FloxDebugPolicy;
import io.nxmatic.rke2lab.manifests.port.profiles.IncusIdentityMaterial;
import io.nxmatic.rke2lab.netplan.port.ClusterNetworkBlueprint;
import io.nxmatic.rke2lab.osgi.runtime.framework.BootedFramework;
import io.nxmatic.rke2lab.pipeline.FluentTopicRunner;
import io.nxmatic.rke2lab.pipeline.OnFailure;
import io.nxmatic.rke2lab.pipeline.PipelineContext;
import io.nxmatic.rke2lab.pipeline.Topic;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.App;
import org.cdk8s.AppProps;
import org.cdk8s.Chart;
import org.cdk8s.JsonPatch;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.jspecify.annotations.Nullable;

/** Provider-native Stage A bootstrap resources for the Incus management seed node. */
public final class IncusResourceBootstrap {

  private static final List<String> CLUSTER_NODE_NAMES =
      List.of("master", "peer1", "peer2", "peer3", "worker1", "worker2");

  private static final class DaemonsetLogPolicy {

    private static final String HOST_SOURCE_DIRECTORY_NAME = "k8s-daemonset.d";

    private static final String GUEST_ROOT_PATH = "/srv/host/k8s-daemonset.d";

    private DaemonsetLogPolicy() {}
  }

  private final ManifestFileOperations manifestFileOps = ManifestFileOperations.INSTANCE;

  private final BootstrapContext bootstrapContext;

  /** The embedded OSGi framework whose registry holds the manifests-world services. */
  private final BootedFramework bootedFramework;

  public IncusResourceBootstrap(BootstrapConfig config, BootedFramework bootedFramework) {
    if (bootedFramework == null) {
      throw new IllegalArgumentException("bootedFramework must not be null");
    }
    this.bootedFramework = bootedFramework;
    this.bootstrapContext =
        new BootstrapContext(
            config,
            new PulumiIncusImageProvider(config),
            HostMountSourceVerifier.INSTANCE,
            new NodeConfigRegenerator(
                new CloudConfigSecretRenderer(singleSpiProvider(ManifestDocumentService.class))),
            IncusImportLookup.INSTANCE,
            LaunchSecretsUpdater.INSTANCE);
  }

  /**
   * Materialize seed resources directly via the Incus provider — three sub-pipelines by domain
   * phase: PREPARE (paths + host state), PROVISION (provider resources), LAUNCH (the instance).
   * Each is its own {@code during/then} chain with a strictly-local accumulator; the ambient config
   * + services + policy are shared through the {@link PipelineContext}. The parent's accumulator
   * carries only the three composite outputs. See
   * docs/architecture/patterns/fluent-pipeline-grammar.adoc.
   */
  public BootstrapResult apply(ControlplanePolicy policy) {
    final PipelineContext context = new PipelineContext();
    context.register(BootstrapContext.class, bootstrapContext);
    context.register(ControlplanePolicy.class, policy);

    final OnFailure onFailure =
        (topic, cause) -> SeedLog.error("incus", topic + ": " + cause.getMessage());
    final FluentTopicRunner runner = new FluentTopicRunner("incus");

    final PreparedHost prepared = new PreparePipeline(context, runner, onFailure).run();
    final ProvisionedResources provisioned =
        new ProvisionPipeline(context, runner, onFailure, prepared).run();
    final LaunchedInstance launched =
        new LaunchPipeline(context, runner, onFailure, prepared, provisioned).run();

    return toResult(prepared, provisioned, launched);
  }

  /** Fan-in of the three phase outputs into the public bootstrap result. */
  private BootstrapResult toResult(
      PreparedHost prepared, ProvisionedResources provisioned, LaunchedInstance launched) {
    return new BootstrapResult(
        "incus://"
            + bootstrapContext.config().incusProject()
            + "/"
            + bootstrapContext.config().nodeName(),
        provisioned.imageFingerprint(),
        launched.instance().status(),
        launched.instance().urn(),
        provisioned.providerContext().provider().urn(),
        prepared.deployment(),
        prepared.provisioning(),
        recombineBuildMetadata(provisioned.imageChecksum(), prepared.manifests()),
        prepared.runtime(),
        launched.instance());
  }

  /**
   * Recombines {@link BuildMetadata} from its two producers — the pure heart of the fan-in. The
   * {@code manifests} half is PREPARE's output, the {@code imageChecksum} half is PROVISION's;
   * joining them here at the fan-in is what let the former mid-run {@code
   * registry.update(BuildMetadata)} disappear. Takes the two raw halves (not the whole phase
   * records) so it depends on exactly what it joins and is unit-testable without a Pulumi context.
   */
  static BuildMetadata recombineBuildMetadata(
      String imageChecksum, BuildMetadata.Manifests manifests) {
    return new BuildMetadata(Optional.of(new BuildMetadata.Image(imageChecksum)), manifests);
  }

  /** Composite output of the PREPARE sub-pipeline (paths + host-state metadata). */
  private record PreparedHost(
      BootstrapPaths localPaths,
      BootstrapPaths nixosPaths,
      DeploymentMetadata deployment,
      ProvisioningMetadata provisioning,
      RuntimeMetadata runtime,
      BuildMetadata.Manifests manifests) {}

  /** Composite output of the PROVISION sub-pipeline (provider resources + image identity). */
  private record ProvisionedResources(
      IncusProviderContext providerContext,
      Output<String> projectName,
      Output<String> profileName,
      Output<String> imageFingerprint,
      String imageChecksum) {}

  /** Output of the LAUNCH phase. */
  private record LaunchedInstance(Instance instance) {}

  /**
   * Immutable context shared across all stages. Contains configuration and service instances that
   * don't change during bootstrap.
   */
  private record BootstrapContext(
      BootstrapConfig config,
      PulumiIncusImageProvider imageProvider,
      HostMountSourceVerifier hostMountSourceVerifier,
      NodeConfigRegenerator nodeConfigRegenerator,
      IncusImportLookup incusImportLookup,
      LaunchSecretsUpdater launchSecretsUpdater) {}

  /**
   * PREPARE sub-pipeline — its own {@code during/then} chain over two topics (path resolution, host
   * state) with a strictly-local accumulator. The four host-state metadata that used to live in a
   * shared registry are set-once fields here; ambient config + policy come from the shared {@link
   * PipelineContext}. Produces {@link PreparedHost}.
   */
  private final class PreparePipeline {
    private final PipelineContext context;
    private final FluentTopicRunner runner;
    private final OnFailure onFailure;
    private final BootstrapContext bootstrap;

    // Local accumulator (set-once by the topics below).
    @MonotonicNonNull BootstrapPaths localPaths;
    @MonotonicNonNull BootstrapPaths nixosPaths;
    @MonotonicNonNull DeploymentMetadata deployment;
    @MonotonicNonNull ProvisioningMetadata provisioning;
    @MonotonicNonNull RuntimeMetadata runtime;
    BuildMetadata.@MonotonicNonNull Manifests manifests;

    PreparePipeline(PipelineContext context, FluentTopicRunner runner, OnFailure onFailure) {
      this.context = context;
      this.runner = runner;
      this.onFailure = onFailure;
      this.bootstrap = context.require(BootstrapContext.class);
    }

    BootstrapContext bootstrap() {
      return bootstrap;
    }

    ControlplanePolicy policy() {
      return context.require(ControlplanePolicy.class);
    }

    PreparedHost run() {
      runner.runDuring(
          "path resolution",
          new PathStage(
              this::bootstrap,
              new PathStage.Sink() {
                @Override
                public void localPaths(BootstrapPaths resolved) {
                  localPaths = resolved;
                }

                @Override
                public void nixosPaths(BootstrapPaths resolved) {
                  nixosPaths = resolved;
                }
              }),
          PathStage::resolve,
          onFailure);
      runner.runDuring(
          "host state",
          new HostStage(
              this::bootstrap,
              this::localPaths,
              this::policy,
              new HostStage.Sink() {
                @Override
                public void deployment(DeploymentMetadata captured) {
                  deployment = captured;
                }

                @Override
                public void provisioning(ProvisioningMetadata captured) {
                  provisioning = captured;
                }

                @Override
                public void manifests(BuildMetadata.Manifests captured) {
                  manifests = captured;
                }

                @Override
                public void runtime(RuntimeMetadata captured) {
                  runtime = captured;
                }
              }),
          host -> host.materializeAssets().ensureSecrets().logSummary(),
          onFailure);
      return new PreparedHost(
          localPaths(), nixosPaths(), deployment(), provisioning(), runtime(), manifests());
    }

    BootstrapPaths localPaths() {
      return Objects.requireNonNull(localPaths, "localPaths (path topic not yet run)");
    }

    BootstrapPaths nixosPaths() {
      return Objects.requireNonNull(nixosPaths, "nixosPaths (path topic not yet run)");
    }

    DeploymentMetadata deployment() {
      return Objects.requireNonNull(deployment, "deployment (host topic not yet run)");
    }

    ProvisioningMetadata provisioning() {
      return Objects.requireNonNull(provisioning, "provisioning (host topic not yet run)");
    }

    RuntimeMetadata runtime() {
      return Objects.requireNonNull(runtime, "runtime (host topic not yet run)");
    }

    BuildMetadata.Manifests manifests() {
      return Objects.requireNonNull(manifests, "manifests (host topic not yet run)");
    }
  }

  /**
   * PROVISION sub-pipeline — the provider-resources topic (project, networks, profile, image,
   * staged config map) with a strictly-local accumulator. The image identity that used to force a
   * {@code registry.update(BuildMetadata)} is just a local output here. Produces {@link
   * ProvisionedResources}.
   */
  private final class ProvisionPipeline {
    private final FluentTopicRunner runner;
    private final OnFailure onFailure;
    private final BootstrapContext bootstrap;
    private final PreparedHost prepared;

    @MonotonicNonNull IncusProviderContext providerContext;
    @MonotonicNonNull Output<String> ensuredProjectName;
    @MonotonicNonNull Output<String> ensuredProfileName;
    @MonotonicNonNull Output<String> ensuredImageFingerprint;
    @MonotonicNonNull String imageChecksum;

    ProvisionPipeline(
        PipelineContext context,
        FluentTopicRunner runner,
        OnFailure onFailure,
        PreparedHost prepared) {
      this.runner = runner;
      this.onFailure = onFailure;
      this.bootstrap = context.require(BootstrapContext.class);
      this.prepared = prepared;
    }

    BootstrapContext bootstrap() {
      return bootstrap;
    }

    PreparedHost prepared() {
      return prepared;
    }

    ProvisionedResources run() {
      runner.runDuring(
          "provider resources",
          new ProviderStage(
              this::bootstrap,
              this::prepared,
              new ProviderStage.Sink() {
                @Override
                public void providerContext(IncusProviderContext ensured) {
                  providerContext = ensured;
                }

                @Override
                public void projectName(Output<String> ensured) {
                  ensuredProjectName = ensured;
                }

                @Override
                public void profileName(Output<String> ensured) {
                  ensuredProfileName = ensured;
                }

                @Override
                public void imageFingerprint(Output<String> ensured) {
                  ensuredImageFingerprint = ensured;
                }

                @Override
                public void imageChecksum(String checksum) {
                  imageChecksum = checksum;
                }
              }),
          provider ->
              provider
                  .ensureProject()
                  .ensureNetworks()
                  .ensureProfile()
                  .ensureImage()
                  .createImageStateConfigMap(),
          onFailure);
      return new ProvisionedResources(
          providerContext(),
          ensuredProjectName(),
          ensuredProfileName(),
          ensuredImageFingerprint(),
          imageChecksum());
    }

    IncusProviderContext providerContext() {
      return Objects.requireNonNull(
          providerContext, "providerContext (provider topic not yet run)");
    }

    Output<String> ensuredProjectName() {
      return Objects.requireNonNull(
          ensuredProjectName, "ensuredProjectName (provider topic not yet run)");
    }

    Output<String> ensuredProfileName() {
      return Objects.requireNonNull(
          ensuredProfileName, "ensuredProfileName (provider topic not yet run)");
    }

    Output<String> ensuredImageFingerprint() {
      return Objects.requireNonNull(
          ensuredImageFingerprint, "ensuredImageFingerprint (image topic not yet run)");
    }

    String imageChecksum() {
      return Objects.requireNonNull(imageChecksum, "imageChecksum (image topic not yet run)");
    }
  }

  /**
   * LAUNCH phase — a single leaf topic that fans in {@link PreparedHost} + {@link
   * ProvisionedResources} to create the instance. Produces {@link LaunchedInstance}.
   */
  private final class LaunchPipeline {
    private final FluentTopicRunner runner;
    private final OnFailure onFailure;
    private final BootstrapContext bootstrap;
    private final PreparedHost prepared;
    private final ProvisionedResources provisioned;

    @MonotonicNonNull Instance instance;

    LaunchPipeline(
        PipelineContext context,
        FluentTopicRunner runner,
        OnFailure onFailure,
        PreparedHost prepared,
        ProvisionedResources provisioned) {
      this.runner = runner;
      this.onFailure = onFailure;
      this.bootstrap = context.require(BootstrapContext.class);
      this.prepared = prepared;
      this.provisioned = provisioned;
    }

    BootstrapContext bootstrap() {
      return bootstrap;
    }

    PreparedHost prepared() {
      return prepared;
    }

    ProvisionedResources provisioned() {
      return provisioned;
    }

    LaunchedInstance run() {
      runner.runDuring(
          "instance",
          new InstanceStage(
              this::bootstrap, this::prepared, this::provisioned, launched -> instance = launched),
          InstanceStage::create,
          onFailure);
      return new LaunchedInstance(
          Objects.requireNonNull(instance, "instance (instance topic not yet run)"));
    }
  }

  /**
   * Path-resolution topic — resolves the dual local/nixos path views. Reads its ambient config,
   * pushes its outputs through its {@link Sink}; it holds no reference to the accumulator, so it is
   * deterministic and testable in isolation (give it a config + a throwaway sink, assert what it
   * pushes).
   */
  private final class PathStage implements Topic.Execution {
    private final Supplier<BootstrapContext> context;
    private final Sink sink;

    PathStage(Supplier<BootstrapContext> context, Sink sink) {
      this.context = context;
      this.sink = sink;
    }

    /** The write-face of the path topic — one verb per resolved view. */
    interface Sink extends Topic.Sink {
      void localPaths(BootstrapPaths localPaths);

      void nixosPaths(BootstrapPaths nixosPaths);
    }

    @Override
    public String role() {
      return "path";
    }

    PathStage resolve() {
      final BootstrapContext context = this.context.get();
      final Path localWorktreeRoot = context.config().worktreeDirOn(WorktreeHost.DARWIN);
      final BootstrapPaths localPaths =
          BootstrapPaths.fromLocalWorktree(
              localWorktreeRoot, context.config().clusterName(), context.config().nodeName());
      final BootstrapPaths nixosPaths = localPaths.asHostView(context.config(), WorktreeHost.NIXOS);
      sink.localPaths(localPaths);
      sink.nixosPaths(nixosPaths);
      return this;
    }
  }

  /**
   * Host-state topic — synthesizes/stages/syncs the host assets and captures the four provisioning
   * metadata. Reads its flux input (the resolved local paths) and its ambient (bootstrap context +
   * policy) by construction; pushes its four outputs through its {@link Sink}. It keeps the
   * metadata it produces in local fields only to log its own summary — it never reads back from the
   * accumulator.
   */
  private final class HostStage implements Topic.Execution {
    private final Supplier<BootstrapContext> context;
    private final Supplier<BootstrapPaths> localPaths;
    private final Supplier<ControlplanePolicy> policy;
    private final Sink sink;

    private @MonotonicNonNull DeploymentMetadata deployment;
    private @MonotonicNonNull ProvisioningMetadata provisioning;

    HostStage(
        Supplier<BootstrapContext> context,
        Supplier<BootstrapPaths> localPaths,
        Supplier<ControlplanePolicy> policy,
        Sink sink) {
      this.context = context;
      this.localPaths = localPaths;
      this.policy = policy;
      this.sink = sink;
    }

    // Read-faces onto the owner (PREPARE's accumulator): each read resolves at the source of truth,
    // never a copied reference. The many private methods below read through these, not the fields.
    private BootstrapContext context() {
      return context.get();
    }

    private BootstrapPaths localPaths() {
      return localPaths.get();
    }

    private ControlplanePolicy policy() {
      return policy.get();
    }

    /** The write-face of the host topic — one verb per captured metadata. */
    interface Sink extends Topic.Sink {
      void deployment(DeploymentMetadata deployment);

      void provisioning(ProvisioningMetadata provisioning);

      void manifests(BuildMetadata.Manifests manifests);

      void runtime(RuntimeMetadata runtime);
    }

    @Override
    public String role() {
      return "host state";
    }

    HostStage materializeAssets() {
      final boolean dryRun = Deployment.getInstance().isDryRun();
      logInfo("mode=" + (dryRun ? "preview" : "apply"));

      // Preview reuses a fixed `host.preview` slot so it never consumes a retention slot or syncs
      // to host/. Apply allocates a numbered slot in [0, retentionCount) and rsyncs it onto host/.
      final HostAssetRootLifecycle lifecycle =
          dryRun
              ? HostAssetRootLifecycle.previewLifecycle()
              : new HostAssetRootLifecycle(context().config().hostAssetRotationRetentionCount());

      final StagingContext staging = materializeToStaging(lifecycle);
      final TargetContext targets = registerProvisioningTargets(staging);
      // Capture metadata BEFORE syncing: the sync step may move the scratch dir into a numbered
      // slot, after which the target registry's recorded paths would no longer resolve. Metadata
      // capture only reads from the registry and in-memory summaries, so it's safe to do first.
      captureDeploymentMetadata(staging, targets);
      if (!dryRun) {
        syncStagingToFinal(lifecycle, staging.stagingRoot(), targets);
        // Note: We do NOT delete the kubeconfig here, even if host assets changed. The kubeconfig
        // is written by RKE2 inside the instance and mounted to the host. It remains valid unless
        // the *instance* is replaced (not just host assets). Deleting it prematurely breaks kubectl
        // access when only manifests/flox environments are updated. The instance replacement logic
        // (replaceOnChanges: ["config", "config.*"]) will trigger a fresh kubeconfig if needed.
      }

      return this;
    }

    HostStage ensureSecrets() {
      ensureLaunchSecretsToken(localPaths().secretsFile());
      return this;
    }

    HostStage logSummary() {
      logInfo("deployment=" + Objects.requireNonNull(deployment, "deployment not yet captured"));
      logInfo(
          "provisioning.targets="
              + Objects.requireNonNull(provisioning, "provisioning not yet captured").targets());
      return this;
    }

    private StagingContext materializeToStaging(HostAssetRootLifecycle lifecycle) {
      final BootstrapPaths localPaths = localPaths();
      final Path stagingRoot = lifecycle.prepareStagingRoot(localPaths.assetsRoot());
      final Path stagingManifestsRoot =
          stagingRoot.resolve(localPaths.assetsRoot().relativize(localPaths.manifestsRoot()));

      final BootstrapPaths stagingPaths = createStagingPaths(stagingRoot);
      final NodeEnvContext layerContext = new DefaultBootstrapNodeEnvContext();
      final Map<String, Object> manifestSynthSummary =
          synthesizeAndExplodeManifests(
              stagingManifestsRoot, stagingPaths.systemdRoot(), policy(), layerContext);

      return new StagingContext(
          stagingRoot, stagingPaths, stagingManifestsRoot, layerContext, manifestSynthSummary);
    }

    private TargetContext registerProvisioningTargets(StagingContext staging) {
      final ProvisioningTargetRegistry targetRegistry = new ProvisioningTargetRegistry();

      registerCloudInitTarget(targetRegistry, staging);
      final SystemdTarget systemdTarget =
          registerSystemdTarget(targetRegistry, staging.stagingPaths());
      registerK8sTarget(targetRegistry, staging);
      registerRke2ConfigTarget(targetRegistry, staging.stagingPaths());
      final Map<String, Object> runtimeSummaries =
          materializeAndRegisterRke2labEnvTarget(targetRegistry, staging);

      return new TargetContext(targetRegistry, runtimeSummaries, systemdTarget);
    }

    private void registerCloudInitTarget(
        ProvisioningTargetRegistry targetRegistry, StagingContext staging) {
      final BootstrapPaths stagingPaths = staging.stagingPaths();
      final CloudInitTarget cloudInitTarget =
          new CloudInitTarget(
              context().nodeConfigRegenerator(),
              stagingPaths.runtimeCloudConfigRoot(),
              stagingPaths.cloudSeedRoot());
      try {
        cloudInitTarget.materialize(stagingPaths);
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to materialize cloud-init target", ex);
      }
      targetRegistry.register(cloudInitTarget);
    }

    private SystemdTarget registerSystemdTarget(
        ProvisioningTargetRegistry targetRegistry, BootstrapPaths stagingPaths) {
      final SystemdTarget systemdTarget =
          new SystemdTarget(singleSpiProvider(FloxRuntimeAssetService.class));
      try {
        systemdTarget.materialize(stagingPaths);
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to materialize systemd target", ex);
      }
      targetRegistry.register(systemdTarget);
      return systemdTarget;
    }

    private void registerK8sTarget(
        ProvisioningTargetRegistry targetRegistry, StagingContext staging) {
      // ManifestSynthesisService + explode already filled stagingManifestsRoot upstream; the
      // target carries no materialize body and only declares ownership for checksum/inventory.
      targetRegistry.register(new K8sTarget(staging.stagingManifestsRoot()));
    }

    private void registerRke2ConfigTarget(
        ProvisioningTargetRegistry targetRegistry, BootstrapPaths stagingPaths) {
      // cdk8s synth+explode fills the rke2-config dir upstream; passive target — no materialize
      // body of its own, just declares ownership.
      targetRegistry.register(new Rke2ConfigTarget(stagingPaths.runtimeRke2ConfigRoot()));
    }

    private Map<String, Object> materializeAndRegisterRke2labEnvTarget(
        ProvisioningTargetRegistry targetRegistry, StagingContext staging) {
      final BootstrapPaths stagingPaths = staging.stagingPaths();
      final NodeEnvContext layerContext = staging.layerContext();

      final List<String> hostMountNotes =
          context().hostMountSourceVerifier().ensureSources(stagingPaths);
      final Map<String, Object> systemdProvisioningSummary =
          SystemdProvisioningInventory.summarize(stagingPaths, hostMountNotes);

      final Rke2labEnvTarget rke2labEnvTarget =
          new Rke2labEnvTarget(
              singleSpiProvider(NodeEnvOverlayService.class),
              layerContext,
              policy(),
              stagingPaths.runtimeEnvConfigRoot());
      try {
        rke2labEnvTarget.materialize(stagingPaths);
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to materialize rke2lab-env target", ex);
      }
      targetRegistry.register(rke2labEnvTarget);

      return Map.of(
          "systemd", systemdProvisioningSummary, "layerEnv", rke2labEnvTarget.layerEnvSummary());
    }

    private boolean syncStagingToFinal(
        HostAssetRootLifecycle lifecycle, Path stagingRoot, TargetContext targets) {
      return lifecycle.syncStagingToFinal(
          stagingRoot,
          localPaths().assetsRoot(),
          context().config(),
          policy(),
          targets.systemdTarget());
    }

    /**
     * Folds the host-state metadata into PREPARE's local accumulator. Note {@code BuildMetadata} is
     * NOT assembled here: only its {@code manifests} half is a Host output; the image half is a
     * PROVISION output. They recombine at the parent's {@code toResult} fan-in — so no mid-run
     * {@code update} of a shared record.
     */
    private void captureDeploymentMetadata(StagingContext staging, TargetContext targets) {
      final BootstrapPaths localPaths = localPaths();
      final ProvisioningMetadata.Targets provisioningTargets =
          ProvisioningResourceInventory.targetChecksums(localPaths, targets.targetRegistry());

      final String hostSourceDirRelative = localPaths.relativizeAgainst(localPaths.worktreeRoot());

      @SuppressWarnings("unchecked")
      final Map<String, Object> layerEnvSummary =
          (Map<String, Object>) targets.runtimeSummaries().getOrDefault("layerEnv", Map.of());
      @SuppressWarnings("unchecked")
      final Map<String, Object> systemdSummary =
          (Map<String, Object>) targets.runtimeSummaries().getOrDefault("systemd", Map.of());

      this.deployment = DeploymentMetadata.capture();
      this.provisioning =
          new ProvisioningMetadata(
              provisioningTargets, new ProvisioningMetadata.Paths(hostSourceDirRelative));
      sink.deployment(deployment);
      sink.provisioning(provisioning);
      sink.manifests(BuildMetadata.Manifests.of(staging.manifestSynthSummary()));
      sink.runtime(
          new RuntimeMetadata(
              RuntimeMetadata.Environment.of(layerEnvSummary),
              RuntimeMetadata.Systemd.of(systemdSummary)));
    }

    private BootstrapPaths createStagingPaths(Path stagingRoot) {
      return localPaths().asStagingView(stagingRoot);
    }

    private record StagingContext(
        Path stagingRoot,
        BootstrapPaths stagingPaths,
        Path stagingManifestsRoot,
        NodeEnvContext layerContext,
        Map<String, Object> manifestSynthSummary) {}

    private record TargetContext(
        ProvisioningTargetRegistry targetRegistry,
        Map<String, Object> runtimeSummaries,
        SystemdTarget systemdTarget) {}
  }

  private Map<String, Object> synthesizeAndExplodeManifests(
      Path manifestsRoot,
      Path systemdUnitsTarget,
      ControlplanePolicy policy,
      NodeEnvContext layerContext) {
    final long startedAt = System.nanoTime();
    Path synthScratch = null;
    try {
      synthScratch = Files.createTempDirectory("rke2lab-synth-");
      final Path consolidated = synthScratch.resolve("manifests.yaml");
      final FloxDebugPolicy floxDebugPolicy = resolveFloxDebugPolicy(policy);

      manifestFileOps.wipeExplodedLayers(manifestsRoot);

      final ManifestSynthesisResult synthResult =
          synthesizeManifests(synthScratch, consolidated, layerContext, floxDebugPolicy);
      final ManifestExplodeResult explodeResult = explodeManifests(consolidated, manifestsRoot);

      // Copy synthesized systemd units to target location before scratch is deleted
      copySystemdUnitsFromSynthesis(synthResult.systemdUnitsDir(), systemdUnitsTarget);

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

  /**
   * Copies synthesized systemd units from the synthesis output to the target location.
   *
   * @param systemdSource directory containing synthesized .service and .target files
   * @param target destination directory for systemd units
   */
  private void copySystemdUnitsFromSynthesis(Path systemdSource, Path target) throws IOException {
    if (!Files.exists(systemdSource) || !Files.isDirectory(systemdSource)) {
      throw new IllegalStateException(
          "Systemd units directory not found in synthesis output: " + systemdSource);
    }

    Files.createDirectories(target);

    try (var stream = Files.list(systemdSource)) {
      stream
          .filter(Files::isRegularFile)
          .filter(
              p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".service") || name.endsWith(".target");
              })
          .forEach(
              unitFile -> {
                try {
                  Path targetFile = target.resolve(unitFile.getFileName());
                  Files.copy(unitFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                  throw new UncheckedIOException("Failed to copy systemd unit: " + unitFile, e);
                }
              });
    }
  }

  private FloxDebugPolicy resolveFloxDebugPolicy(ControlplanePolicy policy) {
    final ControlplanePolicy.DebugPolicy debug = policy.debug();
    return new FloxDebugPolicy(debug.mesh(), debug.networking(), debug.nriPluginsFlox());
  }

  private ManifestSynthesisResult synthesizeManifests(
      Path synthScratch,
      Path consolidated,
      NodeEnvContext layerContext,
      FloxDebugPolicy floxDebugPolicy)
      throws IOException {
    final ComponentVersions componentVersions = ComponentVersions.defaults();
    final IncusIdentityMaterial incusIdentity =
        new IncusIdentityMaterialAssembler(bootstrapContext.config()).assemble();

    final ManifestSynthesisRequest synthRequest =
        ManifestSynthesisRequest.builder(synthScratch, consolidated)
            .floxDebugPolicy(floxDebugPolicy)
            .bootstrapIdentity(layerContext.bootstrapIdentity())
            .networkTopology(layerContext.networkTopology())
            .componentVersions(componentVersions)
            .incusIdentity(incusIdentity)
            .build();

    final ManifestSynthesisService synthesizer = singleSpiProvider(ManifestSynthesisService.class);
    return synthesizer.synthesize(synthRequest);
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
            + " (floxDebugPolicy="
            + floxDebugPolicy
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
        "floxDebugMeshEnabled", floxDebugPolicy.meshEnabled(),
        "floxDebugNetworkingEnabled", floxDebugPolicy.networkingEnabled(),
        "floxDebugFloxNriPluginEnabled", floxDebugPolicy.floxNriPluginEnabled());
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
        final List<Path> entries = stream.sorted(Comparator.reverseOrder()).toList();
        for (Path entry : entries) {
          Files.deleteIfExists(entry);
        }
      }
    }

    void deleteSynthScratchSilently(Path scratch) {
      try (Stream<Path> stream = Files.walk(scratch)) {
        stream
            .sorted(Comparator.reverseOrder())
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

  /** Read the single provider of {@code serviceType} from the booted framework's registry. */
  private <T> T singleSpiProvider(Class<T> serviceType) {
    final T service = bootedFramework.awaitService(serviceType, 5000);
    if (service == null) {
      throw new IllegalStateException(
          "No " + serviceType.getSimpleName() + " published in the OSGi registry within 5s.");
    }
    return service;
  }

  /**
   * Provider-resources topic — ensures the project, networks, profile and image, then stages the
   * image-state ConfigMap. Reads its flux input (the prepared host, for the manifests root) and its
   * ambient (bootstrap context) by construction; pushes its five outputs through its {@link Sink}.
   * The provider context, ensured project and image fingerprint are kept as local working fields
   * because later verbs read them — never read back from the accumulator.
   */
  private final class ProviderStage implements Topic.Execution {
    private final Supplier<BootstrapContext> context;
    private final Supplier<PreparedHost> prepared;
    private final Sink sink;

    private @MonotonicNonNull IncusProviderContext providerContext;
    private @MonotonicNonNull Project ensuredProject;
    private @MonotonicNonNull Output<String> ensuredImageFingerprint;

    ProviderStage(Supplier<BootstrapContext> context, Supplier<PreparedHost> prepared, Sink sink) {
      this.context = context;
      this.prepared = prepared;
      this.sink = sink;
    }

    // Read-faces onto the owner (PROVISION's inputs): resolve at the source on each read.
    private BootstrapContext context() {
      return context.get();
    }

    private PreparedHost prepared() {
      return prepared.get();
    }

    /** The write-face of the provider topic — one verb per provider output. */
    interface Sink extends Topic.Sink {
      void providerContext(IncusProviderContext providerContext);

      void projectName(Output<String> projectName);

      void profileName(Output<String> profileName);

      void imageFingerprint(Output<String> imageFingerprint);

      void imageChecksum(String imageChecksum);
    }

    @Override
    public String role() {
      return "provider resources";
    }

    private IncusProviderContext providerContext() {
      return Objects.requireNonNull(providerContext, "providerContext (ensureProject not yet run)");
    }

    private Project ensuredProject() {
      return Objects.requireNonNull(ensuredProject, "ensuredProject (ensureProject not yet run)");
    }

    ProviderStage ensureProject() {
      providerContext =
          IncusProviderContext.forBootstrap("seed-incus-provider", context().config());
      ensuredProject = IncusResourceBootstrap.this.ensureProject(providerContext);
      sink.providerContext(providerContext);
      sink.projectName(ensuredProject.name());
      return this;
    }

    ProviderStage ensureNetworks() {
      final BootstrapContext context = context();
      IncusResourceBootstrap.this.ensureNetwork(
          providerContext(), context.config().lanBridgeParent(), ensuredProject());
      IncusResourceBootstrap.this.ensureNetwork(
          providerContext(), context.config().vmnetNetworkName(), ensuredProject());
      return this;
    }

    ProviderStage ensureProfile() {
      sink.profileName(
          IncusResourceBootstrap.this.ensureProfile(providerContext(), ensuredProject()));
      return this;
    }

    ProviderStage ensureImage() {
      final BootstrapContext context = context();
      ensuredImageFingerprint =
          context
              .imageProvider()
              .ensureSeedImageFingerprint(
                  providerContext().invokeOptions(),
                  providerContext().provider(),
                  Optional.of(ensuredProject()));
      sink.imageFingerprint(ensuredImageFingerprint);

      // The image checksum is just this topic's output, folded into PROVISION's local accumulator.
      // The manifests half of BuildMetadata is PREPARE's output; the two recombine at the parent's
      // toResult fan-in — no mid-run update of a shared record.
      sink.imageChecksum(context.imageProvider().buildChecksum());
      return this;
    }

    ProviderStage createImageStateConfigMap() {
      // Image state ConfigMap cannot be synthesized during Stage A "host state" preparation
      // because the image fingerprint isn't available yet (chicken-and-egg: manifests need to
      // be materialized into /srv/host BEFORE provider resources are created, but the fingerprint
      // comes FROM those provider resources).
      //
      // Solution: Use CDK8s to synthesize the manifest DURING Pulumi apply (after Outputs resolve),
      // write the YAML to /srv/host/manifests/cluster-api/staged/, then a systemd oneshot unit
      // applies it after RKE2 is up.
      //
      // This implements the "staged post-cluster resource" pattern documented in
      // docs/staged-post-cluster-resources.adoc.
      //
      // IMPORTANT: Shell scripts do NOT author YAML. All manifest structure comes from CDK8s
      // (ImageStateConfigMapManifestUnit), even for staged resources. The staging only affects
      // WHEN synthesis happens (Output.apply time vs. host-state prep), not WHO authors it.

      final BootstrapContext context = context();
      final Output<String> manifestYaml =
          Output.all(
                  Objects.requireNonNull(
                      ensuredImageFingerprint, "ensuredImageFingerprint (image verb not yet run)"),
                  Output.of(context.config().imageAlias()),
                  Output.of(context.imageProvider().buildChecksum()),
                  Output.of(context.config().incusProject()),
                  Output.of(context.config().incusRemoteAddress().toString()))
              .applyValue(
                  outputs -> {
                    final String fingerprint = outputs.get(0);
                    final String alias = outputs.get(1);
                    final String checksum = outputs.get(2);
                    final String project = outputs.get(3);
                    final String remote = outputs.get(4);

                    return ImageStateConfig.builder(IncusResourceBootstrap.this)
                        .clusterName(context.config().clusterName())
                        .imageAlias(alias)
                        .imageFingerprint(fingerprint)
                        .imageBuildChecksum(checksum)
                        .incusProject(project)
                        .incusRemoteAddress(remote)
                        .synthesize();
                  });

      // Write manifest via Output side-effect (runs during Pulumi apply)
      manifestYaml.applyValue(
          yaml -> {
            writeImageStateManifest(yaml);
            return yaml;
          });

      return this;
    }

    private void writeImageStateManifest(String yaml) {
      try {
        final Path targetDir =
            prepared().localPaths().manifestsRoot().resolve("cluster-api/staged");
        Files.createDirectories(targetDir);

        final Path targetFile = targetDir.resolve("image-state-configmap.yaml");
        Files.writeString(targetFile, yaml, StandardCharsets.UTF_8);

        logInfo(
            "Wrote staged image-state ConfigMap manifest to "
                + targetFile
                + " ("
                + yaml.length()
                + " bytes)");
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to write image-state manifest", ex);
      }
    }
  }

  /**
   * Instance topic — fans in the prepared host + provisioned resources to create the Incus
   * instance. Reads both flux inputs and its ambient (bootstrap context) by construction; pushes
   * its single output through its {@link Sink}. Holds no reference to the accumulator.
   */
  private final class InstanceStage implements Topic.Execution {
    private final Supplier<BootstrapContext> context;
    private final Supplier<PreparedHost> prepared;
    private final Supplier<ProvisionedResources> provisioned;
    private final Sink sink;

    InstanceStage(
        Supplier<BootstrapContext> context,
        Supplier<PreparedHost> prepared,
        Supplier<ProvisionedResources> provisioned,
        Sink sink) {
      this.context = context;
      this.prepared = prepared;
      this.provisioned = provisioned;
      this.sink = sink;
    }

    /** The write-face of the instance topic — the launched instance. */
    interface Sink extends Topic.Sink {
      void instance(Instance instance);
    }

    @Override
    public String role() {
      return "instance";
    }

    InstanceStage create() {
      final BootstrapContext context = this.context.get();
      final PreparedHost prepared = this.prepared.get();
      final ProvisionedResources provisioned = this.provisioned.get();
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
          prepared.provisioning().targets().staticTargets().entrySet()) {
        // Wire format kept as `slice.<name>` to avoid a one-time instance replace from the
        // rename. Source code now uses Target vocabulary; the on-instance key migrates the day
        // a static-target checksum changes for a real reason.
        instanceConfig.put("user.rke2lab.provisioning.slice." + entry.getKey(), entry.getValue());
      }

      instanceConfig.put("user.rke2lab.imageBuildChecksum", provisioned.imageChecksum());

      sink.instance(
          new Instance(
              "seed-instance",
              InstanceArgs.builder()
                  .name(context.config().nodeName())
                  .project(provisioned.projectName())
                  .image(provisioned.imageFingerprint())
                  .profiles(provisioned.profileName().applyValue(List::of))
                  .config(instanceConfig)
                  .running(true)
                  .devices(seedInstanceDevices(prepared.nixosPaths()))
                  .build(),
              instanceOptions()));
      return this;
    }

    private CustomResourceOptions instanceOptions() {
      return CustomResourceOptions.builder()
          .provider(provisioned.get().providerContext().provider())
          .deleteBeforeReplace(true)
          .replaceOnChanges(List.of("config", "config.*"))
          .ignoreChanges(List.of("image"))
          .build();
    }
  }

  /**
   * Synthesize the image-state ConfigMap via CDK8s with resolved values.
   *
   * <p>This is a standalone synthesis entrypoint for staged resources. It does NOT use
   * ManifestSynthesisContext or AbstractManifestUnit - those are for the normal host-state
   * synthesis flow. This creates the ConfigMap directly using CDK8s primitives.
   *
   * @return YAML manifest string
   */
  record ImageStateConfig(
      String clusterName,
      String imageAlias,
      String imageFingerprint,
      String imageBuildChecksum,
      String incusProject,
      String incusRemoteAddress) {

    static Builder builder(IncusResourceBootstrap instance) {
      return new Builder(instance);
    }

    static final class Builder {
      private final IncusResourceBootstrap instance;
      private @MonotonicNonNull String clusterName;
      private @MonotonicNonNull String imageAlias;
      private @MonotonicNonNull String imageFingerprint;
      private @MonotonicNonNull String imageBuildChecksum;
      private @MonotonicNonNull String incusProject;
      private @MonotonicNonNull String incusRemoteAddress;

      private Builder(IncusResourceBootstrap instance) {
        this.instance = instance;
      }

      Builder clusterName(String clusterName) {
        this.clusterName = clusterName;
        return this;
      }

      Builder imageAlias(String imageAlias) {
        this.imageAlias = imageAlias;
        return this;
      }

      Builder imageFingerprint(String imageFingerprint) {
        this.imageFingerprint = imageFingerprint;
        return this;
      }

      Builder imageBuildChecksum(String imageBuildChecksum) {
        this.imageBuildChecksum = imageBuildChecksum;
        return this;
      }

      Builder incusProject(String incusProject) {
        this.incusProject = incusProject;
        return this;
      }

      Builder incusRemoteAddress(String incusRemoteAddress) {
        this.incusRemoteAddress = incusRemoteAddress;
        return this;
      }

      ImageStateConfig build() {
        return new ImageStateConfig(
            Objects.requireNonNull(clusterName, "clusterName"),
            Objects.requireNonNull(imageAlias, "imageAlias"),
            Objects.requireNonNull(imageFingerprint, "imageFingerprint"),
            Objects.requireNonNull(imageBuildChecksum, "imageBuildChecksum"),
            Objects.requireNonNull(incusProject, "incusProject"),
            Objects.requireNonNull(incusRemoteAddress, "incusRemoteAddress"));
      }

      String synthesize() {
        return instance.synthesizeImageStateConfigMapYaml(build());
      }
    }
  }

  private String synthesizeImageStateConfigMapYaml(ImageStateConfig config) {
    final Path tempDir;
    try {
      tempDir = Files.createTempDirectory("cdk8s-staged-");
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to create temp dir for CDK8s synthesis", ex);
    }

    try {
      final App app = new App(AppProps.builder().outdir(tempDir.toString()).build());
      final Chart chart = new Chart(app, "staged-resources");

      final ApiObject namespace =
          new ApiObject(
              chart,
              "namespace-capn-system",
              ApiObjectProps.builder()
                  .apiVersion("v1")
                  .kind("Namespace")
                  .metadata(
                      ApiObjectMetadata.builder()
                          .name("capn-system")
                          .annotations(
                              Map.of(
                                  "package",
                                  "cluster-api/image-state",
                                  "description",
                                  "Stage A → Stage B image identity handoff"))
                          .build())
                  .build());

      final Map<String, String> configMapData =
          Map.of(
              "imageAlias",
              config.imageAlias(),
              "imageFingerprint",
              config.imageFingerprint(),
              "imageBuildChecksum",
              config.imageBuildChecksum(),
              "incusProject",
              config.incusProject(),
              "incusRemoteAddress",
              config.incusRemoteAddress());

      final ApiObject configMap =
          new ApiObject(
              chart,
              "configmap-image-state",
              ApiObjectProps.builder()
                  .apiVersion("v1")
                  .kind("ConfigMap")
                  .metadata(
                      ApiObjectMetadata.builder()
                          .name(config.clusterName() + "-image-state")
                          .namespace("capn-system")
                          .annotations(
                              Map.of(
                                  "package",
                                  "cluster-api/image-state",
                                  "description",
                                  "Stage A → Stage B image identity handoff"))
                          .build())
                  .build());

      configMap.addDependency(namespace);
      configMap.addJsonPatch(JsonPatch.add("/data", configMapData));

      app.synth();

      final Path manifestFile = tempDir.resolve("staged-resources.k8s.yaml");
      if (!Files.exists(manifestFile)) {
        throw new IllegalStateException(
            "CDK8s synthesis did not produce expected file: " + manifestFile);
      }
      return Files.readString(manifestFile, StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to read CDK8s synthesized manifest", ex);
    } finally {
      try {
        Files.walk(tempDir)
            .sorted((a, b) -> -a.compareTo(b))
            .forEach(
                path -> {
                  try {
                    Files.delete(path);
                  } catch (IOException ignored) {
                  }
                });
      } catch (IOException ignored) {
      }
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
        bootstrapContext
            .incusImportLookup()
            .normalizeImportId(
                bootstrapContext
                    .incusImportLookup()
                    .existingProjectId(context, bootstrapContext.config().incusProject()));

    final CustomResourceOptions.Builder optionsBuilder =
        CustomResourceOptions.builder().provider(context.provider()).retainOnDelete(true);
    if (!existingProjectId.isBlank()) {
      optionsBuilder.importId(existingProjectId);
    }

    return new Project(
        "seed-project",
        ProjectArgs.builder()
            .name(bootstrapContext.config().incusProject())
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
        ProfileArgs.builder()
            .name(bootstrapContext.config().profileName())
            .project(bootstrapContext.config().incusProject());
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
    bootstrapContext.launchSecretsUpdater().ensureTokensPresent(secretsFile);
  }

  private ClusterNetworkBlueprint deriveBlueprint(String nodeName) {
    return ClusterNetworkBlueprint.builder()
        .cluster(bootstrapContext.config().clusterName())
        .node(nodeName)
        .deriveRecipeModel()
        .build();
  }

  private List<InstanceDeviceArgs> seedInstanceDevices(BootstrapPaths hostPaths) {
    final ClusterNetworkBlueprint managementNodeBlueprint =
        deriveBlueprint(bootstrapContext.config().nodeName());

    return DeviceMountPipeline.builder()
        .lanNic(
            bootstrapContext.config().lanBridgeParent(),
            managementNodeBlueprint.lan().hostMacaddr().value())
        .vmnetNic(
            bootstrapContext.config().vmnetNetworkName(),
            managementNodeBlueprint.wan().hostMacaddr().value())
        .kmsgDevice()
        .zfsDevice()
        .disk(
            "worktree.dir",
            hostPaths.worktreeRoot(),
            BootstrapPaths.HostPathCatalog.WORKTREE.path())
        .disk(
            "rke2lab.environment.dir",
            hostPaths.runtimeEnvConfigRoot(),
            BootstrapPaths.HostPathCatalog.ENV.path())
        .disk(
            "rke2lab.scripts.dir",
            hostPaths.scriptsRoot(),
            BootstrapPaths.HostPathCatalog.SCRIPTS.path())
        .disk("git.dir", hostPaths.gitRoot(), BootstrapPaths.HostPathCatalog.GIT_WORKTREE.path())
        .disk(
            "rke2lab.systemd.libexec.dir",
            hostPaths.systemdLibexecRoot(),
            BootstrapPaths.HostPathCatalog.SYSTEMD_LIBEXEC.path())
        .disk(
            "rke2lab.system.dir",
            hostPaths.systemdRoot(),
            BootstrapPaths.HostPathCatalog.SYSTEMD_UNITS.path())
        .disk(
            "manifests.dir",
            hostPaths.manifestsRoot(),
            BootstrapPaths.HostPathCatalog.MANIFESTS.path())
        .disk(
            "rke2.config.dir",
            hostPaths.runtimeRke2ConfigRoot(),
            BootstrapPaths.HostPathCatalog.RKE2_CONFIG.path())
        .disk(
            "cloudconfig.nocloud.dir",
            hostPaths.runtimeCloudConfigRoot(),
            BootstrapPaths.HostPathCatalog.CLOUDCONFIG_NOCLOUD.path())
        .disk("shared.dir", hostPaths.shareRoot(), BootstrapPaths.HostPathCatalog.SHARE.path())
        .disk("daemonset.dir", hostPaths.daemonsetRoot(), DaemonsetLogPolicy.GUEST_ROOT_PATH)
        .disk(
            "kubeconfig.dir",
            hostPaths.kubeconfigRoot(),
            BootstrapPaths.HostPathCatalog.KUBECONFIG.path())
        .disk("nocloud.dir", hostPaths.cloudSeedRoot(), "/var/lib/cloud/seed/nocloud")
        .build();
  }

  private final class DefaultBootstrapNodeEnvContext implements NodeEnvContext {

    private final ClusterNetworkBlueprint managementNodeBlueprint =
        deriveBlueprint(bootstrapContext.config().nodeName());

    @Override
    public Path rootPath() {
      return BootstrapPaths.HostPathCatalog.ROOT.asPath();
    }

    @Override
    public Path envDirPath() {
      return BootstrapPaths.HostPathCatalog.ENV.asPath();
    }

    @Override
    public Path scriptsDirPath() {
      return BootstrapPaths.HostPathCatalog.SCRIPTS.asPath();
    }

    @Override
    public Path systemdDirPath() {
      return BootstrapPaths.HostPathCatalog.SYSTEMD_UNITS.asPath();
    }

    @Override
    public Path configDirPath() {
      return BootstrapPaths.HostPathCatalog.RKE2_CONFIG.asPath();
    }

    @Override
    public Path cloudconfigNocloudDirPath() {
      return BootstrapPaths.HostPathCatalog.CLOUDCONFIG_NOCLOUD.asPath();
    }

    @Override
    public Path manifestsDirPath() {
      return BootstrapPaths.HostPathCatalog.MANIFESTS.asPath();
    }

    @Override
    public Path sharedDirPath() {
      return BootstrapPaths.HostPathCatalog.SHARE.asPath();
    }

    @Override
    public Path kubeconfigDirPath() {
      return BootstrapPaths.HostPathCatalog.KUBECONFIG.asPath();
    }

    @Override
    public int nodeId() {
      return managementNodeBlueprint.node().id();
    }

    @Override
    public String nodeName() {
      return bootstrapContext.config().nodeName();
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
      return bootstrapContext.config().clusterName();
    }

    @Override
    public String clusterToken() {
      return bootstrapContext.config().clusterName(); // Using cluster name as token (bioskop)
    }

    @Override
    public String clusterDomain() {
      return "cluster.local";
    }

    @Override
    public String incusRemoteName() {
      return bootstrapContext.config().incusDefaultRemote();
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

    /** Catalog of host-mounted paths (single source of truth for container paths). */
    enum HostPathCatalog {
      ROOT("/srv/host"),
      WORKTREE("/srv/host/rke2lab-worktree.d"),
      ENV("/srv/host/rke2lab-environment.d"),
      SCRIPTS("/srv/host/systemd-scripts.d"),
      GIT_WORKTREE("/srv/host/git-worktree.d"),
      SYSTEMD_LIBEXEC("/srv/host/systemd-libexec.d"),
      SYSTEMD_UNITS("/srv/host/systemd-units.d"),
      MANIFESTS("/srv/host/rke2-manifests.d"),
      RKE2_CONFIG("/srv/host/rke2-config.d"),
      CLOUDCONFIG_NOCLOUD("/srv/host/cloudconfig-nocloud.d"),
      SHARE("/srv/host/rke2lab-share.d"),
      KUBECONFIG("/srv/host/rke2lab-kube.d");

      private final String containerPath;

      HostPathCatalog(String containerPath) {
        this.containerPath = containerPath;
      }

      /** Returns the absolute container path (e.g., "/srv/host/rke2-manifests.d"). */
      public String path() {
        return containerPath;
      }

      /** Returns the directory name only (e.g., "rke2-manifests.d"). */
      public String dirName() {
        return Path.of(containerPath).getFileName().toString();
      }

      /** Returns the container path as a Path object. */
      public Path asPath() {
        return Path.of(containerPath);
      }
    }

    private static Builder builder() {
      return new Builder();
    }

    private static BootstrapPaths fromLocalWorktree(
        Path worktreeRoot, String clusterName, String nodeName) {
      // Operator-friendly layout: .local.d/<cluster>/<node>/ owns everything per-node, with the
      // cluster-scoped kubeconfig at .local.d/<cluster>/kubeconfig.yaml. No more
      // var/{run,lib}/incus/<cluster>/<node>/ split — that mirrored the host-fs convention but
      // forced the operator to mentally translate between provisioner storage and "the master
      // node of cluster bioskop". One short cd lands now in the per-node tree.
      final Path stateRoot = worktreeRoot.resolve(".local.d");
      final Path clusterRoot = stateRoot.resolve(clusterName);
      final Path nodeRoot = clusterRoot.resolve(nodeName);
      final Path hostResourceRoot = nodeRoot.resolve("host");
      final Path manifestsRoot = hostResourceRoot.resolve(HostPathCatalog.MANIFESTS.dirName());
      final Path runtimeRoot = manifestsRoot.resolve("runtime");
      final Path systemdStagingRoot = hostResourceRoot.resolve("systemd.d");
      final Path scriptsRoot = systemdStagingRoot.resolve(HostPathCatalog.SCRIPTS.dirName());
      final Path systemdLibexecRoot =
          systemdStagingRoot.resolve(HostPathCatalog.SYSTEMD_LIBEXEC.dirName());
      final Path systemdRoot = systemdStagingRoot.resolve(HostPathCatalog.SYSTEMD_UNITS.dirName());

      return BootstrapPaths.builder()
          .worktreeRoot(worktreeRoot)
          .stateRoot(stateRoot)
          .clusterNodeRoot(nodeRoot)
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
          .gitRoot(
              Objects.requireNonNull(
                  Objects.requireNonNull(worktreeRoot.getParent(), "worktreeRoot parent")
                      .getParent(),
                  "worktreeRoot grandparent (git root)"))
          .shareRoot(stateRoot.resolve("share"))
          .kubeconfigRoot(clusterRoot)
          .cloudSeedRoot(nodeRoot.resolve("cloud.d"))
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
      private @MonotonicNonNull Path worktreeRoot;
      private @MonotonicNonNull Path stateRoot;
      private @MonotonicNonNull Path clusterNodeRoot;
      private @MonotonicNonNull Path manifestsRoot;
      private @MonotonicNonNull Path runtimeRke2ConfigRoot;
      private @MonotonicNonNull Path runtimeCloudConfigRoot;
      private @MonotonicNonNull Path runtimeEnvConfigRoot;
      private @MonotonicNonNull Path secretsFile;
      private @MonotonicNonNull Path assetsRoot;
      private @MonotonicNonNull Path daemonsetRoot;
      private @MonotonicNonNull Path scriptsRoot;
      private @MonotonicNonNull Path systemdLibexecRoot;
      private @MonotonicNonNull Path systemdRoot;
      private @MonotonicNonNull Path gitRoot;
      private @MonotonicNonNull Path shareRoot;
      private @MonotonicNonNull Path kubeconfigRoot;
      private @MonotonicNonNull Path cloudSeedRoot;

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
            Objects.requireNonNull(worktreeRoot, "worktreeRoot"),
            Objects.requireNonNull(stateRoot, "stateRoot"),
            Objects.requireNonNull(clusterNodeRoot, "clusterNodeRoot"),
            Objects.requireNonNull(manifestsRoot, "manifestsRoot"),
            Objects.requireNonNull(runtimeRke2ConfigRoot, "runtimeRke2ConfigRoot"),
            Objects.requireNonNull(runtimeCloudConfigRoot, "runtimeCloudConfigRoot"),
            Objects.requireNonNull(runtimeEnvConfigRoot, "runtimeEnvConfigRoot"),
            Objects.requireNonNull(secretsFile, "secretsFile"),
            Objects.requireNonNull(assetsRoot, "assetsRoot"),
            Objects.requireNonNull(daemonsetRoot, "daemonsetRoot"),
            Objects.requireNonNull(scriptsRoot, "scriptsRoot"),
            Objects.requireNonNull(systemdLibexecRoot, "systemdLibexecRoot"),
            Objects.requireNonNull(systemdRoot, "systemdRoot"),
            Objects.requireNonNull(gitRoot, "gitRoot"),
            Objects.requireNonNull(shareRoot, "shareRoot"),
            Objects.requireNonNull(kubeconfigRoot, "kubeconfigRoot"),
            Objects.requireNonNull(cloudSeedRoot, "cloudSeedRoot"));
      }
    }
  }

  private void ensureNetwork(
      IncusProviderContext context, String networkName, Resource projectDependency) {

    class NetworkSetup {
      boolean shouldSkip() {
        if (networkName.equals(bootstrapContext.config().lanBridgeParent())) {
          logInfo(
              "incus network ensure: skipping canonical host-provided bridge (name="
                  + networkName
                  + ")");
          return true;
        }

        final String networkProject = resolveNetworkProject();
        if (bootstrapContext
            .incusImportLookup()
            .isUnmanagedNetwork(context, networkName, networkProject)) {
          logInfo(
              "incus network ensure: skipping unmanaged bridge reported by provider (name="
                  + networkName
                  + ")");
          return true;
        }

        return false;
      }

      String resolveNetworkProject() {
        return networkName.equals(bootstrapContext.config().vmnetNetworkName())
            ? "default"
            : bootstrapContext.config().incusProject();
      }

      String resolveExistingNetworkId(String networkProject) {
        return bootstrapContext
            .incusImportLookup()
            .normalizeImportId(
                bootstrapContext
                    .incusImportLookup()
                    .existingNetworkId(context, networkName, networkProject));
      }

      boolean shouldSkipExistingVmnet(String existingNetworkId) {
        return !existingNetworkId.isBlank()
            && networkName.equals(bootstrapContext.config().vmnetNetworkName());
      }

      NetworkArgs buildNetworkArgs(String networkProject, String existingNetworkId) {
        final NetworkArgs.Builder builder = NetworkArgs.builder().name(networkName).type("bridge");

        if (networkName.equals(bootstrapContext.config().vmnetNetworkName())) {
          builder.project("default");
          builder.config(vmnetBridgeConfig());
        } else if (existingNetworkId.isBlank()) {
          builder.project(networkProject);
        }

        return builder.build();
      }

      CustomResourceOptions buildNetworkOptions(String existingNetworkId) {
        final List<String> networkIgnoreChanges = new ArrayList<>(List.of("project"));
        if (!existingNetworkId.isBlank()) {
          networkIgnoreChanges.addAll(List.of("config", "remote", "target"));
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

        return optionsBuilder.build();
      }

      Map<String, String> vmnetBridgeConfig() {
        final ClusterNetworkBlueprint managementNodeBlueprint =
            deriveBlueprint(bootstrapContext.config().nodeName());

        final String clusterGatewayWithPrefix =
            managementNodeBlueprint.host().clusterGatewayInetaddr().getHostAddress()
                + "/"
                + managementNodeBlueprint.host().clusterCidr().prefixLength();

        final String dhcpRange = managementNodeBlueprint.wan().dhcpRange();

        final String rawDnsmasq =
            CLUSTER_NODE_NAMES.stream()
                .map(IncusResourceBootstrap.this::deriveBlueprint)
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
    }

    final NetworkSetup setup = new NetworkSetup();

    if (setup.shouldSkip()) {
      return;
    }

    final String networkProject = setup.resolveNetworkProject();
    final String existingNetworkId = setup.resolveExistingNetworkId(networkProject);

    if (setup.shouldSkipExistingVmnet(existingNetworkId)) {
      return;
    }

    final NetworkArgs networkArgs = setup.buildNetworkArgs(networkProject, existingNetworkId);
    final CustomResourceOptions resourceOptions = setup.buildNetworkOptions(existingNetworkId);

    new Network("seed-network-" + networkName, networkArgs, resourceOptions);
  }

  private String clusterNodeLeaseHostname(String nodeName) {
    return bootstrapContext.config().clusterName() + "-" + nodeName;
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
    private boolean syncStagingToFinal(
        Path stagingRoot,
        Path hostAssetRoot,
        BootstrapConfig config,
        ControlplanePolicy policy,
        SystemdTarget systemdTarget) {
      try {
        if (isNoOpDeploy(stagingRoot, hostAssetRoot)) {
          deleteRecursively(stagingRoot);
          return false;
        }

        final int slotSeq = promoteToSlot(stagingRoot, hostAssetRoot);
        final Path slotPath = stagingPathFor(hostAssetRoot, slotSeq);

        writeSlotManifest(slotPath, slotSeq, config, policy, systemdTarget);

        syncToFinal(slotPath, hostAssetRoot, slotSeq);
        symlinkManifestToMasterLevel(hostAssetRoot);

        return true;
      } catch (IOException ex) {
        throw new IllegalStateException(
            "Failed to sync staging to final host asset root: " + hostAssetRoot, ex);
      }
    }

    private boolean isNoOpDeploy(Path stagingRoot, Path hostAssetRoot) throws IOException {
      return Files.exists(hostAssetRoot) && directoriesAreIdentical(stagingRoot, hostAssetRoot);
    }

    private int promoteToSlot(Path stagingRoot, Path hostAssetRoot) throws IOException {
      final int slotSeq = allocateSlot(hostAssetRoot);
      final Path slotPath = stagingPathFor(hostAssetRoot, slotSeq);
      deleteRecursively(slotPath);
      deleteRecursively(backupPathFor(hostAssetRoot, slotSeq));
      Files.move(stagingRoot, slotPath);
      return slotSeq;
    }

    private void syncToFinal(Path slotPath, Path hostAssetRoot, int slotSeq) throws IOException {
      if (Files.exists(hostAssetRoot)) {
        backup(hostAssetRoot, backupPathFor(hostAssetRoot, slotSeq));
        syncDirectories(slotPath, hostAssetRoot);
      } else {
        backup(slotPath, hostAssetRoot);
      }
    }

    /**
     * Writes the slot manifest YAML to the slot directory.
     *
     * <p>The manifest describes what's in this slot: git commit, policy flags, discovered flox
     * environments, etc. Future renewals can read these manifests to select the newest valid slot.
     */
    private void writeSlotManifest(
        Path slotPath,
        int slotSeq,
        BootstrapConfig config,
        ControlplanePolicy policy,
        SystemdTarget systemdTarget)
        throws IOException {

      final Instant timestamp = Instant.now();
      final Path repoRoot = Paths.get(System.getProperty("user.dir"));
      final Optional<HostSlotManifest.GitInfo> gitInfo =
          GitMetadataExtractor.extract(repoRoot, policy.provisioning().gitDirtyCheck());
      final String buildId = GitMetadataExtractor.generateBuildId(gitInfo);

      // Build manifest using CDK8s
      final App app = App.Builder.create().outdir(slotPath.toString()).build();
      final Chart chart = Chart.Builder.create(app, "manifest").build();

      final HostSlotManifest.Builder manifestBuilder =
          HostSlotManifest.builder()
              .slotType(HostSlotManifest.SlotType.STAGING)
              .slotSequence(slotSeq)
              .timestamp(timestamp)
              .buildId(buildId)
              .policy(policy)
              .source(
                  HostSlotManifest.SourceType.FRESH_BUILD, slotPath.toString(), Optional.empty());

      gitInfo.ifPresent(
          info ->
              manifestBuilder.gitInfo(
                  info.commit(),
                  info.commitFull(),
                  info.branch(),
                  info.dirty(),
                  info.commitMessage(),
                  info.author(),
                  info.commitDate()));

      // Add discovered flox environments
      for (var env : systemdTarget.floxAssetService().discoveredEnvironments()) {
        manifestBuilder.addFloxEnvironment(env.category(), env.name(), true);
      }

      // Add staged manifests (post-cluster resources)
      if (policy.manifestLink().clusterApiEnabled()) {
        manifestBuilder.addStagedManifest(
            "cluster-api", "staged", "Image-state ConfigMap for Cluster API");
      }

      manifestBuilder.build(chart, "slot-manifest");

      // Synthesize to YAML - CDK8s writes to slotPath/.rke2lab-manifest.yaml
      app.synth();

      // CDK8s creates manifest-c8XXXXX.k8s.yaml - rename to our expected name
      final Path synthesized = slotPath.resolve("manifest.k8s.yaml");
      final Path target = slotPath.resolve(".rke2lab-manifest.yaml");
      if (Files.exists(synthesized)) {
        Files.move(synthesized, target, StandardCopyOption.REPLACE_EXISTING);
      }
    }

    /**
     * Symlink host/.rke2lab-manifest.yaml to MANIFEST.yaml at master level for operator visibility.
     *
     * <p>Operators navigating .local.d/bioskop/master/ see MANIFEST.yaml immediately without
     * drilling into host/.
     */
    private void symlinkManifestToMasterLevel(Path hostAssetRoot) throws IOException {
      final Path hostManifest = hostAssetRoot.resolve(".rke2lab-manifest.yaml");
      final Path masterManifest =
          Objects.requireNonNull(hostAssetRoot.getParent(), "hostAssetRoot parent")
              .resolve("MANIFEST.yaml");

      // Remove existing symlink/file if present
      Files.deleteIfExists(masterManifest);

      // Create relative symlink: MANIFEST.yaml -> host/.rke2lab-manifest.yaml
      final Path relativeTarget =
          Objects.requireNonNull(masterManifest.getParent(), "masterManifest parent")
              .relativize(hostManifest);
      Files.createSymbolicLink(masterManifest, relativeTarget);
    }

    private boolean directoriesAreIdentical(Path left, Path right) throws IOException {
      final Set<Path> leftRelativePaths = collectRelativePaths(left);
      final Set<Path> rightRelativePaths = collectRelativePaths(right);
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

    private Set<Path> collectRelativePaths(Path root) throws IOException {
      final Set<Path> entries = new HashSet<>();
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              final Path relative = root.relativize(dir);
              if (!relative.toString().isEmpty()) {
                entries.add(relative);
              }
              // Track the .flox/ directory itself but not its volatile contents — flox manages
              // run/, cache/, lib/, log/ live, so peeking into them produces phantom diffs that
              // would force unnecessary slot rotations on no-op deploys.
              if (isFloxRuntimeStateDir(dir)) {
                return FileVisitResult.SKIP_SUBTREE;
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
      FileTime oldestMtime = null;
      for (int seq = 0; seq < retentionCount; seq++) {
        final Path stagingPath = stagingPathFor(hostAssetRoot, seq);
        if (!Files.exists(stagingPath)) {
          return seq;
        }
        final FileTime mtime = Files.getLastModifiedTime(stagingPath);
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

    private static final Set<String> FLOX_VOLATILE_SUBTREES = Set.of("run", "cache", "lib", "log");

    /**
     * Returns true if {@code dir} is flox-managed state that must not be backed up or synced.
     *
     * <p>Two cases are skipped:
     *
     * <ul>
     *   <li>A {@code .flox/} that is NOT under an {@code environment.d/} tree — activation-root
     *       runtime state (the plugin install location), opaque to us.
     *   <li>A volatile subtree ({@code run/}, {@code cache/}, {@code lib/}, {@code log/}) directly
     *       under any {@code .flox/} — flox owns these (its {@code .gitignore} declares them) and
     *       {@code run/} holds host/arch-specific activation symlinks like {@code <arch>.<env>.dev}
     *       that dangle on a cross-arch host and fail a follow-the-link copy.
     * </ul>
     *
     * <p>Build artifacts under {@code environment.d/<category>/<name>/.flox/} ({@code env/}, {@code
     * env.json}, {@code manifest.lock}) are still copied — only the volatile subtrees are skipped,
     * so the asset materializer's output survives while live flox state stays untouched.
     */
    private static boolean isFloxRuntimeStateDir(Path dir) {
      final Path parent = dir.getParent();
      if (parent != null
          && ".flox".equals(String.valueOf(parent.getFileName()))
          && FLOX_VOLATILE_SUBTREES.contains(String.valueOf(dir.getFileName()))) {
        return true;
      }
      if (!".flox".equals(String.valueOf(dir.getFileName()))) {
        return false;
      }
      Path current = parent;
      while (current != null) {
        if ("environment.d".equals(String.valueOf(current.getFileName()))) {
          return false;
        }
        current = current.getParent();
      }
      return true;
    }

    private void backup(Path source, Path target) throws IOException {
      Files.createDirectories(target);
      Files.walkFileTree(
          source,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException {
              if (isFloxRuntimeStateDir(dir)) {
                return FileVisitResult.SKIP_SUBTREE;
              }
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
      // Collect all paths in source (for copying/updating). .flox/ subtrees are skipped on both
      // sides so flox runtime state stays untouched: we don't copy it, and we don't delete it.
      final Set<Path> sourcePaths = new HashSet<>();
      Files.walkFileTree(
          source,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException {
              if (isFloxRuntimeStateDir(dir)) {
                return FileVisitResult.SKIP_SUBTREE;
              }
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
                  StandardCopyOption.REPLACE_EXISTING,
                  StandardCopyOption.COPY_ATTRIBUTES);
              return FileVisitResult.CONTINUE;
            }
          });

      // Collect all paths in target (for deletion of stale entries)
      final Set<Path> targetPaths = new HashSet<>();
      if (Files.exists(target)) {
        Files.walkFileTree(
            target,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (isFloxRuntimeStateDir(dir)) {
                  return FileVisitResult.SKIP_SUBTREE;
                }
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
      final List<Path> toDelete =
          targetPaths.stream()
              .filter(p -> !sourcePaths.contains(p))
              .map(target::resolve)
              .sorted(Comparator.reverseOrder()) // Delete files before dirs
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

  private static final class CloudInitTarget implements ProvisioningTarget {
    private final NodeConfigRegenerator nodeConfigRegenerator;
    private final Path runtimeCloudConfigRoot;
    private final Path cloudSeedRoot;

    private CloudInitTarget(
        NodeConfigRegenerator nodeConfigRegenerator,
        Path runtimeCloudConfigRoot,
        Path cloudSeedRoot) {
      this.nodeConfigRegenerator = nodeConfigRegenerator;
      this.runtimeCloudConfigRoot = runtimeCloudConfigRoot;
      this.cloudSeedRoot = cloudSeedRoot;
    }

    @Override
    public String name() {
      return "cloud-init";
    }

    @Override
    public TargetReloadPolicy reloadPolicy() {
      return TargetReloadPolicy.STATIC;
    }

    @Override
    public void materialize(BootstrapPaths paths) throws IOException {
      // Cloud-seed (user-data/meta-data/network-config) is this target's own output, derived
      // deterministically from the runtime/cloud-config ConfigMap. Cloud-init reads it once at
      // first boot, so the only STATIC target.
      nodeConfigRegenerator.regenerateCloudConfigDir(runtimeCloudConfigRoot, cloudSeedRoot);
    }

    @Override
    public List<Path> getMaterializedPaths() {
      return List.of(cloudSeedRoot);
    }
  }

  /**
   * K8s manifests target — owns the synthesized + exploded {@code manifests.d/} tree that rke2
   * watches via inotify. DYNAMIC: changes hot-reload through rke2's manifest watch, no instance
   * renewal.
   *
   * <p>The tree itself is filled by {@link #synthesizeAndExplodeManifests} upstream of target
   * registration; this target carries no materialize body of its own and only declares ownership of
   * the path so the registry can compute its checksum.
   */
  private static final class K8sTarget implements ProvisioningTarget {
    private final Path manifestsRoot;

    private K8sTarget(Path manifestsRoot) {
      this.manifestsRoot = manifestsRoot;
    }

    @Override
    public String name() {
      return "k8s";
    }

    @Override
    public TargetReloadPolicy reloadPolicy() {
      return TargetReloadPolicy.DYNAMIC;
    }

    @Override
    public void materialize(BootstrapPaths paths) {
      // No-op: manifests tree is materialised by ManifestSynthesisService + explode upstream of
      // target registration. K8sTarget declares ownership for checksum + inventory only.
    }

    @Override
    public List<Path> getMaterializedPaths() {
      return List.of(manifestsRoot);
    }
  }

  /**
   * rke2 config target — passive. cdk8s synth + explode produces ConfigMaps under {@code
   * manifestsRoot/runtime/rke2-config/} (cluster-init token, etcd flags, advertise-address,
   * TLS-SAN, …); rke2-server reads them at startup. DYNAMIC: rke2 picks up changes on its next
   * (re)start triggered by systemd or the manifests-install service.
   */
  private static final class Rke2ConfigTarget implements ProvisioningTarget {
    private final Path rke2ConfigRoot;

    private Rke2ConfigTarget(Path rke2ConfigRoot) {
      this.rke2ConfigRoot = rke2ConfigRoot;
    }

    @Override
    public String name() {
      return "rke2-config";
    }

    @Override
    public TargetReloadPolicy reloadPolicy() {
      return TargetReloadPolicy.DYNAMIC;
    }

    @Override
    public void materialize(BootstrapPaths paths) {
      // No-op: cdk8s synth+explode fills rke2ConfigRoot upstream of target registration.
    }

    @Override
    public List<Path> getMaterializedPaths() {
      return List.of(rke2ConfigRoot);
    }
  }

  /**
   * rke2lab env target — active producer. Writes per-layer env-section ConfigMaps + the aggregated
   * 99-configmap overlay under {@code manifestsRoot/runtime/env-config/}. The host's {@code
   * rke2lab-bootstrap-env.sh} sources these YAMLs and turns their {@code data:} keys into shell
   * environment variables. DYNAMIC: re-sourced on the next service restart.
   */
  private static final class Rke2labEnvTarget implements ProvisioningTarget {
    private final NodeEnvOverlayService overlayService;
    private final NodeEnvContext layerContext;
    private final ControlplanePolicy policy;
    private final Path envConfigRoot;
    private Map<String, Object> layerEnvSummary = Map.of();

    private Rke2labEnvTarget(
        NodeEnvOverlayService overlayService,
        NodeEnvContext layerContext,
        ControlplanePolicy policy,
        Path envConfigRoot) {
      this.overlayService = overlayService;
      this.layerContext = layerContext;
      this.policy = policy;
      this.envConfigRoot = envConfigRoot;
    }

    @Override
    public String name() {
      return "rke2lab-env";
    }

    @Override
    public TargetReloadPolicy reloadPolicy() {
      return TargetReloadPolicy.DYNAMIC;
    }

    @Override
    public void materialize(BootstrapPaths paths) throws IOException {
      // Host-resolved seed variables the manifests world cannot know: bootstrap constants + the
      // controlplane policy env. Contributor-owned sections override these inside the service.
      final Map<String, String> seedVariables = new LinkedHashMap<>();
      seedVariables.put("RKE2LAB_REPO_ROOT", BootstrapPaths.HostPathCatalog.WORKTREE.path());
      seedVariables.putAll(policy.toEnvMap());
      layerEnvSummary =
          overlayService.writeControlplaneOverlay(envConfigRoot, layerContext, seedVariables);
    }

    @Override
    public List<Path> getMaterializedPaths() {
      return List.of(envConfigRoot);
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

    private record LookupResult(String importId, LookupState state, Optional<Boolean> managed) {

      private static LookupResult found(String importId, @Nullable Boolean managed) {
        return new LookupResult(importId, LookupState.FOUND, Optional.ofNullable(managed));
      }

      private static LookupResult notFound() {
        return new LookupResult("", LookupState.NOT_FOUND, Optional.empty());
      }

      private static LookupResult failed() {
        return new LookupResult("", LookupState.FAILED, Optional.empty());
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

    private boolean isUnmanagedNetwork(
        IncusProviderContext context, String networkName, String incusProject) {
      final LookupResult projectScoped =
          resolveNetworkImportId(
              context,
              GetNetworkPlainArgs.builder().name(networkName).project(incusProject).build());
      if (projectScoped.state() == LookupState.FOUND) {
        return projectScoped.managed().map(Boolean.FALSE::equals).orElse(false);
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
        return unscoped.managed().map(Boolean.FALSE::equals).orElse(false);
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
      final String lower = summary.toLowerCase(Locale.ROOT);
      return lower.contains("not found");
    }

    private String summarizeLookupFailure(Exception ex) {
      if (ex == null) {
        return "unknown";
      }

      Throwable root = ex;
      while (root.getCause() != null
          && (root instanceof CompletionException || root instanceof ExecutionException)) {
        root = root.getCause();
      }

      final String type = root.getClass().getSimpleName();
      final String message = root.getMessage() == null ? "" : root.getMessage().trim();
      return message.isBlank() ? type : type + ": " + message;
    }

    private String normalizeImportId(@Nullable String value) {
      if (value == null) {
        return "";
      }
      final String trimmed = value.trim();
      return trimmed.isBlank() ? "" : trimmed;
    }
  }

  private static final class CloudConfigSecretRenderer {

    private final ManifestDocumentService documentService;

    private CloudConfigSecretRenderer(ManifestDocumentService documentService) {
      this.documentService = documentService;
    }

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
        return documentService.parseDocument(yamlSource);
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

    private Map<String, String> extractStringMap(@Nullable Object value) {
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

    private String asString(@Nullable Object value) {
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
     * Compute per-target checksums for independently reconcilable provisioning targets using the
     * fluent pipeline grammar.
     *
     * <p>The core target covers cloud-init source (the only STATIC target today); other targets are
     * discovered from the registry populated during materialization. STATIC targets trigger
     * instance renewal; DYNAMIC targets trigger reconciliation without renewal.
     *
     * @param paths bootstrap filesystem paths
     * @param registry component-populated target registry with reload policies
     * @return partitioned target checksums (static vs dynamic)
     */
    private static ProvisioningMetadata.Targets targetChecksums(
        BootstrapPaths paths, ProvisioningTargetRegistry registry) {
      final Map<String, String> allChecksums =
          TargetChecksumPipeline.begin(paths, registry)
              .during("cloud-init", stage -> stage.fromCloudInitRoots())
              .then()
              .during("registered components", components -> components.fromRegistry())
              .collectChecksums();

      // Partition by reload policy.
      final Map<String, String> staticTargets = new LinkedHashMap<>();
      final Map<String, String> dynamicTargets = new LinkedHashMap<>();

      for (Map.Entry<String, String> entry : allChecksums.entrySet()) {
        final String targetName = entry.getKey();
        final String checksum = entry.getValue();
        final Optional<TargetReloadPolicy> policy = registry.getReloadPolicy(targetName);

        if (policy.filter(TargetReloadPolicy.STATIC::equals).isPresent()) {
          staticTargets.put(targetName, checksum);
        } else if (policy.filter(TargetReloadPolicy.DYNAMIC::equals).isPresent()) {
          dynamicTargets.put(targetName, checksum);
        }
      }

      return ProvisioningMetadata.Targets.of(staticTargets, dynamicTargets);
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
      summary.put("scriptsMountPath", BootstrapPaths.HostPathCatalog.SCRIPTS.path());
      summary.put("unitsMountPath", BootstrapPaths.HostPathCatalog.SYSTEMD_UNITS.path());
      summary.put("systemdLibexecMountPath", BootstrapPaths.HostPathCatalog.SYSTEMD_LIBEXEC.path());
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
      phased.put("platform", new ArrayList<>());
      phased.put("cicd", new ArrayList<>());
      phased.put("tools", new ArrayList<>());
      phased.put("other", new ArrayList<>());

      for (String name : names) {
        // phaseForName always returns one of the keys pre-populated above (with "other" as the
        // total fallback), so the list is never absent.
        Objects.requireNonNull(phased.get(phaseForName(name)), "phase bucket").add(name);
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
      if (normalized.contains("replicator") || normalized.contains("cert-manager")) {
        return "platform";
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
