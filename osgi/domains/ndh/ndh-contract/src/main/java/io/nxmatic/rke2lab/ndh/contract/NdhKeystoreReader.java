package io.nxmatic.rke2lab.ndh.contract;

/**
 * The one seam for reading the operator's ndh key inventory ({@code .ndh-ssh.d/keys.yaml}) — the
 * rke2lab ↔ nix-darwin-home boundary. A pure point of contact: it navigates the structured YAML and
 * returns fields by authority / key NAME; the names ({@code mammoth-skate-tls}, {@code
 * rke2-cluster}) are each consumer's semantic choice, but HOW the inventory is opened and read is
 * single-sourced behind this seam (realised by {@code ndh-core}). Consumed by manifests-core (the
 * rke2-cluster age key) and cluster-pki-core (the mammoth-skate-tls root + rke2-cluster).
 *
 * <p>{@link #present()} is the fail-soft gate (an ephemeral run has no key-store); the accessors
 * are fail-fast (a present-but-malformed store, or a missing field, raises — a defect to surface).
 */
public interface NdhKeystoreReader {

  /** Whether the key-store is present + readable (fail-soft gate; the accessors assume it is). */
  boolean present();

  /** The x509 certificate of a TLS authority — {@code authorities.<authority>.ca_crt}. */
  String authorityCert(String authority);

  /**
   * The domain a TLS authority is scoped to — {@code authorities.<authority>.domain} (e.g. {@code
   * mammoth-skate.ts.net}). The single source of truth for the tailnet/cert domain: the authority
   * signs {@code *.<domain>} certs, so the domain lives with it. Consumers (the manifests version
   * bumper's git-bot email, …) import it here rather than re-typing the literal.
   */
  String authorityDomain(String authority);

  /**
   * The private key of a TLS authority — {@code authorities.<authority>.private} (PEM or OpenSSH).
   */
  String authorityPrivate(String authority);

  /** The private key of an SSH key entry — {@code keys.<keyName>.private} (OpenSSH). */
  String sshPrivate(String keyName);
}
