// @codebase
package io.nxmatic.rk2lab.manifests.units.runtime.cloudinit;

import io.nxmatic.rk2lab.manifests.profiles.PackageMetadataProfile;
import io.nxmatic.rk2lab.netplan.ClusterNetworkBlueprint;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class RuntimeCloudConfigComponent extends Construct {

  private static final String CLUSTER_NAME = "bioskop";
  private static final String NODE_NAME = "master";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("runtime", "cloud-config");

  private final ClusterNetworkBlueprint blueprint =
      ClusterNetworkBlueprint.builder()
          .cluster(CLUSTER_NAME)
          .node(NODE_NAME)
          .deriveRecipeModel()
          .build();

  private final RuntimeCloudConfigAssets runtimeCloudConfigAssets =
      RuntimeCloudConfigAssets.builder().build();

  public RuntimeCloudConfigComponent(final Construct scope, final String id) {
    super(scope, id);
    createCloudConfigManifest();
  }

  private void createCloudConfigManifest() {
    ApiObject configMap =
        new ApiObject(
            this,
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
                                    "config.kubernetes.io/local-config",
                                    "true",
                                    "description.kpt.dev",
                                    "Cloud-init NoCloud payload")))
                        .build())
                .build());

    // Template network-config with blueprint MAC addresses
    Map<String, String> configMapData = runtimeCloudConfigAssets.configMapData();
    String networkConfig = configMapData.get("networkData");
    if (networkConfig != null) {
      // Replace template MACs with blueprint-derived values.
      // The template uses bioskop cluster (id=0) MACs which match the blueprint
      // for bioskop, but this allows generating manifests for other clusters.
      String lanMac = blueprint.lan().hostMacaddr().value();
      String wanMac = blueprint.wan().hostMacaddr().value();

      networkConfig =
          networkConfig.replace("10:66:6a:4c:00:00", lanMac).replace("52:54:00:00:00:00", wanMac);

      // Create new map with updated network config
      java.util.Map<String, String> mutableData = new java.util.HashMap<>(configMapData);
      mutableData.put("networkData", networkConfig);
      configMapData = Map.copyOf(mutableData);
    }

    configMap.addJsonPatch(JsonPatch.add("/data", configMapData));
  }
}
