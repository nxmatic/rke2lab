package io.nxmatic.rk2lab.controlplane;

import com.pulumi.Config;
import com.pulumi.Pulumi;
import com.pulumi.core.Output;
import com.pulumi.deployment.Deployment;
import io.nxmatic.rk2lab.controlplane.bbox.BboxReconcilerComponent;
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

  private Main() {}

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
            final boolean readinessEnabled = ConfigResolver.resolveReadinessEnabled(config);
            final boolean cleanWorktreeRequired =
                ConfigResolver.resolveCleanWorktreeRequired(config);
            final boolean bboxFailOnError = ConfigResolver.resolveBboxFailOnError(config);
            final Consumer<String> readinessLogger = message -> SeedLog.info("readiness", message);
            final BootstrapOrchestrator orchestrator = new BootstrapOrchestrator();
            final Map<String, Object> outputs =
                orchestrator.bootstrapAndCollectOutputs(
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

  private static void runStandalone() {
    final BootstrapConfig bootstrapConfig = new BootstrapConfig.Builder().build();
    final ControlplanePolicy controlplanePolicy = ControlplanePolicy.defaults();
    final Consumer<String> readinessLogger = message -> SeedLog.info("readiness", message);
    final BootstrapOrchestrator orchestrator = new BootstrapOrchestrator();
    final Map<String, Object> outputs =
        orchestrator.bootstrapAndCollectOutputs(
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

  /** Orchestrates the bootstrap process and manages component interactions. */
  static final class BootstrapOrchestrator {

    Map<String, Object> bootstrapAndCollectOutputs(
        BootstrapConfig config,
        ControlplanePolicy policy,
        boolean readinessEnabled,
        boolean cleanWorktreeRequired,
        boolean bboxFailOnError,
        Consumer<String> readinessLogger) {
      EntryGatePolicyEnforcer.enforceAll(config.localWorktreePath(), cleanWorktreeRequired);
      RuntimeCommandPreflight.enforceRequiredCommands(
          java.util.List.of("ssh", "kubectl"), readinessLogger);
      RuntimeCommandPreflight.enforceRemoteCommandAvailable(
          config.imageBuilderHost(), "incus", readinessLogger);

      final Object bboxResourceUrn;
      final Map<String, Object> bboxSummaryMap;
      if (isPulumiEngineAvailable()) {
        final BboxReconcilerComponent.ReconcileResult bboxResult =
            BboxReconcilerComponent.reconcileForPulumi(
                config.localWorktreePath(), bboxFailOnError, null);
        bboxResourceUrn = bboxResult.resourceUrn();
        bboxSummaryMap = bboxResult.summaryMap();
      } else {
        bboxResourceUrn = "";
        bboxSummaryMap =
            BboxReconcilerComponent.reconcileStandalone(
                config.localWorktreePath(), bboxFailOnError);
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
                "checksum",
                bootstrapResult.imageBuildChecksum(),
                "imageAlias",
                config.imageAlias(),
                "imageFingerprint",
                bootstrapResult.imageFingerprint(),
                "incusProject",
                config.incusProject());
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
      return outputs;
    }
  }
}
