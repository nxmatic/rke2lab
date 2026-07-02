package io.nxmatic.rke2lab.controlplane.incus;

import java.util.Map;
import java.util.Optional;

/**
 * Build metadata — state of build artifacts at deployment time.
 *
 * @param image container image build state — empty until the image build stage produces the
 *     checksum (the host stage registers it before that)
 * @param manifests synthesized Kubernetes manifests state
 */
public record BuildMetadata(Optional<Image> image, Manifests manifests) {

  /** The built image, required — throws if read before the image build stage produced it. */
  public Image requireImage() {
    return image.orElseThrow(() -> new IllegalStateException("image checksum not yet built"));
  }

  /**
   * Container image build state.
   *
   * @param checksum SHA-256 checksum of image build inputs
   */
  public record Image(String checksum) {}

  /**
   * Synthesized Kubernetes manifests state.
   *
   * <p>Contains checksum, file counts, layer breakdown, and policy flags.
   *
   * @param summary manifest synthesis summary map
   */
  public record Manifests(Map<String, Object> summary) {

    public static Manifests of(Map<String, Object> summary) {
      return new Manifests(Map.copyOf(summary));
    }
  }
}
