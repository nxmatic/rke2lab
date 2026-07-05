package io.nxmatic.rke2lab.controlplane.policy;

import io.nxmatic.rke2lab.manifests.port.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.port.ManifestDomainPolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Policy controlling which serialized host manifest layers are linked into live RKE2 manifests. */
public record ManifestLinkPolicy(ManifestDomainPolicy domains, DebugPolicy debug) {

  public record DebugPolicy(Predicate<String> domainDebug) {
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
    Objects.requireNonNull(debug, "debug");
  }

  public static ManifestLinkPolicy stageA(
      boolean highAvailabilityEnabled,
      boolean networkingEnabled,
      boolean storageEnabled,
      boolean meshEnabled,
      boolean clusterApiEnabled) {
    return new ManifestLinkPolicy(
        ManifestDomainPolicy.builder()
            .domainCatalog(MANIFEST_DOMAIN_CATALOG)
            .stageADefaults()
            .highAvailability(highAvailabilityEnabled)
            .networking(networkingEnabled)
            .storage(storageEnabled)
            .mesh(meshEnabled)
            .clusterApi(clusterApiEnabled)
            .platform(true)
            .build(),
        DebugPolicy.none());
  }

  public boolean highAvailabilityEnabled() {
    return domains.isEnabled(MANIFEST_DOMAIN_CATALOG.highAvailability());
  }

  public boolean networkingEnabled() {
    return domains.isEnabled(MANIFEST_DOMAIN_CATALOG.networking());
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

  public boolean platformEnabled() {
    return domains.isEnabled(MANIFEST_DOMAIN_CATALOG.platform());
  }

  public Map<String, String> toEnvMap() {
    return Map.of(
        "RKE2LAB_POLICY_LINK_HIGH_AVAILABILITY_ENABLED",
        Boolean.toString(highAvailabilityEnabled()),
        "RKE2LAB_POLICY_LINK_NETWORKING_ENABLED",
        Boolean.toString(networkingEnabled()),
        "RKE2LAB_POLICY_LINK_STORAGE_ENABLED",
        Boolean.toString(storageEnabled()),
        "RKE2LAB_POLICY_LINK_MESH_ENABLED",
        Boolean.toString(meshEnabled()),
        "RKE2LAB_POLICY_LINK_CLUSTER_API_ENABLED",
        Boolean.toString(clusterApiEnabled()),
        "RKE2LAB_POLICY_LINK_PLATFORM_ENABLED",
        Boolean.toString(platformEnabled()));
  }

  public Map<String, Object> toOutputMap() {
    return Map.of(
        "policyLinkHighAvailabilityEnabled", highAvailabilityEnabled(),
        "policyLinkNetworkingEnabled", networkingEnabled(),
        "policyLinkStorageEnabled", storageEnabled(),
        "policyLinkMeshEnabled", meshEnabled(),
        "policyLinkClusterApiEnabled", clusterApiEnabled(),
        "policyLinkPlatformEnabled", platformEnabled());
  }
}
