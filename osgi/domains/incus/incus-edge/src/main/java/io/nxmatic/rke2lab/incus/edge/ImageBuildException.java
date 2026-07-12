package io.nxmatic.rke2lab.incus.edge;

/**
 * The edge's private failure flow while shelling {@code distrobuilder} / {@code ssh}: thrown deep
 * in the process plumbing (a missing binary, a non-zero exit, an ssh failure) and caught once at
 * the {@link DistrobuilderImageBuilder#build} boundary, where it becomes the seam's human summary.
 * It stays package-private in the edge — behavior belongs to the implementation, never on the
 * {@link io.nxmatic.rke2lab.incus.contract.ImageBuilder} contract face (contract-purity).
 */
final class ImageBuildException extends RuntimeException {

  private final String summary;

  ImageBuildException(String message) {
    super(message);
    this.summary = message;
  }

  ImageBuildException(String message, Throwable cause) {
    super(message, cause);
    this.summary = message;
  }

  /**
   * The failure summary — the {@code @NonNull} message this was built with. {@link
   * Throwable#getMessage} is {@code @Nullable}; the {@link DistrobuilderImageBuilder#build}
   * boundary reads this so the seam's {@code Optional<String>} carries a proven-present reason.
   */
  String summary() {
    return summary;
  }
}
