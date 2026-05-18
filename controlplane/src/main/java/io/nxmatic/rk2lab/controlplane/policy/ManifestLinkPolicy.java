package io.nxmatic.rk2lab.controlplane.policy;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainIds;
import io.nxmatic.rk2lab.manifests.api.ManifestDomainPolicy;
import java.util.LinkedHashMap;
import java.util.Map;

/** Policy controlling which serialized host manifest layers are linked into live RKE2 manifests. */
public record ManifestLinkPolicy(ManifestDomainPolicy domains) {

  public ManifestLinkPolicy {
    domains = new ManifestDomainPolicy(new LinkedHashMap<>(domains.asMap()));
  }

  public static ManifestLinkPolicy stageA(
      boolean highAvailabilityEnabled,
      boolean networkingEnabled,
      boolean replicationEnabled,
      boolean storageEnabled,
      boolean meshEnabled) {
    return new ManifestLinkPolicy(
        ManifestDomainPolicy.stageALinkPolicy(
            highAvailabilityEnabled,
            networkingEnabled,
            replicationEnabled,
            storageEnabled,
            meshEnabled));
  }

  public boolean highAvailabilityEnabled() {
    return domains.isEnabled(ManifestDomainIds.HIGH_AVAILABILITY);
  }

  public boolean networkingEnabled() {
    return domains.isEnabled(ManifestDomainIds.NETWORKING);
  }

  public boolean replicationEnabled() {
    return domains.isEnabled(ManifestDomainIds.REPLICATION);
  }

  public boolean storageEnabled() {
    return domains.isEnabled(ManifestDomainIds.STORAGE);
  }

  public boolean meshEnabled() {
    return domains.isEnabled(ManifestDomainIds.MESH);
  }

  public Map<String, String> toEnvMap() {
    return Map.of(
        "RKE2LAB_POLICY_LINK_HIGH_AVAILABILITY_ENABLED",
        Boolean.toString(highAvailabilityEnabled()),
        "RKE2LAB_POLICY_LINK_NETWORKING_ENABLED",
        Boolean.toString(networkingEnabled()),
        "RKE2LAB_POLICY_LINK_REPLICATION_ENABLED",
        Boolean.toString(replicationEnabled()),
        "RKE2LAB_POLICY_LINK_STORAGE_ENABLED",
        Boolean.toString(storageEnabled()),
        "RKE2LAB_POLICY_LINK_MESH_ENABLED",
        Boolean.toString(meshEnabled()));
  }

  public Map<String, Object> toOutputMap() {
    return Map.of(
        "policyLinkHighAvailabilityEnabled", highAvailabilityEnabled(),
        "policyLinkNetworkingEnabled", networkingEnabled(),
        "policyLinkReplicationEnabled", replicationEnabled(),
        "policyLinkStorageEnabled", storageEnabled(),
        "policyLinkMeshEnabled", meshEnabled());
  }
}
