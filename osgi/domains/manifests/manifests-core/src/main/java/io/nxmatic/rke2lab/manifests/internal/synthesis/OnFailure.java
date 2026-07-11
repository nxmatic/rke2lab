package io.nxmatic.rke2lab.manifests.internal.synthesis;

/**
 * Per-phase failure callback: invoked with the phase label and the cause before the throw is
 * wrapped.
 */
@FunctionalInterface
public interface OnFailure {
  void handle(String phase, Throwable cause);

  /** No-op handler for pipelines that opt out of per-phase failure notification. */
  static OnFailure noop() {
    return (phase, cause) -> {};
  }
}
