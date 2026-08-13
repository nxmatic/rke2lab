package io.seedmatic.rke2lab.incus.edge;

/**
 * The edge's failure flow while shelling {@code nix} / {@code ssh}: thrown deep in the process
 * plumbing (a missing binary, a non-zero exit, an ssh failure) and propagated OUT of {@link
 * CultivatingNixosImageBuilder#build} — unchecked, so the {@code void build} contract carries no
 * {@code throws} clause — to the incus scenario, which chains it into the failed step (message AND
 * cause) so the runbook shows the reason and the stack. Package-private: behavior belongs to the
 * implementation, never on the {@link io.seedmatic.rke2lab.incus.contract.ImageBuilder} contract
 * face.
 */
final class ImageBuildException extends RuntimeException {

  ImageBuildException(String message) {
    super(message);
  }

  ImageBuildException(String message, Throwable cause) {
    super(message, cause);
  }
}
