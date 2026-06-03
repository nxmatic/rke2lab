package io.nxmatic.rke2lab.manifests.units.cluster;

import io.nxmatic.rke2lab.manifests.node.NodeEnvContext;
import io.nxmatic.rke2lab.manifests.node.NodeEnvContributor;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Cluster domain node-env contributor. Contributes: cluster */
public class ClusterNodeEnvContributor implements NodeEnvContributor {

  @Override
  public String domainId() {
    return "cluster";
  }

  @Override
  public List<String> contributedSections() {
    return List.of("cluster");
  }

  @Override
  public Map<String, String> contributeVariables(String sectionName, NodeEnvContext context)
      throws IOException {
    return switch (sectionName) {
      case "cluster" ->
          Map.of(
              "RKE2LAB_CLUSTER_ID", Integer.toString(context.clusterId()),
              "RKE2LAB_CLUSTER_NAME", context.clusterName(),
              "RKE2LAB_CLUSTER_TOKEN", context.clusterToken(),
              "RKE2LAB_CLUSTER_DOMAIN", context.clusterDomain());
      default -> Map.of();
    };
  }
}
