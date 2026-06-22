// @codebase
package io.nxmatic.rke2lab.manifests.port.profiles;

/**
 * The age private key published to synth-time layers via {@link
 * io.nxmatic.rke2lab.manifests.ManifestSynthesisContext}. Backs the {@code sops-age} Secret (see
 * {@code SopsAgeSecretManifestsUnit}) that Flux uses to decrypt SOPS-encrypted resources
 * in-cluster.
 *
 * <p>The synthesis service (the host-of-synthesis, an OSGi {@code @Component}) owns the assembly:
 * in a pre-synthesis step it reads the {@code rke2-cluster} SSH key from its key-store and converts
 * it via the {@code SshToAgeConverter} edge, then binds the result here. The unit MUST NOT reach
 * across to a file or shell a tool itself; it receives this and only renders the Secret. The value
 * is RAW (the age key text) — base64 is a Kubernetes Secret encoding concern, applied by the unit
 * at render time, not baked into this port type.
 *
 * <p>The default instance backs unit tests and ephemeral synth runs where no SSH key-store is
 * present; {@link #isUnknown()} lets the unit skip rendering when no real key was supplied.
 */
public record SopsAgeMaterial(String ageKey) {

  /** Sentinel used when no SSH key-store was available to derive the age key (tests, ephemeral). */
  public static final String UNKNOWN = "unknown";

  private static final SopsAgeMaterial DEFAULT = new SopsAgeMaterial(UNKNOWN);

  public SopsAgeMaterial {
    ageKey = (ageKey == null || ageKey.isBlank()) ? UNKNOWN : ageKey;
  }

  /**
   * Default instance used by {@link
   * io.nxmatic.rke2lab.manifests.ManifestSynthesisContext#current()} when nothing was bound — and
   * by the pre-synthesis step itself when no SSH key-store is present (it does not call the
   * converter).
   */
  public static SopsAgeMaterial unknown() {
    return DEFAULT;
  }

  /** True when no real age key was supplied — the unit skips rendering the Secret. */
  public boolean isUnknown() {
    return UNKNOWN.equals(ageKey);
  }
}
