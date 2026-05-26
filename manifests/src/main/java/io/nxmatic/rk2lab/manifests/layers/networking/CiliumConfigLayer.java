// @codebase
package io.nxmatic.rk2lab.manifests.layers.networking;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class CiliumConfigLayer extends Construct {

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("networking", "cilium-config");

  public CiliumConfigLayer(final Construct scope, final String id) {
    super(scope, id);
    createHelmChartConfig();
    createClustermeshRemoteUsersConfigMap();
  }

  private void createClustermeshRemoteUsersConfigMap() {
    // Mounted by clustermesh-apiserver as `etcd-users-config`; without this
    // ConfigMap the pod stays in Init:0/1 waiting for the volume. We ship it
    // with no data — peer entries get appended (out-of-band, by `cilium
    // clustermesh users add`) once federated clusters come online.
    new ApiObject(
        this,
        "configmap-clustermesh-remote-users",
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("ConfigMap")
            .metadata(
                ApiObjectMetadata.builder()
                    .name("clustermesh-remote-users")
                    .namespace("kube-system")
                    .annotations(
                        packageProfile.packageAnnotations(
                            "|ConfigMap|kube-system|clustermesh-remote-users"))
                    .build())
            .build());
  }

  private void createHelmChartConfig() {
    ApiObject helmChartConfig =
        new ApiObject(
            this,
            "helmchartconfig-rke2-cilium",
            ApiObjectProps.builder()
                .apiVersion("helm.cattle.io/v1")
                .kind("HelmChartConfig")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("rke2-cilium")
                        .namespace("kube-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "helm.cattle.io|HelmChartConfig|kube-system|rke2-cilium"))
                        .build())
                .build());

    helmChartConfig.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "valuesContent",
                "installCRDs: true\n"
                    + "k8sServiceHost: \"127.0.0.1\"\n"
                    + "k8sServicePort: \"6443\"\n"
                    + "debug:\n"
                    + "  enabled: true\n"
                    + "  verbose: datapath\n"
                    + "bpf:\n"
                    + "  hostLegacyRouting: false\n"
                    + "bgpControlPlane:\n"
                    + "  enabled: true\n"
                    + "cluster:\n"
                    + "  name: sample\n"
                    + "  id: 7\n"
                    + "clustermesh:\n"
                    + "  enabled: true\n"
                    + "  useAPIServer: true\n"
                    + "  apiserver:\n"
                    + "    enabled: true\n"
                    + "    service:\n"
                    + "      type: LoadBalancer\n"
                    + "      # Using BGP announcements for cluster mesh\n"
                    + "envoy:\n"
                    + "  enabled: true\n"
                    + "gatewayAPI:\n"
                    + "  enabled: true\n"
                    + "ingressController:\n"
                    + "  default: true\n"
                    + "  enabled: true\n"
                    + "  loadBalancerMode: dedicated\n"
                    + "  service:\n"
                    + "    annotations:\n"
                    + "      io.cilium/lb-ipam-pool: lan\n"
                    + "      io.cilium/lb-ipam-ips: lan-headplane-inetaddr\n"
                    + "hubble:\n"
                    + "  enabled: true\n"
                    + "  relay:\n"
                    + "    enabled: true\n"
                    + "  ui:\n"
                    + "    enabled: true\n"
                    + "ipv4:\n"
                    + "  enabled: true\n"
                    + "ipv6:\n"
                    + "  enabled: false\n"
                    + "kubeProxyReplacement: true\n"
                    + "l2announcements:\n"
                    + "  enabled: true\n"
                    + "  leaseDuration: 15s\n"
                    + "  leaseRenewDeadline: 5s\n"
                    + "  leaseRetryPeriod: 2s\n"
                    + "l2NeighDiscovery:\n"
                    + "  enabled: true\n"
                    + "  refresh: true\n"
                    + "  refreshPeriod: 30s\n"
                    + "l7Proxy: true\n"
                    + "# routingMode: tunnel mode (vxlan/geneve) initially failed in LXC/Incus\n"
                    + "# containers with 'protocol not supported' error in route reconciler netlink\n"
                    + "# initialization. Root cause was missing ip_set kernel modules on the NixOS\n"
                    + "# host (now fixed in nix-darwin-home cilium-kernel-modules.nix). Tunnel mode\n"
                    + "# may work now but native routing is more appropriate: all control nodes run\n"
                    + "# as containers on the same host, so native routing is more efficient and\n"
                    + "# avoids encapsulation overhead. Cluster mesh works via apiserver regardless.\n"
                    + "routingMode: native\n"
                    + "autoDirectNodeRoutes: true\n"
                    + "ipv4NativeRoutingCIDR: 10.42.0.0/16\n"
                    + "operator:\n"
                    + "  replicas: 1\n"
                    + "  podDisruptionBudget:\n"
                    + "    enabled: true\n"
                    + "socketLB:\n"
                    + "  enabled: true")));
  }
}
