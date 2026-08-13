// @codebase
package io.seedmatic.rke2lab.manifests.contract.profiles;

import java.util.Objects;

/**
 * Stage A → Stage B image-identity slice published to synth-time layers via {@link
 * io.seedmatic.rke2lab.manifests.ManifestSynthesisContext}. Backs the {@code <cluster>-image-state}
 * ConfigMap (see {@code ImageStateConfigMapManifestsUnit}) that hands the control-node image's
 * identity to the in-cluster Cluster API / CAPN provider, which has no access to Stage A's Pulumi
 * outputs and can only read Kubernetes objects.
 *
 * <p>The {@code imageFingerprint} is the immutable content hash Incus assigns; {@code
 * LXCMachineTemplate} should pin it (not the re-pointable {@code imageAlias}) so peers launch from
 * the exact image Stage A built. {@code imageBuildChecksum} is the SHA-256 of the build inputs,
 * used for drift detection.
 *
 * <p>Values originate in seed-master's Stage A image provider: the fingerprint via the synchronous
 * Incus {@code getImagePlain} lookup (the control-node image already exists once master is
 * bootstrapped), the checksum from the build, the remote/project from the bootstrap config. Absence
 * — a run that supplied no image identity (unit tests, ephemeral synth) — is carried as an empty
 * {@code Optional<ImageState>} on the synthesis request, never a placeholder instance: a present
 * {@code ImageState} always holds a real identity, so the unit renders it unconditionally.
 */
public record ImageState(
    String imageAlias,
    String imageFingerprint,
    String imageBuildChecksum,
    String incusProject,
    String incusRemoteAddress) {

  public ImageState {
    imageAlias = Objects.requireNonNull(imageAlias, "imageAlias");
    imageFingerprint = Objects.requireNonNull(imageFingerprint, "imageFingerprint");
    imageBuildChecksum = Objects.requireNonNull(imageBuildChecksum, "imageBuildChecksum");
    incusProject = Objects.requireNonNull(incusProject, "incusProject");
    incusRemoteAddress = Objects.requireNonNull(incusRemoteAddress, "incusRemoteAddress");
  }
}
