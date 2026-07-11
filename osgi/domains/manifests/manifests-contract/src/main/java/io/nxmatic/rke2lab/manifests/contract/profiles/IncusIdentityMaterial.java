// @codebase
package io.nxmatic.rke2lab.manifests.contract.profiles;

/**
 * Stage A → Stage B Incus identity material published to synth-time layers via {@link
 * io.nxmatic.rke2lab.manifests.ManifestSynthesisContext}. Backs the {@code
 * <cluster>-incus-identity} Secret (see {@code IncusIdentitySecretManifestsUnit}) that hands the
 * {@code capn-provider} identity to the in-cluster CAPN provider (Stage B), which authenticates to
 * Incus via {@code LXCCluster.spec.secretRef} and has no access to Stage A's filesystem or Pulumi
 * outputs.
 *
 * <p>seed-master (the host) owns these materials and assembles them from the host world — the
 * {@code capn-provider} client cert from the application resources, the client key from {@code
 * .secrets}, the server cert + remote address from {@code ~/.config/incus/}. The OSGi unit must NOT
 * reach across the world frontier to read them itself; it receives them here and only renders the
 * Secret. Values are RAW (PEM text, plain address) — base64 is a Kubernetes Secret encoding
 * concern, applied by the unit at render time, not baked into this port type.
 *
 * <p>The default instance backs unit tests and ephemeral synth runs that don't go through
 * seed-master; {@link #isUnknown()} lets the unit skip rendering when no real identity was
 * supplied.
 */
public record IncusIdentityMaterial(
    String serverAddress, String serverCert, String clientCert, String clientKey) {

  /** Sentinel used when seed-master hasn't supplied Incus identity material (tests, ephemeral). */
  public static final String UNKNOWN = "unknown";

  private static final IncusIdentityMaterial DEFAULT = builder().build();

  public IncusIdentityMaterial {
    serverAddress = blankToUnknown(serverAddress);
    serverCert = blankToUnknown(serverCert);
    clientCert = blankToUnknown(clientCert);
    clientKey = blankToUnknown(clientKey);
  }

  /**
   * Default instance used by {@link
   * io.nxmatic.rke2lab.manifests.ManifestSynthesisContext#current()} when nothing was bound — live
   * callers always override via the synth request.
   */
  public static IncusIdentityMaterial unknown() {
    return DEFAULT;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** True when no real material was supplied — the unit skips rendering the identity Secret. */
  public boolean isUnknown() {
    return UNKNOWN.equals(clientCert) && UNKNOWN.equals(clientKey);
  }

  private static String blankToUnknown(String value) {
    return (value == null || value.isBlank()) ? UNKNOWN : value;
  }

  /**
   * The recommended construction path: names each material so the four PEM/address blobs can't be
   * positionally swapped.
   */
  public static final class Builder {
    private String serverAddress = UNKNOWN;
    private String serverCert = UNKNOWN;
    private String clientCert = UNKNOWN;
    private String clientKey = UNKNOWN;

    private Builder() {}

    public Builder serverAddress(final String v) {
      this.serverAddress = v;
      return this;
    }

    public Builder serverCert(final String v) {
      this.serverCert = v;
      return this;
    }

    public Builder clientCert(final String v) {
      this.clientCert = v;
      return this;
    }

    public Builder clientKey(final String v) {
      this.clientKey = v;
      return this;
    }

    public IncusIdentityMaterial build() {
      return new IncusIdentityMaterial(serverAddress, serverCert, clientCert, clientKey);
    }
  }
}
