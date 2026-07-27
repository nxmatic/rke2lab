package io.nxmatic.rke2lab.manifests.units.clusterapi;

import io.nxmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.nxmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.nxmatic.rke2lab.manifests.ManifestsUnitContext;
import io.nxmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.nxmatic.rke2lab.manifests.contract.profiles.ImageState;
import io.nxmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

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
 * <p><b>NOTE:</b> This unit is currently NOT registered in {@link
 * io.nxmatic.rke2lab.manifests.layers.clusterapi.ClusterApiDomainRegistrar} due to a dependency
 * cycle: CDK8s manifest synthesis happens during Stage A "host state" preparation (before Pulumi
 * provider resources are created), but the image fingerprint comes FROM a Pulumi provider resource
 * (the Incus image). This creates a chicken-and-egg problem where manifests need to be materialized
 * into {@code /srv/host} before the data they need exists.
 *
 * <p><b>Solution:</b> The ConfigMap is created via the "staged post-cluster resource" pattern
 * documented in {@code docs/staged-post-cluster-resources.adoc} — a systemd oneshot unit applies it
 * after RKE2 starts, reading image state from a metadata file written during Pulumi apply.
 *
 * <p>This class remains in the codebase as:
 *
 * <ul>
 *   <li>Documentation of the ConfigMap structure
 *   <li>Reference for the systemd bootstrap script that creates it
 *   <li>Potential future use if the dependency cycle is broken another way
 * </ul>
 *
 * <p>Values are supplied by seed-master (Stage A) through the {@link ImageState} synth slice — the
 * fingerprint via the synchronous Incus {@code getImagePlain} lookup, the checksum from the build,
 * the remote/project from the bootstrap config. When no real image state is bound (ephemeral/test
 * synth), the unit is skipped, same as for an unknown cluster identity.
 */
public final class ImageStateConfigMapManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CLUSTER_API + "/image-state";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cluster-api", "image-state");

  public ImageStateConfigMapManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String effectiveClusterName =
        ManifestSynthesisContext.current().bootstrapIdentity().clusterName();

    // Skip synthesis when running in ephemeral/test mode without real bootstrap identity
    if (BootstrapIdentity.UNKNOWN.equals(effectiveClusterName)) {
      return;
    }

    final Optional<ImageState> maybeState = ManifestSynthesisContext.current().imageState();

    // Skip when seed-master supplied no real image identity (ephemeral/test synth): an
    // all-placeholder ConfigMap would mislead Stage B into pinning a non-existent image.
    if (maybeState.isEmpty()) {
      return;
    }
    final ImageState state = maybeState.orElseThrow();

    final Map<String, String> data =
        Map.of(
            "imageAlias", state.imageAlias(),
            "imageFingerprint", state.imageFingerprint(),
            "imageBuildChecksum", state.imageBuildChecksum(),
            "incusProject", state.incusProject(),
            "incusRemoteAddress", state.incusRemoteAddress());

    createImageStateConfigMap(scope, effectiveClusterName, data);
  }

  private void createImageStateConfigMap(
      Construct scope, String clusterName, Map<String, String> imageState) {
    ApiObject configMap =
        new ApiObject(
            scope,
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
