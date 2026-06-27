package io.nxmatic.rke2lab.controlplane.resources;

import com.pulumi.deployment.Deployment;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessContact;
import io.nxmatic.rke2lab.controlplane.bdd.LiveClusterReadinessProbe;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rke2lab.controlplane.pipeline.stages.ClusterReadinessStage;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterReadinessResource;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterRuntimeStatusSnapshot;
import io.nxmatic.rke2lab.controlplane.systemd.SystemdAdapterResource;
import io.nxmatic.rke2lab.doctor.port.ConsultationLog;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.doctor.records.Checkpoint;
import io.nxmatic.rke2lab.doctor.records.ConsultationReport;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Functional pipeline for bootstrap resource creation.
 *
 * <p>Organizes resource creation into composable stages: systemd adapter, readiness verification,
 * registry provisioning, image builds, and manifest synthesis.
 */
final class ResourceCreationPipeline {

  private final BootstrapConfig config;
  private final ControlplanePolicy policy;
  private final boolean readinessEnabled;
  private final Consumer<String> readinessLogger;
  private final ReportModel runbook;
  private final ConsultationLog consultations;
  private final ConsultingService doctor;
  private final SeedSystemdAdapterRuntimeStatusSnapshot systemdRuntimeStatus;
  private final ClusterReadinessContact clusterReadinessContact;
  private final IncusResourceBootstrap.BootstrapResult bootstrapResult;
  private final Map<String, Object> systemdAdapterLaunchSummary;

  ResourceCreationPipeline(
      BootstrapConfig config,
      ControlplanePolicy policy,
      boolean readinessEnabled,
      Consumer<String> readinessLogger,
      ReportModel runbook,
      ConsultationLog consultations,
      ConsultingService doctor,
      SeedSystemdAdapterRuntimeStatusSnapshot systemdRuntimeStatus,
      ClusterReadinessContact clusterReadinessContact,
      IncusResourceBootstrap.BootstrapResult bootstrapResult,
      Map<String, Object> systemdAdapterLaunchSummary) {
    this.config = config;
    this.policy = policy;
    this.readinessEnabled = readinessEnabled;
    this.readinessLogger = readinessLogger;
    this.runbook = runbook;
    this.consultations = consultations;
    this.doctor = doctor;
    this.systemdRuntimeStatus = systemdRuntimeStatus;
    this.clusterReadinessContact = clusterReadinessContact;
    this.bootstrapResult = bootstrapResult;
    this.systemdAdapterLaunchSummary = systemdAdapterLaunchSummary;
  }

  /**
   * Play the cluster-readiness checkpoint as a BDD scenario, eager — the result is the same whether
   * Pulumi-managed or standalone (the resource just mirrors it). Records into the shared runbook
   * and consults the doctor on failure.
   */
  private ClusterBootstrapReadinessVerifier.VerificationResult playClusterReadiness(
      boolean pulumiMode) {
    final ClusterBootstrapReadinessVerifier.VerificationResult[] holder =
        new ClusterBootstrapReadinessVerifier.VerificationResult[1];
    new ClusterReadinessStage(
            config,
            policy,
            readinessEnabled,
            pulumiMode,
            readinessLogger,
            runbook,
            consultations,
            doctor,
            new LiveClusterReadinessProbe(
                policy, systemdRuntimeStatus, clusterReadinessContact, readinessLogger),
            systemdAdapterLaunchSummary,
            result -> holder[0] = result)
        .launch();
    return holder[0];
  }

  /**
   * Joins the shared {@link ConsultationLog} on the checkpoint's slug — the report exists only when
   * that checkpoint raised a symptom (reactive consultation), so a healthy node yields empty.
   */
  private Optional<ConsultationReport> consultationFor(Checkpoint checkpoint) {
    if (consultations == null) {
      return Optional.empty();
    }
    return consultations.consultations().stream()
        .filter(r -> r.checkpointId().equals(checkpoint.slug()))
        .findFirst();
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
              Checkpoint.SYSTEMD_ADAPTER.resourceName(),
              systemdAdapterLaunchSummary,
              consultationFor(Checkpoint.SYSTEMD_ADAPTER),
              bootstrapResult.deployment().timestamp(),
              bootstrapResult.readinessDependency());
      return this;
    }

    PulumiResourceBuilder withReadiness() {
      // The checkpoint is played eagerly as a BDD scenario (records into the runbook, consults the
      // doctor); the resource is a thin graph mirror of the result + the dependsOn edge. The edge
      // points at the systemd-adapter resource (the real business dependency), so the persisted
      // Pulumi graph matches the runbook's BDD nesting. Play before the join so any raised report
      // is already in the shared log when consultationFor reads it.
      final ClusterBootstrapReadinessVerifier.VerificationResult result =
          playClusterReadiness(true);
      this.readiness =
          new ClusterReadinessResource(
              Checkpoint.CLUSTER_READINESS.resourceName(),
              result,
              consultationFor(Checkpoint.CLUSTER_READINESS),
              this.systemdAdapter);
      return this;
    }

    PulumiResourceBuilder withRegistry() {
      this.registry =
          new BootstrapRegistryResource(
              "seed-master-registry",
              config,
              bootstrapResult.provisioning(),
              bootstrapResult.runtime(),
              bootstrapResult.readinessDependency());
      return this;
    }

    PulumiResourceBuilder withImageBuild() {
      this.imageBuild =
          new SeedImageBuildResource(
              "seed-image-build",
              config,
              bootstrapResult.build().image().checksum(),
              bootstrapResult.imageFingerprint(),
              bootstrapResult.readinessDependency());
      return this;
    }

    PulumiResourceBuilder withManifestSynth() {
      this.manifestSynth =
          new SeedManifestSynthResource(
              "seed-manifest-synth",
              bootstrapResult.build().manifests().summary(),
              bootstrapResult.readinessDependency());
      return this;
    }

    PulumiResourceBuilder withSystemdRuntimeStatus() {
      this.systemdRuntimeStatus =
          Deployment.getInstance().isDryRun()
              ? SeedSystemdAdapterRuntimeStatusSnapshot.deferredPreview(config)
              : ResourceCreationPipeline.this.systemdRuntimeStatus.snapshot(
                  config, readinessLogger);
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
      // Same eager BDD checkpoint as the Pulumi path; standalone keeps the plain VerificationResult
      // (no Pulumi resource wrapping). Unified by the pulumiMode flag, as SystemdAdapterStage is.
      this.readinessOutput = playClusterReadiness(false);
      return this;
    }

    StandaloneResourceBuilder withRegistry() {
      this.registrySummary =
          Map.of(
              "targetChecksums",
              bootstrapResult.provisioning().targets().all(),
              "hostSourceDirRelative",
              bootstrapResult.provisioning().paths().hostSourceDirRelative(),
              "localWorktreePath",
              config.localWorktreePath().toString(),
              "layerEnvRegistry",
              bootstrapResult.runtime().environment().summary(),
              "systemdProvisioning",
              bootstrapResult.runtime().systemd().summary());
      return this;
    }

    StandaloneResourceBuilder withImageBuild() {
      this.imageBuildSummary =
          Map.of(
              "checksum",
              bootstrapResult.build().image().checksum(),
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
          bootstrapResult.build().manifests().summary() == null
              ? Map.of()
              : Map.copyOf(bootstrapResult.build().manifests().summary());
      return this;
    }

    StandaloneResourceBuilder withSystemdRuntimeStatus() {
      this.systemdRuntimeStatus =
          ResourceCreationPipeline.this.systemdRuntimeStatus.snapshotStandalone(config);
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
