// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContext;
import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContributor;
import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContributorRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class RuntimeEnvConfigLayer extends Construct {

  public static final String LEGACY_PATH_PREFIX = "runtime/env-config/";

  private static final List<String> ENV_SECTIONS =
      List.of(
          "cilium",
          "cluster",
          "config",
          "containerd",
          "cri",
          "daemonset-script-policy",
          "etcdctl",
          "helm",
          "kpt",
          "kubectl",
          "network-cluster",
          "network-lan-wan",
          "network-node",
          "network-vip",
          "node",
          "paths",
          "rke2",
          "user");

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("runtime", "env-config");

  private final LayerEnvContext layerEnvContext;

  private final LayerEnvContributorRegistry envContributorRegistry;

  public RuntimeEnvConfigLayer(final Construct scope, final String id) {
    super(scope, id);

    this.layerEnvContext = new DefaultSynthesisLayerEnvContext();
    this.envContributorRegistry = new LayerEnvContributorRegistry(layerEnvContext);

    for (String section : ENV_SECTIONS) {
      createSectionConfigMap(section);
    }
  }

  private void createSectionConfigMap(final String section) {
    final String cmName = "env-section-" + section;
    final Map<String, String> data = resolveEnvData(section);

    ApiObject configMap =
        new ApiObject(
            this,
            "configmap-" + cmName,
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(cmName)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ConfigMap|default|" + cmName,
                                Map.of(
                                    "config.kubernetes.io/local-config",
                                    "true",
                                    "description.kpt.dev",
                                    "Environment section " + section,
                                    "env.rk2lab.nxmatic.io/section",
                                    "section-" + section)))
                        .build())
                .build());

    configMap.addJsonPatch(JsonPatch.add("/data", data));
  }

  private Map<String, String> resolveEnvData(final String section) {
    final Map<String, String> contributorData = resolveContributorSection(section);
    if (!contributorData.isEmpty()) {
      return contributorData;
    }

    final Map<String, String> builtInSectionData = resolveBuiltInSection(section);
    if (!builtInSectionData.isEmpty()) {
      return builtInSectionData;
    }

    throw new IllegalStateException("Unsupported runtime env-config section: " + section);
  }

  private Map<String, String> resolveContributorSection(final String section) {
    for (LayerEnvContributor contributor : envContributorRegistry.orderedContributors()) {
      if (!contributor.contributedSections().contains(section)) {
        continue;
      }

      try {
        return Map.copyOf(
            new LinkedHashMap<>(contributor.contributeVariables(section, layerEnvContext)));
      } catch (IOException ex) {
        throw new IllegalStateException(
            "Failed to synthesize runtime env-config section from contributor: " + section, ex);
      }
    }

    return Map.of();
  }

  private Map<String, String> resolveBuiltInSection(final String section) {
    return switch (section) {
      case "cluster" ->
          Map.of(
              "RKE2LAB_CLUSTER_ID", Integer.toString(layerEnvContext.clusterId()),
              "RKE2LAB_CLUSTER_NAME", layerEnvContext.clusterName(),
              "RKE2LAB_CLUSTER_TOKEN", layerEnvContext.clusterToken(),
              "RKE2LAB_CLUSTER_DOMAIN", layerEnvContext.clusterDomain());
      case "node" ->
          Map.of(
              "RKE2LAB_NODE_ID", Integer.toString(layerEnvContext.nodeId()),
              "RKE2LAB_NODE_NAME", layerEnvContext.nodeName(),
              "RKE2LAB_NODE_KIND", layerEnvContext.nodeKind());
      case "paths" ->
          Map.of(
              "RKE2LAB_ROOT",
              layerEnvContext.rootPath().toString(),
              "RKE2LAB_ENV_DIR",
              layerEnvContext.envDirPath().toString(),
              "RKE2LAB_SCRIPTS_DIR",
              layerEnvContext.scriptsDirPath().toString(),
              "RKE2LAB_SYSTEMD_DIR",
              layerEnvContext.systemdDirPath().toString(),
              "RKE2LAB_CONFIG_DIR",
              layerEnvContext.configDirPath().toString(),
              "RKE2LAB_CLOUDCONFIG_NO_CLOUD_DIR",
              layerEnvContext.cloudconfigNocloudDirPath().toString(),
              "RKE2LAB_MANIFESTS_DIR",
              layerEnvContext.manifestsDirPath().toString(),
              "RKE2LAB_SHARED_DIR",
              layerEnvContext.sharedDirPath().toString(),
              "RKE2LAB_KUBECONFIG_DIR",
              layerEnvContext.kubeconfigDirPath().toString());
      case "kpt" -> Map.of("KRM_FN_RUNTIME", "nerdctl");
      default -> Map.of();
    };
  }

  private static final class DefaultSynthesisLayerEnvContext implements LayerEnvContext {

    private static final Path ROOT_PATH = Path.of("/srv/host");

    @Override
    public Path rootPath() {
      return ROOT_PATH;
    }

    @Override
    public Path envDirPath() {
      return ROOT_PATH.resolve("rke2lab-environment.d");
    }

    @Override
    public Path scriptsDirPath() {
      return ROOT_PATH.resolve("systemd-scripts.d");
    }

    @Override
    public Path systemdDirPath() {
      return ROOT_PATH.resolve("systemd-units.d");
    }

    @Override
    public Path configDirPath() {
      return ROOT_PATH.resolve("rke2-config.d");
    }

    @Override
    public Path cloudconfigNocloudDirPath() {
      return ROOT_PATH.resolve("cloudconfig-nocloud.d");
    }

    @Override
    public Path manifestsDirPath() {
      return ROOT_PATH.resolve("rke2-manifests.d");
    }

    @Override
    public Path sharedDirPath() {
      return ROOT_PATH.resolve("rke2lab-share.d");
    }

    @Override
    public Path kubeconfigDirPath() {
      return ROOT_PATH.resolve("rke2lab-kube.d");
    }

    @Override
    public int nodeId() {
      return 0;
    }

    @Override
    public String nodeName() {
      return "master";
    }

    @Override
    public String nodeKind() {
      return "server";
    }

    @Override
    public int clusterId() {
      return 0;
    }

    @Override
    public String clusterName() {
      return "bioskop";
    }

    @Override
    public String clusterToken() {
      return clusterName();
    }

    @Override
    public String clusterDomain() {
      return "cluster.local";
    }

    @Override
    public String clusterCidr() {
      return "10.80.0.0/21";
    }

    @Override
    public String clusterPodCidr() {
      return "10.42.0.0/16";
    }

    @Override
    public String clusterServiceCidr() {
      return "10.43.0.0/16";
    }

    @Override
    public String nodeHostInetAddr() {
      return "10.80.0.10";
    }

    @Override
    public String nodeNetworkCidr() {
      return "10.80.0.0/23";
    }

    @Override
    public String nodeNetworkGatewayAddr() {
      return "10.80.0.1";
    }
  }
}
