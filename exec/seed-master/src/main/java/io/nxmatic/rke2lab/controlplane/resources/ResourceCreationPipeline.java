package io.nxmatic.rke2lab.controlplane.resources;

import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterReadinessResource;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterRuntimeStatusSnapshot;
import io.nxmatic.rke2lab.controlplane.systemd.SystemdAdapterResource;
import io.nxmatic.rke2lab.doctor.port.ConsultationLog;
import io.nxmatic.rke2lab.pulumi.edge.LiveGate;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Checkpoint;
import io.nxmatic.rke2lab.world.gateway.port.Consultation;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Functional pipeline for bootstrap resource creation — PURE resource assembly. It receives a
 * cluster-readiness {@link ClusterBootstrapReadinessVerifier.VerificationResult} already PLAYED (by
 * {@code ClusterReadinessStage} in the composite scenario, or by the fluent {@code
 * ResourceManager.createResources} before it built this pipeline) and mirrors it into a resource;
 * it never plays readiness itself. That is the full-BDD shape: the checkpoint is a scenario phase,
 * this is only the registry/image/manifest assembly.
 */
final class ResourceCreationPipeline {

  private final BootstrapConfig config;
  private final Consumer<String> readinessLogger;
  private final Optional<ConsultationLog> consultations;
  private final SeedSystemdAdapterRuntimeStatusSnapshot systemdRuntimeStatus;
  private final IncusResourceBootstrap.BootstrapResult bootstrapResult;
  private final Map<String, Object> systemdAdapterLaunchSummary;
  private final LiveGate gate;
  private final ClusterBootstrapReadinessVerifier.VerificationResult readiness;
  private final DocumentCodec codec = new DocumentCodec();

  ResourceCreationPipeline(
      BootstrapConfig config,
      Consumer<String> readinessLogger,
      Optional<ConsultationLog> consultations,
      SeedSystemdAdapterRuntimeStatusSnapshot systemdRuntimeStatus,
      IncusResourceBootstrap.BootstrapResult bootstrapResult,
      Map<String, Object> systemdAdapterLaunchSummary,
      LiveGate gate,
      ClusterBootstrapReadinessVerifier.VerificationResult readiness) {
    this.config = config;
    this.readinessLogger = readinessLogger;
    this.consultations = consultations;
    this.systemdRuntimeStatus = systemdRuntimeStatus;
    this.bootstrapResult = bootstrapResult;
    this.systemdAdapterLaunchSummary = systemdAdapterLaunchSummary;
    this.gate = gate;
    this.readiness = readiness;
  }

  /**
   * Joins the shared {@link ConsultationLog} on the checkpoint's slug — the consultation Document
   * exists only when that checkpoint raised a symptom (reactive consultation), so a healthy node
   * yields empty. The slug is read from each Document's opaque payload; the host holds no doctor
   * type, it only matches the scenario id it wrote.
   */
  private Optional<Document> consultationFor(Checkpoint checkpoint) {
    return consultations.stream()
        .flatMap(log -> log.consultations().stream())
        .filter(
            document ->
                checkpoint.slug().equals(codec.decode(document, Consultation.class).scenarioId()))
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
    private @MonotonicNonNull SystemdAdapterResource systemdAdapter;
    private @MonotonicNonNull ClusterReadinessResource readiness;
    private @MonotonicNonNull BootstrapRegistryResource registry;
    private @MonotonicNonNull SeedImageBuildResource imageBuild;
    private @MonotonicNonNull SeedManifestSynthResource manifestSynth;
    private @MonotonicNonNull Object systemdRuntimeStatus;

    PulumiResourceBuilder withSystemdAdapter() {
      this.systemdAdapter =
          new SystemdAdapterResource(
              Checkpoint.SYSTEMD_ADAPTER.resourceName(),
              systemdAdapterLaunchSummary,
              consultationFor(Checkpoint.SYSTEMD_ADAPTER),
              bootstrapResult.readinessDependency());
      return this;
    }

    PulumiResourceBuilder withReadiness() {
      // The checkpoint was already played (by a stage, or by the fluent createResources); the
      // resource is a thin graph mirror of the result + the dependsOn edge. The edge points at the
      // systemd-adapter resource (the real business dependency), so the persisted Pulumi graph
      // matches the runbook's BDD nesting. The consultation, if any, is already in the shared log.
      this.readiness =
          new ClusterReadinessResource(
              Checkpoint.CLUSTER_READINESS.resourceName(),
              ResourceCreationPipeline.this.readiness,
              consultationFor(Checkpoint.CLUSTER_READINESS),
              Objects.requireNonNull(
                  this.systemdAdapter, "systemdAdapter (call withSystemdAdapter first)"));
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
              bootstrapResult.build().requireImage().checksum(),
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
          gate.isOpen()
              ? ResourceCreationPipeline.this.systemdRuntimeStatus.snapshot(config, readinessLogger)
              : SeedSystemdAdapterRuntimeStatusSnapshot.deferredPreview(config);
      return this;
    }

    PulumiResources build() {
      final ClusterReadinessResource readiness =
          Objects.requireNonNull(this.readiness, "readiness");
      final SystemdAdapterResource systemdAdapter =
          Objects.requireNonNull(this.systemdAdapter, "systemdAdapter");
      final BootstrapRegistryResource registry = Objects.requireNonNull(this.registry, "registry");
      final SeedImageBuildResource imageBuild =
          Objects.requireNonNull(this.imageBuild, "imageBuild");
      final SeedManifestSynthResource manifestSynth =
          Objects.requireNonNull(this.manifestSynth, "manifestSynth");
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
          Objects.requireNonNull(systemdRuntimeStatus, "systemdRuntimeStatus"));
    }
  }

  /** Builder for standalone resources. */
  private final class StandaloneResourceBuilder {
    private @MonotonicNonNull Object readinessOutput;
    private @MonotonicNonNull Map<String, Object> registrySummary;
    private @MonotonicNonNull Map<String, Object> imageBuildSummary;
    private @MonotonicNonNull Map<String, Object> manifestSynthSummary;
    private @MonotonicNonNull Object systemdRuntimeStatus;

    StandaloneResourceBuilder withReadiness() {
      // The readiness result was already played upstream; standalone keeps the plain
      // VerificationResult (no Pulumi resource wrapping), unified by the pulumiMode flag.
      this.readinessOutput = ResourceCreationPipeline.this.readiness;
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
              bootstrapResult.build().requireImage().checksum(),
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
          Objects.requireNonNull(readinessOutput, "readinessOutput"),
          Objects.requireNonNull(registrySummary, "registrySummary"),
          Objects.requireNonNull(imageBuildSummary, "imageBuildSummary"),
          Objects.requireNonNull(manifestSynthSummary, "manifestSynthSummary"),
          Objects.requireNonNull(systemdRuntimeStatus, "systemdRuntimeStatus"));
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
