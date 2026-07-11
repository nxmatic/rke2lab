package io.nxmatic.rke2lab.incus.contract;

/**
 * Signals that an {@link ImageBuilder#build(ImageBuildRequest)} could not produce the artifacts. A
 * seam exception — carries a human summary the host logs and propagates; no edge-internal type
 * (process handle, exit struct) leaks across the boundary.
 */
public final class ImageBuildException extends RuntimeException {

  public ImageBuildException(String message) {
    super(message);
  }

  public ImageBuildException(String message, Throwable cause) {
    super(message, cause);
  }
}
