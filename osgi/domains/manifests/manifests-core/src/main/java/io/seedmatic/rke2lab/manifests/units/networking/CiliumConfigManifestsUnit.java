// @codebase
package io.seedmatic.rke2lab.manifests.units.networking;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class CiliumConfigManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.NETWORKING + "/cilium-config";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("networking", "cilium-config", true);

  public CiliumConfigManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    createHelmChartConfig(scope);
    createClustermeshRemoteUsersConfigMap(scope);
  }

  private void createClustermeshRemoteUsersConfigMap(final Construct scope) {
    // Mounted by clustermesh-apiserver as `etcd-users-config`; without this
    // ConfigMap the pod stays in Init:0/1 waiting for the volume. We ship it
    // with no data — peer entries get appended (out-of-band, by `cilium
    // clustermesh users add`) once federated clusters come online.
    new ApiObject(
        scope,
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

  private void createHelmChartConfig(final Construct scope) {
    ApiObject helmChartConfig =
        new ApiObject(
            scope,
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
                """
                installCRDs: true
                k8sServiceHost: "127.0.0.1"
                k8sServicePort: "6443"
                debug:
                  enabled: true
                  verbose: datapath
                bpf:
                  hostLegacyRouting: false
                bgpControlPlane:
                  enabled: true
                cluster:
                  name: sample
                  id: 7
                clustermesh:
                  enabled: true
                  useAPIServer: true
                  apiserver:
                    enabled: true
                    service:
                      type: LoadBalancer
                      # Using BGP announcements for cluster mesh
                envoy:
                  enabled: true
                gatewayAPI:
                  enabled: true
                ingressController:
                  default: true
                  enabled: true
                  loadBalancerMode: dedicated
                  service:
                    annotations:
                      io.cilium/lb-ipam-pool: lan
                      io.cilium/lb-ipam-ips: lan-headplane-inetaddr
                hubble:
                  enabled: true
                  relay:
                    enabled: true
                  ui:
                    enabled: true
                ipv4:
                  enabled: true
                ipv6:
                  enabled: true
                kubeProxyReplacement: true
                l2announcements:
                  enabled: true
                  leaseDuration: 15s
                  leaseRenewDeadline: 5s
                  leaseRetryPeriod: 2s
                l2NeighDiscovery:
                  enabled: true
                  refresh: true
                  refreshPeriod: 30s
                l7Proxy: true
                # routingMode: tunnel mode (vxlan/geneve) initially failed in LXC/Incus
                # containers with 'protocol not supported' error in route reconciler netlink
                # initialization. Root cause was missing ip_set kernel modules on the NixOS
                # host (now fixed in nix-darwin-home cilium-kernel-modules.nix). Tunnel mode
                # may work now but native routing is more appropriate: all control nodes run
                # as containers on the same host, so native routing is more efficient and
                # avoids encapsulation overhead. Cluster mesh works via apiserver regardless.
                routingMode: native
                autoDirectNodeRoutes: true
                ipv4NativeRoutingCIDR: %s
                ipv6NativeRoutingCIDR: %s
                operator:
                  replicas: 1
                  podDisruptionBudget:
                    enabled: true
                socketLB:
                  enabled: true"""
                    .formatted(
                        ClusterNetworkBlueprint.POD_CIDR, ClusterNetworkBlueprint.POD_CIDR_V6))));
  }
}
