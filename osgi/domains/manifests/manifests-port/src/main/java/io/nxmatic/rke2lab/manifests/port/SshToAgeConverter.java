package io.nxmatic.rke2lab.manifests.port;

/**
 * Seam toward the {@code ssh-to-age} external tool: converts an OpenSSH private key into an age
 * private key. A pure point of contact — it reasons over nothing, it shells a tool — so it has no
 * {@code -core} and no port named after it; this contract is owned by the consumer that needs the
 * age key (the synthesis service, which runs the conversion as a pre-synthesis step). The realised
 * adapter lives in {@code ssh-to-age-edge}, which {@code @Component}-provides it; the synthesis
 * service binds it with a mandatory {@code @Reference}.
 *
 * <p>One target, one door: this seam carries ONLY the conversion. Reading the SSH key from its
 * key-store is a separate concern (a filesystem contact), done by the consumer before it calls here
 * — never folded into this edge.
 */
public interface SshToAgeConverter {

  /**
   * Convert an OpenSSH private key to an age private key.
   *
   * @param sshPrivateKey OpenSSH-format private key (PEM text)
   * @return the age private key in standard format
   * @throws SshToAgeConversionException if the tool is absent or exits non-zero
   */
  String toAgeKey(String sshPrivateKey);
}
