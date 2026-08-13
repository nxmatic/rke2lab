package io.seedmatic.rke2lab.sshtoage.edge;

/**
 * The {@code ssh-to-age} conversion failure: the tool was missing or exited non-zero. Unchecked,
 * because a failed conversion is a defect to surface fast (the SOPS age Secret cannot be rendered),
 * not a recoverable {@code Optional}-shaped outcome — it propagates uncaught to the top. It stays
 * package-private in the edge: behavior belongs to the implementation, never on the {@link
 * io.seedmatic.rke2lab.manifests.contract.SshToAgeConverter} contract face (contract-purity).
 */
final class SshToAgeConversionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  SshToAgeConversionException(String message) {
    super(message);
  }

  SshToAgeConversionException(String message, Throwable cause) {
    super(message, cause);
  }
}
