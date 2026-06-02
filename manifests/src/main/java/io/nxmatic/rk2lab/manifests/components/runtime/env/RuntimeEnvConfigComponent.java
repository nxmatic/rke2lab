// @codebase
package io.nxmatic.rk2lab.manifests.components.runtime.env;

import io.nxmatic.rk2lab.manifests.layers.env.DefaultLayerEnvContext;
import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContext;
import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContributor;
import io.nxmatic.rk2lab.manifests.layers.env.LayerEnvContributorRegistry;
import io.nxmatic.rk2lab.manifests.profiles.PackageMetadataProfile;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class RuntimeEnvConfigComponent extends Construct {

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

  public RuntimeEnvConfigComponent(final Construct scope, final String id) {
    super(scope, id);

    this.layerEnvContext = new DefaultLayerEnvContext();
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
      case "kpt" -> Map.of("KRM_FN_RUNTIME", "nerdctl");
      default -> Map.of();
    };
  }
}
