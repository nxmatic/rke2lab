package io.nxmatic.rk2lab.controlplane.policy;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.api.ManifestDomainPolicy;
import java.util.LinkedHashMap;
import java.util.Map;

/** Policy controlling which serialized host manifest layers are linked into live RKE2 manifests. */
public record ManifestLinkPolicy(ManifestDomainPolicy domains, DebugPolicy debug) {

  public record DebugPolicy(java.util.function.Predicate<String> domainDebug) {
    public static DebugPolicy none() {
      return new DebugPolicy(domain -> false);
    }

    public boolean isEnabled(String domain) {
      return domainDebug.test(domain);
    }
  }

  private static final ManifestDomainCatalog MANIFEST_DOMAIN_CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

  public ManifestLinkPolicy {
    domains = new ManifestDomainPolicy(new LinkedHashMap<>(domains.asMap()));
    java.util.Objects.requireNonNull(debug, "debug");
  }

  public static ManifestLinkPolicy stageA(
      boolean highAvailabilityEnabled,
      boolean networkingEnabled,
      boolean replicationEnabled,
      boolean storageEnabled,
      boolean meshEnabled,
      boolean clusterApiEnabled) {
    return new ManifestLinkPolicy(
        ManifestDomainPolicy.builder()
            .domainCatalog(MANIFEST_DOMAIN_CATALOG)
            .stageADefaults()
            .highAvailability(highAvailabilityEnabled)
            .networking(networkingEnabled)
            .replication(replicationEnabled)
            .storage(storageEnabled)
            .mesh(meshEnabled)
            .clusterApi(clusterApiEnabled)
            .certManager(true)
            .build(),
        DebugPolicy.none());
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

  public boolean clusterApiEnabled() {
    return domains.isEnabled(MANIFEST_DOMAIN_CATALOG.clusterApi());
  }

  public boolean certManagerEnabled() {
    return domains.isEnabled(MANIFEST_DOMAIN_CATALOG.certManager());
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
        Boolean.toString(meshEnabled()),
        "RKE2LAB_POLICY_LINK_CLUSTER_API_ENABLED",
        Boolean.toString(clusterApiEnabled()),
        "RKE2LAB_POLICY_LINK_CERT_MANAGER_ENABLED",
        Boolean.toString(certManagerEnabled()));
  }

  public Map<String, Object> toOutputMap() {
    return Map.of(
        "policyLinkHighAvailabilityEnabled", highAvailabilityEnabled(),
        "policyLinkNetworkingEnabled", networkingEnabled(),
        "policyLinkReplicationEnabled", replicationEnabled(),
        "policyLinkStorageEnabled", storageEnabled(),
        "policyLinkMeshEnabled", meshEnabled(),
        "policyLinkClusterApiEnabled", clusterApiEnabled(),
        "policyLinkCertManagerEnabled", certManagerEnabled());
  }
}
