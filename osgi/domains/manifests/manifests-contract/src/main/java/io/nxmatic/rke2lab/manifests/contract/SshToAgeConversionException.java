package io.nxmatic.rke2lab.manifests.contract;

/**
 * The {@link SshToAgeConverter} failure mode: the {@code ssh-to-age} tool was missing or exited
 * non-zero. Unchecked, because a failed conversion is a defect to surface fast (the SOPS age Secret
 * cannot be rendered), not a recoverable Optional-shaped outcome.
 */
public final class SshToAgeConversionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SshToAgeConversionException(String message) {
    super(message);
  }

  public SshToAgeConversionException(String message, Throwable cause) {
    super(message, cause);
  }
}
