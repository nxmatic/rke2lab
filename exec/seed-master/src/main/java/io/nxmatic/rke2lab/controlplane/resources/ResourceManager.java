package io.nxmatic.rke2lab.controlplane.resources;

import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessContact;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterRuntimeStatusSnapshot;
import io.nxmatic.rke2lab.doctor.port.ConsultationLog;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Manages bootstrap resource creation, delegating to pipelines based on execution mode. */
public final class ResourceManager {

  public ResourceCreationResult createResources(
      BootstrapConfig config,
      ControlplanePolicy policy,
      boolean readinessEnabled,
      Consumer<String> readinessLogger,
      Optional<ReportModel> runbook,
      Optional<ConsultationLog> consultations,
      ConsultingService doctor,
      SeedSystemdAdapterRuntimeStatusSnapshot systemdRuntimeStatus,
      ClusterReadinessContact clusterReadinessContact,
      IncusResourceBootstrap.BootstrapResult bootstrapResult,
      Map<String, Object> systemdAdapterLaunchSummary,
      boolean pulumiMode) {

    final ResourceCreationPipeline pipeline =
        new ResourceCreationPipeline(
            config,
            policy,
            readinessEnabled,
            readinessLogger,
            runbook,
            consultations,
            doctor,
            systemdRuntimeStatus,
            clusterReadinessContact,
            bootstrapResult,
            systemdAdapterLaunchSummary);

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
