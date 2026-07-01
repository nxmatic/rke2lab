package io.nxmatic.rke2lab.pipeline;

/**
 * Per-topic failure callback: invoked with the topic label and the cause before the throw is
 * wrapped.
 */
@FunctionalInterface
public interface OnFailure {
  void handle(String topic, Throwable cause);

  /** No-op handler for pipelines that opt out of per-topic failure notification. */
  static OnFailure noop() {
    return (topic, cause) -> {};
  }
}
