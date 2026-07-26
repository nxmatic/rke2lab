// @codebase
package io.nxmatic.rke2lab.manifests.units.runtime.cloudinit;

import io.nxmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.nxmatic.rke2lab.manifests.ManifestsUnitContext;
import io.nxmatic.rke2lab.manifests.contract.ManifestAnnotations;
import io.nxmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.contract.node.NodeEnvContext;
import io.nxmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class CloudConfigManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.RUNTIME + "/cloud-config";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("runtime", "cloud-config");

  private final RuntimeCloudConfigAssets runtimeCloudConfigAssets =
      RuntimeCloudConfigAssets.builder().build();

  public CloudConfigManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    createCloudConfigManifest(scope, context.nodeEnvContext());
  }

  private void createCloudConfigManifest(
      final Construct scope, final NodeEnvContext nodeEnvContext) {
    ApiObject configMap =
        new ApiObject(
            scope,
            "configmap-cloud-config",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("cloud-config")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ConfigMap|default|cloud-config",
                                Map.of(
                                    ManifestAnnotations.LOCAL_CONFIG,
                                    "true",
                                    "description.kpt.dev",
                                    "Cloud-init NoCloud payload")))
                        .build())
                .build());

    // Template the NoCloud seed with the run's identity — the node env projects the cluster/node
    // blueprint, so the seed matches whatever cluster this run synthesises for (no hardcoded
    // cluster). Two placeholders are filled: the host name (meta-data + user-data) and the
    // network-config MACs.
    final var id = nodeEnvContext.bootstrapIdentity();
    final String hostName = id.clusterName() + "-" + id.nodeName();

    final Map<String, String> mutableData = new HashMap<>(runtimeCloudConfigAssets.configMapData());
    mutableData.replaceAll((key, value) -> value.replace("__RKE2LAB_HOST__", hostName));

    final String networkConfig = mutableData.get("networkData");
    if (networkConfig != null) {
      final var net = nodeEnvContext.networkTopology();
      mutableData.put(
          "networkData",
          networkConfig
              .replace("10:66:6a:4c:00:00", net.lanHostMacAddr())
              .replace("52:54:00:00:00:00", net.wanHostMacAddr()));
    }

    configMap.addJsonPatch(JsonPatch.add("/data", Map.copyOf(mutableData)));
  }
}
