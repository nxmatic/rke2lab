// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime.rke2;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import io.nxmatic.rk2lab.manifests.layers.env.DefaultLayerEnvContext;
import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import software.constructs.Construct;

public final class RuntimeRke2ConfigLayer extends Construct {

  private static final Yaml YAML_SCALAR_SERIALIZER = createYamlScalarSerializer();

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("runtime", "rke2-config");

  public RuntimeRke2ConfigLayer(final Construct scope, final String id) {
    super(scope, id);

    final LayerEnvContext context = new DefaultLayerEnvContext();

    createConfigMap(
        "advertise-address.yaml",
        "Advertise address fragment",
        "|ConfigMap|default|rke2-advertise-address",
        Map.of("advertise-address", context.nodeHostInetAddr()));
    createConfigMap(
        "cidrs.yaml",
        "Network CIDRs fragment",
        "|ConfigMap|default|rke2-cidrs",
        orderedMap(
            entry("kube-controller-manager-arg", List.of("node-cidr-mask-size-ipv4=24")),
            entry("service-cidr", context.clusterServiceCidr()),
            entry("cluster-cidr", context.clusterPodCidr())));
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
                // Keep RKE2's snapshot controller + validation webhook disabled.
                // The openebs-zfs chart already ships its own snapshot-controller
                // sidecar in its localpv-controller deployment; running RKE2's
                // alongside would mean two controllers reconciling the same
                // VolumeSnapshot CRs.
                "rke2-snapshot-controller",
                "rke2-snapshot-validation-webhook",
                // rke2-snapshot-controller-crd is *enabled* (i.e. not in this
                // list) so the upstream VolumeSnapshot{,Content,Class} CRDs
                // exist on the cluster. Without them the openebs-zfs-bundled
                // snapshot-controller v8 hard-fails at startup ("Exiting due
                // to failure to ensure CRDs exist"). The CRDs themselves are
                // pure data — they don't bring a controller of their own —
                // so installing them is the minimum-viable fix that makes the
                // openebs-zfs controller pod healthy without introducing a
                // second snapshot-controller.
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
        Map.of("node-ip", context.nodeHostInetAddr()));
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
                context.vipGatewayInetAddr(),
                context.nodeNetworkGatewayAddr(),
                context.nodeHostInetAddr(),
                context.lanHostInetAddr())));
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

    configMap.addJsonPatch(JsonPatch.add("/data", toConfigMapData(data)));
  }

  private static Map<String, String> toConfigMapData(final Map<String, Object> data) {
    final LinkedHashMap<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : data.entrySet()) {
      out.put(entry.getKey(), yamlScalarString(entry.getValue()));
    }
    return Collections.unmodifiableMap(out);
  }

  private static String yamlScalarString(final Object value) {
    final String dumped = YAML_SCALAR_SERIALIZER.dump(value);
    return dumped.endsWith("\n") ? dumped.substring(0, dumped.length() - 1) : dumped;
  }

  private static Yaml createYamlScalarSerializer() {
    final DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setPrettyFlow(false);
    options.setSplitLines(false);
    return new Yaml(options);
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
