package io.nxmatic.rk2lab.controlplane;

import io.nxmatic.rk2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rk2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rk2lab.controlplane.readiness.ReadinessOutputMapper;
import io.nxmatic.rk2lab.controlplane.resources.ResourceManager;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the final bootstrap output map from all pipeline results. */
final class OutputBuilder {

  Map<String, Object> buildOutputs(
      BootstrapConfig config,
      ControlplanePolicy policy,
      IncusResourceBootstrap.BootstrapResult bootstrapResult,
      BboxReconciliationOrchestrator.ReconciliationResult bboxResult,
      Map<String, Object> systemdAdapterLaunchSummary,
      ResourceManager.ResourceCreationResult resourceResult) {

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
