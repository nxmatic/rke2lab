// @codebase
package io.nxmatic.rke2lab.manifests.units.runtime.env;

import io.nxmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.nxmatic.rke2lab.manifests.ManifestAnnotations;
import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.ManifestsUnitContext;
import io.nxmatic.rke2lab.manifests.node.DefaultNodeEnvContext;
import io.nxmatic.rke2lab.manifests.node.NodeEnvContext;
import io.nxmatic.rke2lab.manifests.node.NodeEnvContributor;
import io.nxmatic.rke2lab.manifests.node.NodeEnvContributorRegistry;
import io.nxmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class RKE2LabEnvConfigManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.RUNTIME + "/env-config";

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

  public RKE2LabEnvConfigManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final NodeEnvContext nodeEnvContext = new DefaultNodeEnvContext();
    final NodeEnvContributorRegistry envContributorRegistry =
        new NodeEnvContributorRegistry(nodeEnvContext);

    for (String section : ENV_SECTIONS) {
      createSectionConfigMap(scope, section, nodeEnvContext, envContributorRegistry);
    }
  }

  private void createSectionConfigMap(
      final Construct scope,
      final String section,
      final NodeEnvContext nodeEnvContext,
      final NodeEnvContributorRegistry envContributorRegistry) {
    final String cmName = "env-section-" + section;
    final Map<String, String> data =
        resolveEnvData(section, nodeEnvContext, envContributorRegistry);

    ApiObject configMap =
        new ApiObject(
            scope,
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
                                    ManifestAnnotations.LOCAL_CONFIG,
                                    "true",
                                    "description.kpt.dev",
                                    "Environment section " + section,
                                    "env.rke2lab.nxmatic.io/section",
                                    "section-" + section)))
                        .build())
                .build());

    configMap.addJsonPatch(JsonPatch.add("/data", data));
  }

  private static Map<String, String> resolveEnvData(
      final String section,
      final NodeEnvContext nodeEnvContext,
      final NodeEnvContributorRegistry envContributorRegistry) {
    final Map<String, String> contributorData =
        resolveContributorSection(section, nodeEnvContext, envContributorRegistry);
    if (!contributorData.isEmpty()) {
      return contributorData;
    }

    final Map<String, String> builtInSectionData = resolveBuiltInSection(section);
    if (!builtInSectionData.isEmpty()) {
      return builtInSectionData;
    }

    throw new IllegalStateException("Unsupported runtime env-config section: " + section);
  }

  private static Map<String, String> resolveContributorSection(
      final String section,
      final NodeEnvContext nodeEnvContext,
      final NodeEnvContributorRegistry envContributorRegistry) {
    for (NodeEnvContributor contributor : envContributorRegistry.orderedContributors()) {
      if (!contributor.contributedSections().contains(section)) {
        continue;
      }

      try {
        return Map.copyOf(
            new LinkedHashMap<>(contributor.contributeVariables(section, nodeEnvContext)));
      } catch (IOException ex) {
        throw new IllegalStateException(
            "Failed to synthesize runtime env-config section from contributor: " + section, ex);
      }
    }

    return Map.of();
  }

  private static Map<String, String> resolveBuiltInSection(final String section) {
    return switch (section) {
      case "kpt" -> Map.of("KRM_FN_RUNTIME", "nerdctl");
      default -> Map.of();
    };
  }
}
