package io.nxmatic.rke2lab.manifests.units.storage;

import io.nxmatic.rke2lab.manifests.node.NodeEnvContext;
import io.nxmatic.rke2lab.manifests.node.NodeEnvContributor;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Storage domain node-env contributor. Contributes: etcdctl */
public class StorageNodeEnvContributor implements NodeEnvContributor {

  @Override
  public String domainId() {
    return "storage";
  }

  @Override
  public List<String> contributedSections() {
    return List.of("etcdctl");
  }

  @Override
  public Map<String, String> contributeVariables(String sectionName, NodeEnvContext context)
      throws IOException {
    return switch (sectionName) {
      case "etcdctl" ->
          Map.of(
              "ETCDCTL_API", "3",
              "ETCDCTL_CERT", "/var/lib/rancher/rke2/server/tls/etcd/server-client.crt",
              "ETCDCTL_KEY", "/var/lib/rancher/rke2/server/tls/etcd/server-client.key",
              "ETCDCTL_CACERT", "/var/lib/rancher/rke2/server/tls/etcd/server-ca.crt",
              "ETCDCTL_ENDPOINTS", "https://127.0.0.1:2379",
              "ETCDCTL_WRITE_OUT", "table",
              "ETCDCTL_DIAL_TIMEOUT", "10s",
              "ETCDCTL_COMMAND_TIMEOUT", "30s");
      default -> Map.of();
    };
  }
}
