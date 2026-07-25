package io.nxmatic.rke2lab.manifests.node;

import io.nxmatic.rke2lab.manifests.contract.node.NodeEnvContext;
import io.nxmatic.rke2lab.manifests.contract.node.NodeEnvContributor;
import io.nxmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.nxmatic.rke2lab.manifests.contract.profiles.HostPaths;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/** Node-identity domain node-env contributor. Contributes: node, paths */
@Component(service = NodeEnvContributor.class)
public class NodeEnvIdentityContributor implements NodeEnvContributor {

  @Override
  public String domainId() {
    return "node";
  }

  @Override
  public List<String> contributedSections() {
    return List.of("node", "paths");
  }

  @Override
  public Map<String, String> contributeVariables(String sectionName, NodeEnvContext context)
      throws IOException {
    return switch (sectionName) {
      case "node" -> {
        final BootstrapIdentity id = context.bootstrapIdentity();
        yield Map.of(
            "RKE2LAB_NODE_ID", Integer.toString(id.nodeId()),
            "RKE2LAB_NODE_NAME", id.nodeName(),
            "RKE2LAB_NODE_KIND", id.nodeKind());
      }
      case "paths" -> {
        final HostPaths paths = context.hostPaths();
        yield Map.of(
            "RKE2LAB_ROOT", paths.rootPath().toString(),
            "RKE2LAB_SCRIPTS_DIR", paths.scriptsDirPath().toString(),
            "RKE2LAB_SYSTEMD_DIR", paths.systemdDirPath().toString(),
            "RKE2LAB_CONFIG_DIR", paths.configDirPath().toString(),
            "RKE2LAB_CLOUDCONFIG_NO_CLOUD_DIR", paths.cloudconfigNocloudDirPath().toString(),
            "RKE2LAB_MANIFESTS_DIR", paths.manifestsDirPath().toString(),
            "RKE2LAB_SHARED_DIR", paths.sharedDirPath().toString(),
            "RKE2LAB_KUBECONFIG_DIR", paths.kubeconfigDirPath().toString(),
            "RKE2LAB_ENV_FILE",
                paths.scriptsDirPath().resolve("rke2lab-environment.sh").toString());
      }
      default -> Map.of();
    };
  }
}
