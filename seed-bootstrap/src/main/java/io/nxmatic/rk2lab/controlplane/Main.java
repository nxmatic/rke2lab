package io.nxmatic.rk2lab.controlplane;

import com.pulumi.Config;
import com.pulumi.Pulumi;
import com.pulumi.deployment.Deployment;
import io.nxmatic.rk2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rk2lab.controlplane.config.ConfigResolver;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rk2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rk2lab.controlplane.policy.EntryGatePolicyEnforcer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Entry point for the Pulumi management-cluster bootstrap program. */
public final class Main {

  private final BboxReconciliationOrchestrator bboxOrchestrator;
  private final ResourceManager resourceManager;
  private final OutputBuilder outputBuilder;

  private Main(boolean pulumiMode) {
    this.bboxOrchestrator = new BboxReconciliationOrchestrator(pulumiMode);
    this.resourceManager = new ResourceManager();
    this.outputBuilder = new OutputBuilder();
  }

  public static void main(String[] args) {
    final boolean pulumiMode = isPulumiEngineAvailable();
    final Main instance = new Main(pulumiMode);

    if (!pulumiMode) {
      instance.runStandalone();
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
            final boolean readinessEnabled = ConfigResolver.resolveReadinessEnabled(config);
            final boolean cleanWorktreeRequired =
                ConfigResolver.resolveCleanWorktreeRequired(config);
            final boolean bboxFailOnError = ConfigResolver.resolveBboxFailOnError(config);
            final Consumer<String> readinessLogger = message -> SeedLog.info("readiness", message);
            final Map<String, Object> outputs =
                instance.bootstrapAndCollectOutputs(
                    bootstrapConfig,
                    controlplanePolicy,
                    readinessEnabled,
                    cleanWorktreeRequired,
                    bboxFailOnError,
                    readinessLogger);
            outputs.forEach(context::export);
          } finally {
            SeedLog.clearPulumiLogSink();
          }
        });
  }

  private void runStandalone() {
    final BootstrapConfig bootstrapConfig = new BootstrapConfig.Builder().build();
    final ControlplanePolicy controlplanePolicy = ControlplanePolicy.defaults();
    final Consumer<String> readinessLogger = message -> SeedLog.info("readiness", message);
    final Map<String, Object> outputs =
        bootstrapAndCollectOutputs(
            bootstrapConfig, controlplanePolicy, true, true, true, readinessLogger);
    SeedLog.info(
        "standalone",
        "Pulumi engine not detected (missing PULUMI_MONITOR). Running in standalone mode.");
    SeedLog.info("standalone", "Bootstrap outputs:");
    outputs.forEach((key, value) -> SeedLog.info("standalone", key + "=" + value));
  }

  private static boolean isPulumiEngineAvailable() {
    final String monitor = System.getenv("PULUMI_MONITOR");
    return monitor != null && !monitor.isBlank();
  }

  private boolean isPulumiMode() {
    return isPulumiEngineAvailable();
  }

  private Map<String, Object> bootstrapAndCollectOutputs(
      BootstrapConfig config,
      ControlplanePolicy policy,
      boolean readinessEnabled,
      boolean cleanWorktreeRequired,
      boolean bboxFailOnError,
      Consumer<String> readinessLogger) {

    // Preflight checks
    EntryGatePolicyEnforcer.enforceAll(config.localWorktreePath(), cleanWorktreeRequired);
    RuntimeCommandPreflight.enforceRequiredCommands(
        java.util.List.of("ssh", "kubectl"), readinessLogger);
    RuntimeCommandPreflight.enforceRemoteCommandAvailable(
        config.imageBuilderHost(), "incus", readinessLogger);

    // Bbox reconciliation
    final BboxReconciliationOrchestrator.ReconciliationResult bboxResult =
        bboxOrchestrator.reconcile(config.localWorktreePath(), bboxFailOnError);

    // Incus bootstrap
    final IncusResourceBootstrap.BootstrapResult bootstrapResult =
        new IncusResourceBootstrap(config, policy).apply();

    // Systemd adapter launch
    final Map<String, Object> systemdAdapterLaunchSummary;
    if (isPulumiMode() && Deployment.getInstance().isDryRun()) {
      systemdAdapterLaunchSummary = SeedSystemdAdapterEndpointGate.deferredPreview(config);
    } else {
      systemdAdapterLaunchSummary =
          SeedSystemdAdapterEndpointGate.ensureReachable(config, readinessLogger);
    }

    // Resource creation via pipeline
    final ResourceCreationResult resourceResult =
        resourceManager.createResources(
            config,
            policy,
            readinessEnabled,
            readinessLogger,
            bootstrapResult,
            systemdAdapterLaunchSummary,
            isPulumiMode());

    // Build outputs
    return outputBuilder.buildOutputs(
        config, policy, bootstrapResult, bboxResult, systemdAdapterLaunchSummary, resourceResult);
  }

  /** Result of resource creation containing all created resources and summaries. */
  record ResourceCreationResult(
      Object readinessOutput,
      Object clusterReadinessResourceUrn,
      Object systemdAdapterResourceUrn,
      Object registryResourceUrn,
      Object imageBuildResourceUrn,
      Object manifestSynthResourceUrn,
      Map<String, Object> registrySummary,
      Map<String, Object> imageBuildSummary,
      Map<String, Object> manifestSynthSummary,
      Object systemdRuntimeStatusSummary) {}

  /** Manages resource creation for both Pulumi and standalone modes using functional pipelines. */
  private final class ResourceManager {

    ResourceCreationResult createResources(
        BootstrapConfig config,
        ControlplanePolicy policy,
        boolean readinessEnabled,
        Consumer<String> readinessLogger,
        IncusResourceBootstrap.BootstrapResult bootstrapResult,
        Map<String, Object> systemdAdapterLaunchSummary,
        boolean pulumiMode) {

      final BootstrapPipeline pipeline =
          new BootstrapPipeline(
              config,
              policy,
              readinessEnabled,
              readinessLogger,
              bootstrapResult,
              systemdAdapterLaunchSummary);

      if (pulumiMode) {
        final BootstrapPipeline.PulumiResources resources = pipeline.createPulumiResources();
        return new ResourceCreationResult(
            resources.readinessOutput(),
            resources.clusterReadinessResourceUrn(),
            resources.systemdAdapterResourceUrn(),
            resources.registryResourceUrn(),
            resources.imageBuildResourceUrn(),
            resources.manifestSynthResourceUrn(),
            resources.registrySummary(),
            resources.imageBuildSummary(),
            resources.manifestSynthSummary(),
            resources.systemdRuntimeStatusSummary());
      } else {
        return pipeline.createStandaloneResources().toResourceCreationResult();
      }
    }
  }

  /** Builds the final output map from all bootstrap results. */
  private final class OutputBuilder {

    Map<String, Object> buildOutputs(
        BootstrapConfig config,
        ControlplanePolicy policy,
        IncusResourceBootstrap.BootstrapResult bootstrapResult,
        BboxReconciliationOrchestrator.ReconciliationResult bboxResult,
        Map<String, Object> systemdAdapterLaunchSummary,
        ResourceCreationResult resourceResult) {

      final Map<String, Object> outputs = new LinkedHashMap<>();

      // Core cluster information
      outputs.put("managementClusterName", config.clusterName());
      outputs.put("apiEndpoint", config.apiEndpoint().toString());
      outputs.put("kubeconfigRef", config.kubeconfigRef().toString());

      // Seed instance information
      outputs.put("seedNodeId", bootstrapResult.seedNodeId());
      outputs.put("seedInstanceUrn", bootstrapResult.instanceUrn());
      outputs.put("seedProviderUrn", bootstrapResult.providerUrn());
      outputs.put("seedProvisioningChecksum", bootstrapResult.provisioningChecksum());
      outputs.put("seedImageBuildChecksum", bootstrapResult.imageBuildChecksum());
      outputs.put("seedImageFingerprint", bootstrapResult.imageFingerprint());
      outputs.put("seedInstanceStatus", bootstrapResult.instanceStatus());
      outputs.put("hostSourceDirRelative", bootstrapResult.hostSourceDirRelative());

      // Configuration
      outputs.put("incusProject", config.incusProject());
      outputs.put("imageAlias", config.imageAlias());
      outputs.put("seedLanBridgeParent", config.lanBridgeParent());
      outputs.putAll(policy.toOutputMap());

      // Readiness outputs (functional mapping)
      outputs.putAll(ReadinessOutputMapper.mapToOutputs(resourceResult.readinessOutput()));

      // Resource URNs
      outputs.put("clusterReadinessResourceUrn", resourceResult.clusterReadinessResourceUrn());
      outputs.put("systemdAdapterResourceUrn", resourceResult.systemdAdapterResourceUrn());
      outputs.put("registryResourceUrn", resourceResult.registryResourceUrn());
      outputs.put("seedImageBuildResourceUrn", resourceResult.imageBuildResourceUrn());
      outputs.put("seedManifestSynthResourceUrn", resourceResult.manifestSynthResourceUrn());
      outputs.put("bboxReservationsResourceUrn", bboxResult.resourceUrn());

      // Summaries
      outputs.put("bboxReservationsSummary", bboxResult.summaryMap());
      outputs.put("registrySummary", resourceResult.registrySummary());
      outputs.put("systemdProvisioningSummary", bootstrapResult.systemdProvisioningSummary());
      outputs.put("systemdAdapterLaunchSummary", systemdAdapterLaunchSummary);
      outputs.put("systemdRuntimeStatusSummary", resourceResult.systemdRuntimeStatusSummary());
      outputs.put("seedImageBuildSummary", resourceResult.imageBuildSummary());
      outputs.put("seedManifestSynthSummary", resourceResult.manifestSynthSummary());

      return outputs;
    }
  }
}
