package io.nxmatic.rk2lab.manifests.components.cluster;

import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContext;
import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContributor;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Cluster layer environment variable contributor. Contributes: cluster */
public class ClusterLayerEnvContributor implements LayerEnvContributor {

  @Override
  public String layerId() {
    return "cluster";
  }

  @Override
  public List<String> contributedSections() {
    return List.of("cluster");
  }

  @Override
  public Map<String, String> contributeVariables(String sectionName, LayerEnvContext context)
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
