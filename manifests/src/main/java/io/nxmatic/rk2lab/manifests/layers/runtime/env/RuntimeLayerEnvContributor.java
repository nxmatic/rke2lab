package io.nxmatic.rk2lab.manifests.layers.runtime.env;

import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContext;
import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContributor;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Runtime layer environment variable contributor. Contributes: rke2, config, containerd, cri, helm,
 * kubectl, user, daemonset-script-policy
 */
public final class RuntimeLayerEnvContributor implements LayerEnvContributor {

  @Override
  public String layerId() {
    return "runtime";
  }

  @Override
  public List<String> contributedSections() {
    return List.of(
        "rke2",
        "config",
        "containerd",
        "cri",
        "helm",
        "kubectl",
        "user",
        "daemonset-script-policy");
  }

  @Override
  public Map<String, String> contributeVariables(String sectionName, LayerEnvContext context)
      throws IOException {
    return switch (sectionName) {
      case "rke2" -> Map.of("RKE2_SERVER_MANIFESTS_DIR", "/var/lib/rancher/rke2/server/manifests");
      case "config" -> Map.of("RKE2LAB_DEBUG", "false");
      case "containerd" ->
          Map.of(
              "CONTAINERD_ADDRESS",
              "/run/k3s/containerd/containerd.sock",
              "CONTAINERD_NAMESPACE",
              "k8s.io",
              "CONTAINERD_CONFIG_FILE",
              "/var/lib/rancher/rke2/agent/etc/containerd/config.toml",
              "CONTAINERD_SHIM_FLOX_DIR",
              "/srv/host/k8s-daemonset.d/runtime/containerd-shim-flox");
      case "cri" -> Map.of("CRI_CONFIG_FILE", "/var/lib/rancher/rke2/agent/etc/crictl.yaml");
      case "helm" ->
          Map.of(
              "HELM_DATA_HOME", "/var/lib/rancher/rke2/helm",
              "HELM_CONFIG_HOME", "/etc/rancher/rke2/helm",
              "HELM_CACHE_HOME", "/var/cache/rancher/rke2/helm",
              "HELM_REPOSITORY_CONFIG", "/etc/rancher/rke2/helm/repositories.yaml",
              "HELM_REPOSITORY_CACHE", "/var/cache/rancher/rke2/helm/repository",
              "HELM_PLUGINS", "/var/lib/rancher/rke2/helm/plugins");
      case "kubectl" ->
          Map.of(
              "KUBECTL_OUTPUT", "yaml",
              "KUBECTL_EXTERNAL_DIFF", "delta",
              "KREW_ROOT", "/var/lib/rancher/rke2/krew");
      case "user" ->
          Map.of(
              "USER", "root",
              "HOME", "/root");
      case "daemonset-script-policy" ->
          Map.of(
              "DAEMONSET_SCRIPT_ROOT", "/var/lib/rke2lab/daemonset-scripts.d",
              "SCRIPT_POLICY_ROOT", "/var/lib/rke2lab/script-policy.d");
      default -> Map.of();
    };
  }
}
