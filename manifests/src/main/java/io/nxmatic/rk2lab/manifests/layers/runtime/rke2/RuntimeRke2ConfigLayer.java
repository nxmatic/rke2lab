// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime.rke2;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class RuntimeRke2ConfigLayer extends Construct {

  public static final String LEGACY_PATH_PREFIX = "runtime/rke2-config/";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("runtime", "rke2-config");

  public RuntimeRke2ConfigLayer(final Construct scope, final String id) {
    super(scope, id);

    createConfigMap(
        "advertise-address.yaml",
        "Advertise address fragment",
        "|ConfigMap|default|rke2-advertise-address",
        Map.of("advertise-address", "10.80.0.10"));
    createConfigMap(
        "cidrs.yaml",
        "Network CIDRs fragment",
        "|ConfigMap|default|rke2-cidrs",
        orderedMap(
            entry("kube-controller-manager-arg", List.of("node-cidr-mask-size-ipv4=24")),
            entry("service-cidr", "10.43.0.0/16"),
            entry("cluster-cidr", "10.42.0.0/16")));
    createConfigMap(
        "cluster-init.yaml",
        "Cluster init flag (only true on first master)",
        "|ConfigMap|default|rke2-cluster-init",
        Map.of("cluster-init", true));
    createConfigMap(
        "core.yaml",
        "Core RKE2 settings",
        "|ConfigMap|default|rke2-core",
        orderedMap(
            entry("write-kubeconfig-mode", "0640"),
            entry("bind-address", "0.0.0.0"),
            entry("ingress-controller", "traefik"),
            entry("cni", "cilium")));
    createConfigMap(
        "debug.yaml",
        "Enable RKE2 debug logging for manifest watcher",
        "|ConfigMap|default|debug",
        orderedMap(entry("v", "4"), entry("debug", "false")));
    createConfigMap(
        "disable.yaml",
        "Disable list fragment",
        "|ConfigMap|default|rke2-disable",
        Map.of(
            "disable",
            List.of(
                "rke2-snapshot-controller",
                "rke2-snapshot-controller-crd",
                "rke2-snapshot-validation-webhook",
                "rke2-ingress-nginx")));
    createConfigMap(
        "etcd-metrics.yaml",
        "Etcd metrics fragment",
        "|ConfigMap|default|rke2-etcd-metrics",
        Map.of("etcd-expose-metrics", true));
    createConfigMap(
        "etcd.yaml",
        "Etcd settings fragment",
        "|ConfigMap|default|rke2-etcd",
        orderedMap(
            entry("with-node-id", false),
            entry("node-name", "bioskop-master"),
            entry("etcd-expose-metrics", false)));
    createConfigMap(
        "node-inetaddr.yaml",
        "Node IP fragment",
        "|ConfigMap|default|rke2-node-inetaddr",
        Map.of("node-ip", "10.80.0.10"));
    createConfigMap(
        "node-labels.yaml",
        "Node labels fragment",
        "|ConfigMap|default|rke2-node-labels",
        Map.of(
            "node-label",
            List.of(
                "node.kubernetes.io/instance-name=master",
                "node.kubernetes.io/instance-kind=server",
                "flox.dev/enabled=true")));
    createConfigMap(
        "tls-san.yaml",
        "TLS SAN fragment",
        "|ConfigMap|default|rke2-tls-san",
        Map.of(
            "tls-san",
            List.of(
                "localhost",
                "gateway",
                "0.0.0.0",
                "127.0.0.1",
                "10.80.7.1",
                "10.80.0.1",
                "10.80.0.10")));
    createConfigMap(
        "token.yaml",
        "RKE2 token fragment",
        "|ConfigMap|default|rke2-token",
        Map.of("token", "bioskop"));
  }

  private void createConfigMap(
      final String name,
      final String description,
      final String upstreamIdentifier,
      final Map<String, Object> data) {
    ApiObject configMap =
        new ApiObject(
            this,
            "configmap-rke2-" + name.replace('.', '-'),
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .annotations(
                            packageProfile.packageAnnotations(
                                upstreamIdentifier,
                                Map.of(
                                    "config.kubernetes.io/local-config",
                                    "true",
                                    "description.kpt.dev",
                                    description)))
                        .build())
                .build());

    configMap.addJsonPatch(JsonPatch.add("/data", data));
  }

  @SafeVarargs
  private static Map<String, Object> orderedMap(final Map.Entry<String, Object>... entries) {
    final LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : entries) {
      out.put(entry.getKey(), entry.getValue());
    }
    return Map.copyOf(out);
  }

  private static Map.Entry<String, Object> entry(final String key, final Object value) {
    return Map.entry(key, value);
  }
}
