package io.nxmatic.rk2lab.controlplane.resources;

import com.pulumi.deployment.Deployment;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rk2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rk2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier;
import io.nxmatic.rk2lab.controlplane.readiness.ClusterReadinessResource;
import io.nxmatic.rk2lab.controlplane.systemd.SeedSystemdAdapterRuntimeStatusSnapshot;
import io.nxmatic.rk2lab.controlplane.systemd.SystemdAdapterResource;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Functional pipeline for bootstrap resource creation.
 *
 * <p>Organizes resource creation into composable stages: systemd adapter, readiness verification,
 * registry provisioning, image builds, and manifest synthesis.
 */
final class BootstrapPipeline {

  private final BootstrapConfig config;
  private final ControlplanePolicy policy;
  private final boolean readinessEnabled;
  private final Consumer<String> readinessLogger;
  private final IncusResourceBootstrap.BootstrapResult bootstrapResult;
  private final Map<String, Object> systemdAdapterLaunchSummary;

  BootstrapPipeline(
      BootstrapConfig config,
      ControlplanePolicy policy,
      boolean readinessEnabled,
      Consumer<String> readinessLogger,
      IncusResourceBootstrap.BootstrapResult bootstrapResult,
      Map<String, Object> systemdAdapterLaunchSummary) {
    this.config = config;
    this.policy = policy;
    this.readinessEnabled = readinessEnabled;
    this.readinessLogger = readinessLogger;
    this.bootstrapResult = bootstrapResult;
    this.systemdAdapterLaunchSummary = systemdAdapterLaunchSummary;
  }

  /** Executes the Pulumi resource creation pipeline. */
  PulumiResources createPulumiResources() {
    return new PulumiResourceBuilder()
        .withSystemdAdapter()
        .withReadiness()
        .withRegistry()
        .withImageBuild()
        .withManifestSynth()
        .withSystemdRuntimeStatus()
        .build();
  }

  /** Executes the standalone (no Pulumi) resource creation pipeline. */
  StandaloneResources createStandaloneResources() {
    return new StandaloneResourceBuilder()
        .withReadiness()
        .withRegistry()
        .withImageBuild()
        .withManifestSynth()
        .withSystemdRuntimeStatus()
        .build();
  }

  /** Builder for Pulumi-managed resources. */
  private final class PulumiResourceBuilder {
    private SystemdAdapterResource systemdAdapter;
    private ClusterReadinessResource readiness;
    private BootstrapRegistryResource registry;
    private SeedImageBuildResource imageBuild;
    private SeedManifestSynthResource manifestSynth;
    private Object systemdRuntimeStatus;

    PulumiResourceBuilder withSystemdAdapter() {
      this.systemdAdapter =
          new SystemdAdapterResource(
              "seed-systemd-adapter",
              systemdAdapterLaunchSummary,
              bootstrapResult.readinessDependency());
      return this;
    }

    PulumiResourceBuilder withReadiness() {
      this.readiness =
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
      return this;
    }

    PulumiResourceBuilder withRegistry() {
      this.registry =
          new BootstrapRegistryResource(
              "seed-bootstrap-registry",
              config,
              bootstrapResult.provisioningChecksum(),
              bootstrapResult.hostSourceDirRelative(),
              bootstrapResult.layerEnvRegistrySummary(),
              bootstrapResult.systemdProvisioningSummary(),
              bootstrapResult.readinessDependency());
      return this;
    }

    PulumiResourceBuilder withImageBuild() {
      this.imageBuild =
          new SeedImageBuildResource(
              "seed-image-build",
              config,
              bootstrapResult.imageBuildChecksum(),
              bootstrapResult.imageFingerprint(),
              bootstrapResult.readinessDependency());
      return this;
    }

    PulumiResourceBuilder withManifestSynth() {
      this.manifestSynth =
          new SeedManifestSynthResource(
              "seed-manifest-synth",
              bootstrapResult.manifestSynthSummary(),
              bootstrapResult.readinessDependency());
      return this;
    }

    PulumiResourceBuilder withSystemdRuntimeStatus() {
      this.systemdRuntimeStatus =
          Deployment.getInstance().isDryRun()
              ? SeedSystemdAdapterRuntimeStatusSnapshot.deferredPreview(config)
              : SeedSystemdAdapterRuntimeStatusSnapshot.snapshot(config, readinessLogger);
      return this;
    }

    PulumiResources build() {
      return new PulumiResources(
          readiness.verificationResult(),
          readiness.urn(),
          systemdAdapter.urn(),
          registry.urn(),
          imageBuild.urn(),
          manifestSynth.urn(),
          registry.summary(),
          imageBuild.summary(),
          manifestSynth.summary(),
          systemdRuntimeStatus);
    }
  }

  /** Builder for standalone resources. */
  private final class StandaloneResourceBuilder {
    private Object readinessOutput;
    private Map<String, Object> registrySummary;
    private Map<String, Object> imageBuildSummary;
    private Map<String, Object> manifestSynthSummary;
    private Object systemdRuntimeStatus;

    StandaloneResourceBuilder withReadiness() {
      this.readinessOutput =
          readinessEnabled
              ? ClusterBootstrapReadinessVerifier.verify(config, policy, readinessLogger)
              : ClusterBootstrapReadinessVerifier.skipped(policy, readinessLogger);
      return this;
    }

    StandaloneResourceBuilder withRegistry() {
      this.registrySummary =
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
      return this;
    }

    StandaloneResourceBuilder withImageBuild() {
      this.imageBuildSummary =
          Map.of(
              "checksum",
              bootstrapResult.imageBuildChecksum(),
              "imageAlias",
              config.imageAlias(),
              "imageFingerprint",
              bootstrapResult.imageFingerprint(),
              "incusProject",
              config.incusProject());
      return this;
    }

    StandaloneResourceBuilder withManifestSynth() {
      this.manifestSynthSummary =
          bootstrapResult.manifestSynthSummary() == null
              ? Map.of()
              : Map.copyOf(bootstrapResult.manifestSynthSummary());
      return this;
    }

    StandaloneResourceBuilder withSystemdRuntimeStatus() {
      this.systemdRuntimeStatus =
          SeedSystemdAdapterRuntimeStatusSnapshot.snapshotStandalone(config);
      return this;
    }

    StandaloneResources build() {
      return new StandaloneResources(
          readinessOutput,
          registrySummary,
          imageBuildSummary,
          manifestSynthSummary,
          systemdRuntimeStatus);
    }
  }

  /** Result of Pulumi resource creation pipeline. */
  record PulumiResources(
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

  /** Result of standalone resource creation pipeline. */
  record StandaloneResources(
      Object readinessOutput,
      Map<String, Object> registrySummary,
      Map<String, Object> imageBuildSummary,
      Map<String, Object> manifestSynthSummary,
      Object systemdRuntimeStatusSummary) {

    /** Converts to full ResourceCreationResult with empty URNs. */
    ResourceManager.ResourceCreationResult toResourceCreationResult() {
      return new ResourceManager.ResourceCreationResult(
          readinessOutput,
          "",
          "",
          "",
          "",
          "",
          registrySummary,
          imageBuildSummary,
          manifestSynthSummary,
          systemdRuntimeStatusSummary);
    }
  }
}
