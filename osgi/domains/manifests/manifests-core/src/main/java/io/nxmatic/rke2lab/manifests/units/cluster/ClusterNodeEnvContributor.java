package io.nxmatic.rke2lab.manifests.units.cluster;

import io.nxmatic.rke2lab.manifests.contract.node.NodeEnvContext;
import io.nxmatic.rke2lab.manifests.contract.node.NodeEnvContributor;
import io.nxmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/** Cluster domain node-env contributor. Contributes: cluster */
@Component(service = NodeEnvContributor.class)
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
      case "cluster" -> {
        final BootstrapIdentity id = context.bootstrapIdentity();
        yield Map.of(
            "RKE2LAB_CLUSTER_ID", Integer.toString(id.clusterId()),
            "RKE2LAB_CLUSTER_NAME", id.clusterName(),
            "RKE2LAB_CLUSTER_TOKEN", id.clusterToken(),
            "RKE2LAB_CLUSTER_DOMAIN", id.clusterDomain());
      }
      default -> Map.of();
    };
  }
}
