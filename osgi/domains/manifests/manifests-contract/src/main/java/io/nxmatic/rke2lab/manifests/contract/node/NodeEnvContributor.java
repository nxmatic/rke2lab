package io.nxmatic.rke2lab.manifests.contract.node;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Contract for domains to contribute node environment variables. Implementations are SCR
 * {@code @Component}s, discovered through the OSGi service registry and aggregated by {@code
 * NodeEnvContributorRegistry} (a {@code @Reference(MULTIPLE)}) for runtime env-config synthesis.
 */
public interface NodeEnvContributor {

  /**
   * Unique identifier for this contributor (e.g., "networking", "storage", "high-availability").
   * Used for ConfigMap naming and override ordering.
   */
  String domainId();

  /**
   * List of environment sections this domain contributes. Examples: ["cilium", "network-cluster",
   * "network-node"]
   */
  List<String> contributedSections();

  /**
   * Generate environment variables for the given section.
   *
   * @param sectionName the section being contributed (one of contributedSections())
   * @param context read-only context with bootstrap paths, node identity, cluster topology
   * @return map of KEY=VALUE environment variables
   * @throws IOException if contribution fails
   */
  Map<String, String> contributeVariables(String sectionName, NodeEnvContext context)
      throws IOException;
}
