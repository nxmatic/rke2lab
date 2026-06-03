package io.nxmatic.rke2lab.manifests.systemd;

@FunctionalInterface
interface SynthesisOnFailure {
  void handle(String topic, Throwable cause);
}
