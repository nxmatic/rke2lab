package io.nxmatic.rke2lab.manifests.node;

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
      case "node" ->
          Map.of(
              "RKE2LAB_NODE_ID", Integer.toString(context.nodeId()),
              "RKE2LAB_NODE_NAME", context.nodeName(),
              "RKE2LAB_NODE_KIND", context.nodeKind());
      case "paths" ->
          Map.of(
              "RKE2LAB_ROOT", context.rootPath().toString(),
              "RKE2LAB_ENV_DIR", context.envDirPath().toString(),
              "RKE2LAB_SCRIPTS_DIR", context.scriptsDirPath().toString(),
              "RKE2LAB_SYSTEMD_DIR", context.systemdDirPath().toString(),
              "RKE2LAB_CONFIG_DIR", context.configDirPath().toString(),
              "RKE2LAB_CLOUDCONFIG_NO_CLOUD_DIR", context.cloudconfigNocloudDirPath().toString(),
              "RKE2LAB_MANIFESTS_DIR", context.manifestsDirPath().toString(),
              "RKE2LAB_SHARED_DIR", context.sharedDirPath().toString(),
              "RKE2LAB_KUBECONFIG_DIR", context.kubeconfigDirPath().toString());
      default -> Map.of();
    };
  }
}
