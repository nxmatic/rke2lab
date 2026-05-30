package io.nxmatic.rk2lab.controlplane.incus;

import java.util.Map;

/**
 * Build metadata — state of build artifacts at deployment time.
 *
 * @param image container image build state
 * @param manifests synthesized Kubernetes manifests state
 */
public record BuildMetadata(Image image, Manifests manifests) {

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
