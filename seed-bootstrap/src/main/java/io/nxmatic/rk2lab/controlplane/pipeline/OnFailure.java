package io.nxmatic.rk2lab.controlplane.pipeline;

@FunctionalInterface
public interface OnFailure {
  void handle(String topic, Throwable cause);
}
