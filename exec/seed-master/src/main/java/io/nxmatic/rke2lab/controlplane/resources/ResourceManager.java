package io.nxmatic.rke2lab.controlplane.resources;

import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterRuntimeStatusSnapshot;
import io.nxmatic.rke2lab.doctor.port.ConsultationLog;
import io.nxmatic.rke2lab.pulumi.edge.LiveGate;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Manages bootstrap resource creation, delegating to pipelines based on execution mode. */
public final class ResourceManager {

  /**
   * Assemble the bootstrap resources from a cluster-readiness result a stage ALREADY played (the
   * composite scenario). The pipeline is pure — it mirrors the result, it never plays readiness.
   */
  public ResourceCreationResult createResources(
      BootstrapConfig config,
      Consumer<String> readinessLogger,
      Optional<ConsultationLog> consultations,
      SeedSystemdAdapterRuntimeStatusSnapshot systemdRuntimeStatus,
      IncusResourceBootstrap.BootstrapResult bootstrapResult,
      Map<String, Object> systemdAdapterLaunchSummary,
      boolean pulumiMode,
      LiveGate gate,
      ClusterBootstrapReadinessVerifier.VerificationResult readiness) {
    return assemble(
        config,
        readinessLogger,
        consultations,
        systemdRuntimeStatus,
        bootstrapResult,
        systemdAdapterLaunchSummary,
        pulumiMode,
        gate,
        readiness);
  }

  private ResourceCreationResult assemble(
      BootstrapConfig config,
      Consumer<String> readinessLogger,
      Optional<ConsultationLog> consultations,
      SeedSystemdAdapterRuntimeStatusSnapshot systemdRuntimeStatus,
      IncusResourceBootstrap.BootstrapResult bootstrapResult,
      Map<String, Object> systemdAdapterLaunchSummary,
      boolean pulumiMode,
      LiveGate gate,
      ClusterBootstrapReadinessVerifier.VerificationResult readiness) {

    final ResourceCreationPipeline pipeline =
        new ResourceCreationPipeline(
            config,
            readinessLogger,
            consultations,
            systemdRuntimeStatus,
            bootstrapResult,
            systemdAdapterLaunchSummary,
            gate,
            readiness);

    if (pulumiMode) {
      final ResourceCreationPipeline.PulumiResources resources = pipeline.createPulumiResources();
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

  /** Result of resource creation containing all created resources and summaries. */
  public record ResourceCreationResult(
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
}
