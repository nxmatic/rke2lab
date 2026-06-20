// @codebase
package io.nxmatic.rke2lab.manifests.port.profiles;

/**
 * Stage A → Stage B image-identity slice published to synth-time layers via {@link
 * io.nxmatic.rke2lab.manifests.ManifestSynthesisContext}. Backs the {@code <cluster>-image-state}
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
 * bootstrapped), the checksum from the build, the remote/project from the bootstrap config. The
 * default instance backs unit tests and ephemeral synth runs that don't go through seed-master.
 */
public record ImageState(
    String imageAlias,
    String imageFingerprint,
    String imageBuildChecksum,
    String incusProject,
    String incusRemoteAddress) {

  /** Sentinel used when seed-master hasn't supplied image state (tests, ephemeral runs). */
  public static final String UNKNOWN = "unknown";

  private static final ImageState DEFAULT = builder().build();

  public ImageState {
    imageAlias = blankToUnknown(imageAlias);
    imageFingerprint = blankToUnknown(imageFingerprint);
    imageBuildChecksum = blankToUnknown(imageBuildChecksum);
    incusProject = blankToUnknown(incusProject);
    incusRemoteAddress = blankToUnknown(incusRemoteAddress);
  }

  /**
   * Default instance used by {@link
   * io.nxmatic.rke2lab.manifests.ManifestSynthesisContext#current()} when nothing was bound —
   * production callers always override via the synth request.
   */
  public static ImageState unknown() {
    return DEFAULT;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** True when every field is still the sentinel — i.e. seed-master supplied no real identity. */
  public boolean isUnknown() {
    return UNKNOWN.equals(imageFingerprint) && UNKNOWN.equals(imageAlias);
  }

  private static String blankToUnknown(String value) {
    return (value == null || value.isBlank()) ? UNKNOWN : value;
  }

  /**
   * The recommended construction path: names each field so the image identity values can't be
   * positionally swapped.
   */
  public static final class Builder {
    private String imageAlias = UNKNOWN;
    private String imageFingerprint = UNKNOWN;
    private String imageBuildChecksum = UNKNOWN;
    private String incusProject = UNKNOWN;
    private String incusRemoteAddress = UNKNOWN;

    private Builder() {}

    public Builder imageAlias(final String v) {
      this.imageAlias = v;
      return this;
    }

    public Builder imageFingerprint(final String v) {
      this.imageFingerprint = v;
      return this;
    }

    public Builder imageBuildChecksum(final String v) {
      this.imageBuildChecksum = v;
      return this;
    }

    public Builder incusProject(final String v) {
      this.incusProject = v;
      return this;
    }

    public Builder incusRemoteAddress(final String v) {
      this.incusRemoteAddress = v;
      return this;
    }

    public ImageState build() {
      return new ImageState(
          imageAlias, imageFingerprint, imageBuildChecksum, incusProject, incusRemoteAddress);
    }
  }
}
