package io.nxmatic.rk2lab.controlplane.policy;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.api.ManifestDomainPolicy;
import java.util.LinkedHashMap;
import java.util.Map;

/** Policy controlling which serialized host manifest layers are linked into live RKE2 manifests. */
public record ManifestLinkPolicy(ManifestDomainPolicy domains) {

  private static final ManifestDomainCatalog MANIFEST_DOMAIN_CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

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
        ManifestDomainPolicy.builder()
            .domainCatalog(MANIFEST_DOMAIN_CATALOG)
            .stageALinkPolicy(
                highAvailabilityEnabled,
                networkingEnabled,
                replicationEnabled,
                storageEnabled,
                meshEnabled)
            .build());
  }

  public boolean highAvailabilityEnabled() {
    return domains.isEnabled(MANIFEST_DOMAIN_CATALOG.highAvailability());
  }

  public boolean networkingEnabled() {
    return domains.isEnabled(MANIFEST_DOMAIN_CATALOG.networking());
  }

  public boolean replicationEnabled() {
    return domains.isEnabled(MANIFEST_DOMAIN_CATALOG.replication());
  }

  public boolean storageEnabled() {
    return domains.isEnabled(MANIFEST_DOMAIN_CATALOG.storage());
  }

  public boolean meshEnabled() {
    return domains.isEnabled(MANIFEST_DOMAIN_CATALOG.mesh());
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
