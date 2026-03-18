package io.nxmatic.rk2lab.manifests.layers.storage;

import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContext;
import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContributor;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Storage layer environment variable contributor. Contributes: etcdctl */
public class StorageLayerEnvContributor implements LayerEnvContributor {

  @Override
  public String layerId() {
    return "storage";
  }

  @Override
  public List<String> contributedSections() {
    return List.of("etcdctl");
  }

  @Override
  public Map<String, String> contributeVariables(String sectionName, LayerEnvContext context)
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
