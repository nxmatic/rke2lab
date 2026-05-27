package io.nxmatic.rk2lab.controlplane.pipeline;

import io.nxmatic.rk2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rk2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rk2lab.controlplane.readiness.ReadinessOutputMapper;
import io.nxmatic.rk2lab.controlplane.resources.ResourceManager;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the final bootstrap output map from all pipeline results. */
public final class OutputBuilder {

  public Map<String, Object> buildOutputs(
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
    outputs.put("seedImageFingerprint", bootstrapResult.imageFingerprint());
    outputs.put("seedInstanceStatus", bootstrapResult.instanceStatus());

    // Deployment metadata. Intentionally omit `timestamp` here -- it would change every run and
    // produce phantom output diffs on no-op pulumi up. Pulumi's own state already tracks when the
    // stack was last updated.
    outputs.put(
        "seedDeploymentMetadata",
        Map.of(
            "git",
            Map.of(
                "branch", bootstrapResult.deployment().git().branch(),
                "commitSha", bootstrapResult.deployment().git().commitSha())));

    // Provisioning metadata
    outputs.put(
        "seedProvisioningMetadata",
        Map.of(
            "slices",
            Map.of(
                "static", bootstrapResult.provisioning().slices().staticSlices(),
                "hotReload", bootstrapResult.provisioning().slices().hotReloadSlices(),
                "all", bootstrapResult.provisioning().slices().all()),
            "paths",
            Map.of(
                "hostSourceDirRelative",
                bootstrapResult.provisioning().paths().hostSourceDirRelative())));

    // Build metadata
    outputs.put(
        "seedBuildMetadata",
        Map.of(
            "image",
            Map.of("checksum", bootstrapResult.build().image().checksum()),
            "manifests",
            bootstrapResult.build().manifests().summary()));

    // Runtime metadata
    outputs.put(
        "seedRuntimeMetadata",
        Map.of(
            "environment", bootstrapResult.runtime().environment().summary(),
            "systemd", bootstrapResult.runtime().systemd().summary()));

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
    outputs.put("systemdAdapterLaunchSummary", systemdAdapterLaunchSummary);
    outputs.put("systemdRuntimeStatusSummary", resourceResult.systemdRuntimeStatusSummary());
    outputs.put("seedImageBuildSummary", resourceResult.imageBuildSummary());
    outputs.put("seedManifestSynthSummary", resourceResult.manifestSynthSummary());

    return outputs;
  }
}
