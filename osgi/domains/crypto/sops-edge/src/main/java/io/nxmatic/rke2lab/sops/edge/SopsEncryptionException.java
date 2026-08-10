package io.nxmatic.rke2lab.sops.edge;

/**
 * Unchecked failure of the {@code sops} edge — the tool is absent or exited non-zero. A failed seal
 * is a defect to surface fast, not a recoverable outcome, so it propagates uncaught (the {@link
 * io.nxmatic.rke2lab.clusterpki.contract.SopsEncryptor} seam documents this).
 */
public final class SopsEncryptionException extends RuntimeException {

  public SopsEncryptionException(String message) {
    super(message);
  }

  public SopsEncryptionException(String message, Throwable cause) {
    super(message, cause);
  }
}
