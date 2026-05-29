package io.nxmatic.rk2lab.manifests.layers.clusterapi;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.common.profiles.BootstrapIdentity;
import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.Chart;
import org.cdk8s.JsonPatch;

/**
 * Manifest unit that creates the image-state ConfigMap for Stage A → Stage B handoff.
 *
 * <p>The ConfigMap provides cluster image identity to seed-peers (Stage B) for synthesizing
 * LXCMachineTemplate CRs. Contains:
 *
 * <ul>
 *   <li>{@code imageAlias} - Image alias (e.g., "control-node")
 *   <li>{@code imageFingerprint} - Immutable image fingerprint from imageProvider
 *   <li>{@code imageBuildChecksum} - SHA-256 of image build inputs
 *   <li>{@code incusProject} - Incus project name (e.g., "rke2lab")
 *   <li>{@code incusRemoteAddress} - Remote URI (e.g., "https://bioskop-nixos:8443")
 * </ul>
 *
 * <p>The ConfigMap is named {@code <cluster-name>-image-state} in namespace {@code capn-system}.
 *
 * <p><b>Note:</b> Currently uses placeholder values. These will be populated from Stage A outputs
 * (imageProvider, BuildMetadata) in a future iteration when seed-bootstrap integration is complete.
 */
public final class ImageStateConfigMapManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "clusterapi/image-state";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("clusterapi", "image-state");

  public ImageStateConfigMapManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    final String clusterName = bootstrapIdentity().clusterName();

    // Skip synthesis when running in ephemeral/test mode without real bootstrap identity
    if (BootstrapIdentity.UNKNOWN.equals(clusterName)) {
      return;
    }

    // TODO: These values should come from Stage A outputs (imageProvider, BuildMetadata)
    // For now, using placeholders to establish the handoff contract
    final Map<String, String> imageState =
        Map.of(
            "imageAlias", "control-node",
            "imageFingerprint", "PLACEHOLDER-fingerprint-from-imageProvider",
            "imageBuildChecksum", "PLACEHOLDER-checksum-from-BuildMetadata",
            "incusProject", "rke2lab",
            "incusRemoteAddress", "PLACEHOLDER-remote-from-bootstrapIdentity");

    createImageStateConfigMap(chart, clusterName, imageState);
  }

  private void createImageStateConfigMap(
      Chart chart, String clusterName, Map<String, String> imageState) {
    ApiObject configMap =
        new ApiObject(
            chart,
            "configmap-image-state",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(clusterName + "-image-state")
                        .namespace("capn-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ConfigMap|capn-system|" + clusterName + "-image-state"))
                        .build())
                .build());

    configMap.addJsonPatch(JsonPatch.add("/data", imageState));
  }
}
