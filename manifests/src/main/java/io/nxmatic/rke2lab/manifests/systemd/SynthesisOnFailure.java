package io.nxmatic.rke2lab.manifests.systemd;

@FunctionalInterface
public interface SynthesisOnFailure {
  void handle(String topic, Throwable cause);
}
